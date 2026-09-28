package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /vexcore import setupcore}: copies data from an old SetupCore install
 * (plugins/SetupCore/setupcore.db and teleports.db) into VexCore. Only ever runs from that
 * command. Every enabled feature copies what it understands; a feature that fails is rolled back
 * on its own without stopping the others. Online players are saved first and loaded again after,
 * so they see the imported data straight away.
 */
public final class SetupCoreImport {

    private SetupCoreImport() {
    }

    /** The old SetupCore data, read-only. */
    public static final class Source implements AutoCloseable {
        private final File folder;
        private final Connection main;
        private final Connection teleports;

        Source(File folder) throws SQLException {
            this.folder = folder;
            this.main = open(new File(folder, "setupcore.db"));
            this.teleports = open(new File(folder, "teleports.db"));
        }

        private static Connection open(File file) throws SQLException {
            return file.isFile() ? DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()) : null;
        }

        /** plugins/SetupCore, for YAML-based data. */
        public File folder() {
            return folder;
        }

        /** setupcore.db, or null if missing. */
        public Connection main() {
            return main;
        }

        /** teleports.db (spawn, afk), or null if missing. */
        public Connection teleports() {
            return teleports;
        }

        /** True if the database has this table. */
        public static boolean has(Connection c, String table) throws SQLException {
            if (c == null) return false;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        @Override
        public void close() {
            for (Connection c : new Connection[]{main, teleports}) {
                try {
                    if (c != null) c.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /** What was copied, per feature. */
    public static final class Report {
        private final Map<String, List<String>> lines = new LinkedHashMap<>();
        private String current = "core";

        void feature(String id) {
            current = id;
        }

        /** Records "&lt;count&gt; &lt;what&gt;" for the feature being imported. */
        public void add(int count, String what) {
            lines.computeIfAbsent(current, k -> new ArrayList<>()).add(count + " " + what);
        }

        public void note(String text) {
            lines.computeIfAbsent(current, k -> new ArrayList<>()).add(text);
        }
    }

    public static File folder(VexCore plugin) {
        return new File(plugin.getDataFolder().getParentFile(), "SetupCore");
    }

    /** Runs the import. Call on the global thread. */
    public static void run(VexCore plugin, CommandSender sender) {
        Messages messages = plugin.messages();
        File folder = folder(plugin);
        if (!new File(folder, "setupcore.db").isFile() && !new File(folder, "teleports.db").isFile()) {
            messages.send(null, sender, "import-not-found", Messages.map("folder", folder.getPath()));
            return;
        }
        messages.send(null, sender, "import-started", Map.of());
        plugin.data().unloadAll();
        List<Feature> features = new ArrayList<>(plugin.features().active());
        Report report = new Report();
        plugin.database().query("import setupcore", target -> {
            try (Source source = new Source(folder)) {
                for (Feature feature : features) {
                    report.feature(feature.id());
                    Savepoint savepoint = target.setSavepoint();
                    try {
                        feature.importSetupCore(source, target, report);
                        target.releaseSavepoint(savepoint);
                    } catch (SQLException | RuntimeException error) {
                        target.rollback(savepoint);
                        report.note("FAILED: " + error.getMessage());
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "SetupCore import of " + feature.id() + " failed", error);
                    }
                }
            }
            return report;
        }).whenComplete((done, error) -> Scheduler.global(() -> {
            plugin.data().loadAll();
            if (error != null) {
                messages.send(null, sender, "import-failed", Messages.map("error", String.valueOf(error.getMessage())));
                return;
            }
            if (done.lines.isEmpty()) {
                messages.send(null, sender, "import-empty", Map.of());
                return;
            }
            for (Map.Entry<String, List<String>> e : done.lines.entrySet()) {
                messages.send(null, sender, "import-line", Messages.map("feature", e.getKey(), "result", String.join(", ", e.getValue())));
            }
            messages.send(null, sender, "import-done", Map.of());
        }));
    }
}
