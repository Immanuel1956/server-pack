package com.vexorstudios.vexcore.core;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations: "1d 2h 30m 15s" in configs and commands, and written out for players. */
public final class Time {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([dhms]?)");

    private Time() {
    }

    /** "1h30m", "90m", "45s", "2d", "3600" (plain seconds). -1 if it isn't a duration. */
    public static long seconds(String raw) {
        if (raw == null) return -1;
        String s = raw.toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) return -1;
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            try {
                long n = Long.parseLong(m.group(1));
                total = Math.addExact(total, Math.multiplyExact(n, switch (m.group(2)) {
                    case "d" -> 86400L;
                    case "h" -> 3600L;
                    case "m" -> 60L;
                    default -> 1L;
                }));
            } catch (ArithmeticException | NumberFormatException tooBig) {
                return -1; // more digits than any real duration: same as not a duration
            }
        }
        // Capped at ~100 years so "seconds * 1000" never overflows anywhere it is used.
        return end == s.length() && total <= 3_153_600_000L ? total : -1;
    }

    /**
     * Writes seconds out with the labels in {@code labels} (days, hours, minutes, seconds, units),
     * e.g. "1d 4h". {@code units} is how many parts are shown at most.
     */
    public static String format(long seconds, ConfigurationSection labels) {
        String d = labels == null ? "d" : labels.getString("days", "d");
        String h = labels == null ? "h" : labels.getString("hours", "h");
        String m = labels == null ? "m" : labels.getString("minutes", "m");
        String sec = labels == null ? "s" : labels.getString("seconds", "s");
        int units = labels == null ? 2 : Math.max(1, labels.getInt("units", 2));
        seconds = Math.max(0, seconds);
        long[] values = {seconds / 86400, seconds % 86400 / 3600, seconds % 3600 / 60, seconds % 60};
        String[] names = {d, h, m, sec};
        int first = 0;
        while (first < 3 && values[first] == 0) first++;
        StringBuilder out = new StringBuilder();
        for (int i = first; i < 4 && i < first + units; i++) {
            if (values[i] == 0) continue;
            if (out.length() > 0) out.append(' ');
            out.append(values[i]).append(names[i]);
        }
        return out.length() == 0 ? "0" + sec : out.toString();
    }
}
