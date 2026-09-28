package com.vexorstudios.vexcore.features.tebex;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's face as 8x8 RGB pixels (the skin's face with the hat layer on top), for drawing
 * their head in chat. Looked up off the main thread and remembered for half an hour.
 */
final class HeadArt {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final long KEEP_MS = 30 * 60 * 1000L;

    private record Cached(int[][] face, long at) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /** Steve's face, when a skin can't be found. */
    private static final int[][] STEVE = {
            {0x2F200D, 0x2B1E0D, 0x2F1F0F, 0x281C0B, 0x241808, 0x261A0A, 0x2B1E0D, 0x2A1D0D},
            {0x2B1E0D, 0x2B1E0D, 0x2B1E0D, 0x332411, 0x422A12, 0x3F2A15, 0x2C1E0E, 0x281C0B},
            {0x2B1E0D, 0xB6896C, 0xBD8E72, 0xC69680, 0xBD8B72, 0xBD8E74, 0xAC765A, 0x342512},
            {0xAA7D66, 0xB4846D, 0xAA7D66, 0xAD806D, 0x9C725C, 0xBB8972, 0x9C694C, 0x9C694C},
            {0xB4846D, 0xFFFFFF, 0x523D89, 0xB57B67, 0xBB8972, 0x523D89, 0xFFFFFF, 0xAA7D66},
            {0x9C6346, 0xB37B62, 0xB78272, 0x6A4030, 0x6A4030, 0xBE886C, 0xA26A47, 0x805334},
            {0x905E43, 0x965F40, 0x40200A, 0x40200A, 0x40200A, 0x40200A, 0x8F5E3E, 0x815339},
            {0x6F452C, 0x6D432A, 0x40200A, 0x422310, 0x3D1F0A, 0x40200A, 0x83553B, 0x7A4E33}};

    private HeadArt() {
    }

    /** The face of the player, Steve's if their skin can't be read. Completes off the main thread. */
    static CompletableFuture<int[][]> face(UUID uuid, String name) {
        String key = uuid != null ? uuid.toString() : String.valueOf(name).toLowerCase(java.util.Locale.ROOT);
        Cached hit = CACHE.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at < KEEP_MS) return CompletableFuture.completedFuture(hit.face);
        Player online = uuid != null ? Bukkit.getPlayer(uuid) : name == null ? null : Bukkit.getPlayerExact(name);
        PlayerProfile known = online != null ? online.getPlayerProfile() : null;
        return CompletableFuture.supplyAsync(() -> {
            int[][] face = STEVE;
            try {
                PlayerProfile profile = known;
                if (profile == null || profile.getTextures().getSkin() == null) {
                    profile = Bukkit.createProfile(uuid, name);
                    profile.complete(true); // asks Mojang; this thread may wait
                }
                URL skin = profile.getTextures().getSkin();
                if (skin != null) face = read(skin.toString());
            } catch (Exception | LinkageError ignored) {
                // no skin, offline mode, Mojang down: Steve
            }
            CACHE.put(key, new Cached(face, System.currentTimeMillis()));
            if (CACHE.size() > 500) CACHE.clear();
            return face;
        });
    }

    private static int[][] read(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.replace("http://", "https://")))
                .timeout(Duration.ofSeconds(8)).header("User-Agent", "VexCore").GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 300) return STEVE;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
        if (image == null || image.getWidth() < 64 || image.getHeight() < 32) return STEVE;
        int scale = image.getWidth() / 64; // HD skins
        int[][] face = new int[8][8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = image.getRGB((8 + x) * scale, (8 + y) * scale);
                int hat = image.getRGB((40 + x) * scale, (8 + y) * scale);
                int alpha = hat >>> 24;
                face[y][x] = (alpha > 0 ? blend(base, hat, alpha) : base) & 0xFFFFFF;
            }
        }
        return face;
    }

    private static int blend(int under, int over, int alpha) {
        float a = alpha / 255f;
        int r = Math.round(((over >> 16) & 0xFF) * a + ((under >> 16) & 0xFF) * (1 - a));
        int g = Math.round(((over >> 8) & 0xFF) * a + ((under >> 8) & 0xFF) * (1 - a));
        int b = Math.round((over & 0xFF) * a + (under & 0xFF) * (1 - a));
        return (r << 16) | (g << 8) | b;
    }
}
