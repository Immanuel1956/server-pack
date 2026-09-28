package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite or MySQL, chosen in database.yml.
 *
 * <p>Every read and write runs on one database thread, in the order it was asked for. That is
 * what keeps a quick rejoin from reading a player's data before their quit save has landed, and
 * two quick changes from reaching the database the wrong way round. Each job is one transaction.
 *
 * <p>Both engines get the same SQL except for upserts; use {@link #upsert} and
 * {@link #insertIgnore}. Stick to VARCHAR/INT/BIGINT/DOUBLE/TEXT and give text keys a length.
 */
public final class Database {

    public enum Type {SQLITE, MYSQL}

    @FunctionalInterface
    public interface Work {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    private final VexCore plugin;
    private Type type = Type.SQLITE;
    private String url;
    private final Properties properties = new Properties();
    private String prefix = "";
    private Connection connection;
    private ExecutorService thread;
    private volatile Thread worker;
    private volatile boolean closing;

    public Database(VexCore plugin) {
        this.plugin = plugin;
    }

    public Type type() {
        return type;
    }

    /** Opens the database described by database.yml. Throws if it cannot be reached. */
    public void open(ConfigurationSection cfg) throws SQLException {
        closing = false;
        properties.clear();
        prefix = cfg.getString("table-prefix", "vex_");
        String configured = cfg.getString("type", "SQLITE").trim().toUpperCase(Locale.ROOT);
        type = configured.equals("MYSQL") || configured.equals("MARIADB") ? Type.MYSQL : Type.SQLITE;
        if (type == Type.SQLITE) {
            File file = new File(plugin.getDataFolder(), cfg.getString("sqlite.file", "data/vexcore.db"));
            file.getParentFile().mkdirs();
            url = "jdbc:sqlite:" + file.getAbsolutePath();
            load("org.sqlite.JDBC");
        } else {
            ConfigurationSection my = cfg.getConfigurationSection("mysql");
            if (my == null) throw new SQLException("database.yml has type MYSQL but no mysql section");
            url = "jdbc:mysql://" + my.getString("host", "localhost") + ":" + my.getInt("port", 3306)
                    + "/" + my.getString("database", "vexcore");
            properties.setProperty("user", my.getString("username", "root"));
            properties.setProperty("password", my.getString("password", ""));
            // Speed settings (database.yml's properties can override any of them):
            // batches go as one multi-row INSERT, prepared statements are reused, and the driver
            // doesn't ask the server for things it already knows.
            properties.setProperty("rewriteBatchedStatements", "true");
            properties.setProperty("cachePrepStmts", "true");
            properties.setProperty("prepStmtCacheSize", "250");
            properties.setProperty("prepStmtCacheSqlLimit", "2048");
            properties.setProperty("useServerPrepStmts", "true");
            properties.setProperty("useLocalSessionState", "true");
            properties.setProperty("cacheServerConfiguration", "true");
            properties.setProperty("elideSetAutoCommits", "true");
            properties.setProperty("maintainTimeStats", "false");
            properties.setProperty("tcpKeepAlive", "true");
            ConfigurationSection extra = my.getConfigurationSection("properties");
            if (extra != null) for (String key : extra.getKeys(false)) properties.setProperty(key, String.valueOf(extra.get(key)));
            load("com.mysql.cj.jdbc.Driver");
        }
        connection = connect();
        thread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VexCore-Database");
            worker = t;
            t.setDaemon(true);
            return t;
        });
    }

    private static void load(String driver) {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException ignored) {
            // DriverManager may still find it through the service loader.
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, properties);
        if (type == Type.SQLITE) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA temp_store=MEMORY");
                st.execute("PRAGMA cache_size=-16000"); // 16 MB of pages kept in memory
            }
        }
        // Every job is its own transaction: autocommit stays off and each job ends in a commit,
        // instead of switching autocommit off and on again (two extra round trips per job).
        c.setAutoCommit(false);
        return c;
    }

    /** The live connection, reconnecting if MySQL dropped it. Database thread only. */
    private long lastUsed; // database thread only

    private Connection connection() throws SQLException {
        // A MySQL connection is only pinged after sitting idle (the server may have dropped it);
        // pinging before every job would double every job's round trips.
        long now = System.currentTimeMillis();
        boolean idle = now - lastUsed > 30_000;
        lastUsed = now;
        if (connection == null || connection.isClosed() || (type == Type.MYSQL && idle && !connection.isValid(3))) {
            try {
                if (connection != null) connection.close();
            } catch (SQLException ignored) {
            }
            connection = connect();
        }
        return connection;
    }

    /** Queues a write. Runs after everything queued before it, in one transaction. */
    public void queue(String label, Work work) {
        submit(label, c -> {
            work.run(c);
            return null;
        });
    }

    /** Queues a read (or write) whose result is needed. Completes on the database thread. */
    public <T> CompletableFuture<T> query(String label, Query<T> query) {
        return submit(label, query);
    }

    private <T> CompletableFuture<T> submit(String label, Query<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable job = () -> {
            try {
                future.complete(transaction(query));
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "Database job '" + label + "' failed", error);
                future.completeExceptionally(error);
            }
        };
        if (closing && Thread.currentThread() == worker) {
            job.run(); // the outer transaction already committed before its callback
            return future;
        }
        ExecutorService t = thread;
        try {
            if (t == null) throw new RejectedExecutionException("database is closed");
            t.execute(job);
        } catch (RejectedExecutionException closed) {
            plugin.getLogger().warning("Database job '" + label + "' arrived after the database closed and was dropped.");
            future.completeExceptionally(closed);
        }
        return future;
    }

    private <T> T transaction(Query<T> query) throws SQLException {
        Connection c = connection();
        try {
            T result = query.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException error) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
            }
            throw error;
        }
    }

    /** Finishes every queued job, then closes. Blocks for at most 60 seconds. */
    public void close() {
        closing = true;
        ExecutorService t = thread;
        thread = null;
        if (t != null) {
            t.shutdown();
            try {
                if (!t.awaitTermination(60, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("The database did not finish its queued work within 60 seconds.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {
        }
        connection = null;
    }

    // ── SQL helpers ───────────────────────────────────────────────────────

    /** A table name with the configured prefix. */
    public String table(String name) {
        return prefix + name;
    }

    /**
     * INSERT that updates {@code columns} when a row with the same {@code keys} exists.
     * Parameters are the keys first, then the columns.
     */
    public String upsert(String table, String[] keys, String... columns) {
        if (columns.length == 0) return insertIgnore(table, keys);
        String all = String.join(", ", concat(keys, columns));
        String marks = String.join(", ", Arrays.stream(concat(keys, columns)).map(c -> "?").toList());
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table(table))
                .append(" (").append(all).append(") VALUES (").append(marks).append(") ");
        if (type == Type.MYSQL) {
            sql.append("ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) sql.append(", ");
                sql.append(columns[i]).append(" = VALUES(").append(columns[i]).append(')');
            }
        } else {
            sql.append("ON CONFLICT(").append(String.join(", ", keys)).append(") DO UPDATE SET ");
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) sql.append(", ");
                sql.append(columns[i]).append(" = excluded.").append(columns[i]);
            }
        }
        return sql.toString();
    }

    /** INSERT that does nothing when the row already exists. */
    public String insertIgnore(String table, String... columns) {
        String marks = String.join(", ", Arrays.stream(columns).map(c -> "?").toList());
        return (type == Type.MYSQL ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ") + table(table)
                + " (" + String.join(", ", columns) + ") VALUES (" + marks + ")";
    }

    /** Adds an index on {@code columns} if it isn't there yet. */
    public void index(String table, String columns) {
        String name = table(table) + "_" + columns.replaceAll("[^A-Za-z0-9]+", "_");
        queue("index " + name, c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate((type == Type.MYSQL ? "CREATE INDEX " : "CREATE INDEX IF NOT EXISTS ")
                        + name + " ON " + table(table) + " (" + columns + ")");
            } catch (SQLException e) {
                if (type != Type.MYSQL || e.getErrorCode() != 1061) throw e; // 1061: already exists
            }
        });
    }

    /**
     * Adds a column to a table made by an older version, if it isn't there yet. {@code definition}
     * is the type with a default, e.g. {@code "INT NOT NULL DEFAULT 0"}. Queued after the schema.
     */
    public void addColumn(String table, String column, String definition) {
        queue("column " + table + "." + column, c -> {
            String name = table(table);
            for (String candidate : new String[]{name, name.toUpperCase(Locale.ROOT)}) {
                try (java.sql.ResultSet rs = c.getMetaData().getColumns(null, null, candidate, null)) {
                    while (rs.next()) if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) return;
                }
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE " + name + " ADD COLUMN " + column + " " + definition);
            } catch (SQLException e) {
                if (!String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("duplicate")) throw e;
            }
        });
    }

    /** Runs CREATE TABLE statements; {@code {t}} in each is replaced by the prefixed name. */
    public void schema(String table, String... statements) {
        queue("schema " + table, c -> {
            try (Statement st = c.createStatement()) {
                for (String sql : statements) st.executeUpdate(sql.replace("{t}", table(table)));
            }
        });
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
