package com.vexorstudios.vexcore.core;

import com.vexorstudios.vexcore.VexCore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dates written with a pattern from a config file (Java SimpleDateFormat, e.g. "dd.MM.yyyy HH:mm").
 * Each pattern is checked once: a typo logs one warning and falls back to the default pattern,
 * instead of throwing every time something is shown.
 */
public final class Dates {

    public static final String DEFAULT = "dd.MM.yyyy HH:mm";
    private static final Map<String, SimpleDateFormat> CACHE = new ConcurrentHashMap<>();

    private Dates() {
    }

    public static String format(long millis, String pattern) {
        return format(millis, pattern, null);
    }

    /** {@code zone} null or "" is the server's own time zone. Safe from any thread. */
    public static String format(long millis, String pattern, String zone) {
        String p = pattern == null || pattern.isBlank() ? DEFAULT : pattern;
        String z = zone == null ? "" : zone.trim();
        SimpleDateFormat proto = CACHE.computeIfAbsent(p + '\u0000' + z, k -> build(p, z));
        // SimpleDateFormat isn't thread-safe: every call formats with its own copy.
        return ((SimpleDateFormat) proto.clone()).format(new Date(millis));
    }

    private static SimpleDateFormat build(String pattern, String zone) {
        SimpleDateFormat f;
        try {
            f = new SimpleDateFormat(pattern);
        } catch (IllegalArgumentException bad) {
            VexCore core = VexCore.get();
            if (core != null) core.getLogger().warning("Date format '" + pattern + "' is not valid (" + bad.getMessage()
                    + "); using '" + DEFAULT + "'.");
            f = new SimpleDateFormat(DEFAULT);
        }
        if (!zone.isEmpty()) f.setTimeZone(TimeZone.getTimeZone(zone));
        return f;
    }
}
