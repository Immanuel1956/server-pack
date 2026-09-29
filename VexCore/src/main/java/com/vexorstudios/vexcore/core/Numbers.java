package com.vexorstudios.vexcore.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Pattern;

/** Money amounts: reading what players type, and writing numbers out. */
public final class Numbers {

    private static final Pattern PLAIN = Pattern.compile("\\d+(\\.\\d*)?|\\.\\d+");
    private static final Pattern NOISE = Pattern.compile("(?i)[§&][0-9a-fk-or]|[\\s\\u00A0\\u200B-\\u200D\\uFEFF_]");
    private static final String SUFFIXES = "kmbtq";

    private Numbers() {
    }

    /**
     * Reads "250", "1.5k", "2m", "3b", "1t", "1q", "1,000,000". Returns NaN for anything else:
     * negative numbers, NaN, Infinity, hex and exponents are all refused, so a bad amount can
     * never slip through a {@code balance < amount} check.
     */
    public static double parse(String raw) {
        if (raw == null) return Double.NaN;
        String s = NOISE.matcher(raw).replaceAll("").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return Double.NaN;
        double multiplier = 1;
        int suffix = SUFFIXES.indexOf(s.charAt(s.length() - 1));
        if (suffix >= 0) {
            multiplier = Math.pow(1000, suffix + 1);
            s = s.substring(0, s.length() - 1);
        }
        // "1,000,000" means thousands; a single comma with 1-2 digits after it is a decimal comma.
        if (s.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) s = s.replace(",", "");
        else s = s.replace(',', '.');
        if (!PLAIN.matcher(s).matches()) return Double.NaN;
        double value = Double.parseDouble(s) * multiplier;
        return Double.isFinite(value) ? value : Double.NaN;
    }

    /** A positive amount rounded to {@code decimals}, or NaN if it is not one. */
    public static double amount(String raw, int decimals) {
        double v = parse(raw);
        if (Double.isNaN(v)) return v;
        v = round(v, decimals);
        return v > 0 ? v : Double.NaN;
    }

    /**
     * Rounded in the given direction: charges round up, payouts down (money is never made up).
     * Floating-point noise is cleared first (to 9 places past the unit), so 0.57 x 100
     * (56.99999999999999) pays 57.00 and 1.1 x 3 (3.3000000000000003) charges 3.30, not a cent
     * off either way.
     */
    public static double round(double v, int decimals, RoundingMode mode) {
        if (!Double.isFinite(v)) return v;
        int scale = Math.max(0, decimals);
        return BigDecimal.valueOf(v).setScale(scale + 9, RoundingMode.HALF_EVEN).setScale(scale, mode).doubleValue();
    }

    public static double round(double v, int decimals) {
        if (!Double.isFinite(v)) return v;
        return BigDecimal.valueOf(v).setScale(Math.max(0, decimals), RoundingMode.HALF_DOWN).doubleValue();
    }

    /** 1234567.891 -> "1,234,567.89" (decimals only when there are any). */
    public static String full(double v, int decimals, String separator) {
        StringBuilder pattern = new StringBuilder("#,##0");
        if (decimals > 0) pattern.append('.').append("#".repeat(decimals));
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        if (separator != null && !separator.isEmpty()) symbols.setGroupingSeparator(separator.charAt(0));
        DecimalFormat format = new DecimalFormat(pattern.toString(), symbols);
        format.setGroupingUsed(separator != null && !separator.isEmpty());
        format.setRoundingMode(RoundingMode.HALF_DOWN);
        return format.format(v);
    }

    /** Money: "1,234" when whole, otherwise every decimal shown ("2.50", not "2.5"). */
    public static String money(double v, int decimals, String separator) {
        double rounded = round(v, decimals);
        if (decimals <= 0 || rounded == Math.rint(rounded)) return full(rounded, 0, separator);
        String out = full(rounded, decimals, separator);
        int dot = out.lastIndexOf('.');
        int have = dot < 0 ? 0 : out.length() - dot - 1;
        return (dot < 0 ? out + "." : out) + "0".repeat(Math.max(0, decimals - have));
    }

    /** 1234567 -> "1.23M" with the given suffixes (K, M, B, T, Q). */
    public static String shortened(double v, String[] suffixes) {
        return shortened(v, suffixes, 2, "");
    }

    /**
     * 1234567 -> "1.23m": one suffix per thousand step, {@code decimals} kept and never rounded up
     * (999,999 is "999.99k", not "1000k", and a balance never looks bigger than it is). Trailing
     * zeros are dropped: "1k", "1.5m". Under 1000 the number as it is.
     */
    public static String shortened(double v, String[] suffixes, int decimals, String separator) {
        double abs = Math.abs(v);
        if (abs < 1000 || suffixes.length == 0 || !Double.isFinite(v)) return full(v, 2, separator);
        int index = 0;
        while (index + 1 < suffixes.length && abs >= Math.pow(1000, index + 2)) index++;
        double scaled = round(v / Math.pow(1000, index + 1), decimals, RoundingMode.DOWN);
        return full(scaled, decimals, separator) + suffixes[index];
    }

    // ── How numbers are shown everywhere: numbers: in config.yml ────────────

    /**
     * {@code shorten}: SHORT (1.5k) or FULL (1,500). {@code from}: the first number that is
     * shortened. {@code decimals}: kept after shortening. {@code separator}: thousands separator for
     * numbers written in full.
     */
    public record Style(boolean shorten, double from, int decimals, String[] suffixes, String separator) {
    }

    public static final Style DEFAULT = new Style(true, 1000, 2, new String[]{"k", "m", "b", "t", "q"}, ",");
    private static volatile Style style = DEFAULT;

    public static Style style() {
        return style;
    }

    public static void style(Style s) {
        style = s == null ? DEFAULT : s;
    }

    /** Reads {@code numbers:} from config.yml (start and /vexcore reload). */
    public static void configure(org.bukkit.configuration.ConfigurationSection numbers) {
        if (numbers == null) {
            style = DEFAULT;
            return;
        }
        java.util.List<String> list = numbers.getStringList("suffixes");
        style = new Style(!"FULL".equalsIgnoreCase(numbers.getString("style", "SHORT").trim()),
                Math.max(1000, numbers.getDouble("short-from", 1000)), // nothing to shorten below 1k
                Math.max(0, Math.min(3, numbers.getInt("decimals", 2))),
                list.isEmpty() ? DEFAULT.suffixes() : list.toArray(new String[0]),
                numbers.getString("thousands-separator", ","));
    }

    /** A count (kills, votes, blocks, quest progress): "1.5k" with SHORT, "1,500" with FULL. */
    public static String format(double v) {
        Style s = style;
        if (s.shorten() && Math.abs(v) >= s.from()) return shortened(v, s.suffixes(), s.decimals(), s.separator());
        return full(v, 2, s.separator());
    }

    /** Money without its currency: "1.5k" with SHORT, "1,500.25" with FULL, "12.50" when small. */
    public static String formatMoney(double v, int decimals, String separator) {
        return style.shorten() ? shortMoney(v, decimals, separator) : money(v, decimals, separator);
    }

    /** Money shortened whatever the style (menus, scoreboards): "1.5k", and "12.50" when small. */
    public static String shortMoney(double v, int decimals, String separator) {
        Style s = style;
        if (Math.abs(v) < s.from()) return money(v, decimals, separator);
        return shortened(v, s.suffixes(), s.decimals(), separator);
    }
}
