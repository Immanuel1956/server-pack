package com.vexorstudios.vexcore.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vexorstudios.vexcore.VexCore;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Discord webhooks: one embed per call, sent off the main thread. Written in YAML as
 * <pre>
 *   webhook:
 *     enabled: true
 *     url: "https://discord.com/api/webhooks/..."
 *     username: "Reports"
 *     avatar: ""
 *     content: ""                  # text above the embed; "&lt;@&amp;ROLE_ID&gt;" pings a role
 *     ping-roles: []               # the only role ids content may ping
 *     embed:
 *       title / description / url / color / thumbnail / footer / timestamp
 *       fields: [{name: "...", value: "...", inline: true}]
 * </pre>
 * %placeholders% are filled in and colour codes removed. Nothing a player typed can ping anyone:
 * only role ids listed in ping-roles are allowed as mentions.
 */
public final class Webhook {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private Webhook() {
    }

    /** Sends the webhook described by {@code s}, if it is enabled and has a url. */
    public static void send(ConfigurationSection s, Map<String, ?> placeholders) {
        if (s == null || !s.getBoolean("enabled", false)) return;
        String url = s.getString("url", "").trim();
        if (!url.startsWith("https://")) return;
        JsonObject body = new JsonObject();
        String content = fill(s.getString("content", ""), placeholders);
        if (!content.isBlank()) body.addProperty("content", cut(content, 2000));
        String username = fill(s.getString("username", ""), placeholders);
        if (!username.isBlank()) body.addProperty("username", cut(username, 80));
        String avatar = fill(s.getString("avatar", ""), placeholders);
        if (avatar.startsWith("http")) body.addProperty("avatar_url", avatar);

        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        JsonArray roles = new JsonArray();
        for (String role : s.getStringList("ping-roles")) if (role.matches("\\d{5,25}")) roles.add(role);
        mentions.add("roles", roles);
        body.add("allowed_mentions", mentions);

        ConfigurationSection e = s.getConfigurationSection("embed");
        if (e != null) {
            JsonObject embed = new JsonObject();
            put(embed, "title", fill(e.getString("title", ""), placeholders), 256);
            put(embed, "description", fill(e.getString("description", ""), placeholders), 4000);
            String link = fill(e.getString("url", ""), placeholders);
            if (link.startsWith("http")) embed.addProperty("url", link);
            embed.addProperty("color", color(e.getString("color", "#A66CFF")));
            String thumb = fill(e.getString("thumbnail", ""), placeholders);
            if (thumb.startsWith("http")) {
                JsonObject t = new JsonObject();
                t.addProperty("url", thumb);
                embed.add("thumbnail", t);
            }
            String footer = fill(e.getString("footer", ""), placeholders);
            if (!footer.isBlank()) {
                JsonObject f = new JsonObject();
                f.addProperty("text", cut(footer, 2048));
                embed.add("footer", f);
            }
            if (e.getBoolean("timestamp", true)) embed.addProperty("timestamp", Instant.now().toString());
            JsonArray fields = new JsonArray();
            for (Map<?, ?> raw : e.getMapList("fields")) {
                if (fields.size() >= 25) break;
                Object n = raw.get("name");
                Object v = raw.get("value");
                String name = fill(n == null ? "" : String.valueOf(n), placeholders);
                String value = fill(v == null ? "" : String.valueOf(v), placeholders);
                if (name.isBlank() || value.isBlank()) continue;
                JsonObject field = new JsonObject();
                field.addProperty("name", cut(name, 256));
                field.addProperty("value", cut(value, 1024));
                field.addProperty("inline", Boolean.TRUE.equals(raw.get("inline")));
                fields.add(field);
            }
            if (!fields.isEmpty()) embed.add("fields", fields);
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            body.add("embeds", embeds);
        }
        post(url, body.toString());
    }

    private static void post(String url, String json) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "VexCore")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
        } catch (IllegalArgumentException bad) {
            VexCore.get().getLogger().warning("Webhook url is not valid: " + bad.getMessage());
            return;
        }
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            VexCore core = VexCore.get();
            if (core == null) return;
            if (error != null) core.getLogger().warning("Discord webhook failed: " + error.getMessage());
            else if (response.statusCode() >= 300) core.getLogger().warning("Discord webhook answered "
                    + response.statusCode() + ": " + cut(String.valueOf(response.body()), 200));
        });
    }

    private static void put(JsonObject o, String key, String value, int max) {
        if (value != null && !value.isBlank()) o.addProperty(key, cut(value, max));
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String fill(String text, Map<String, ?> placeholders) {
        if (text == null || text.isEmpty()) return "";
        return Text.fill(text, placeholders).replaceAll("&#[0-9a-fA-F]{6}|[&§][0-9a-fk-orA-FK-OR]", "");
    }

    private static int color(String hex) {
        try {
            return Integer.parseInt(hex.replace("#", "").replace("&", "").trim(), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xA66CFF;
        }
    }

    /** Text a player typed, shown on Discord as written: no markdown, no pings. */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replaceAll("([\\\\*_`~|>\\[\\]()#])", "\\\\$1").replace("@", "@\u200B");
    }
}
