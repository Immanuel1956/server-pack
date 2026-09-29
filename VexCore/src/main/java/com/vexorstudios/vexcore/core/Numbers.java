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
        double abs = Math.abs(v);
        int index = -1;
        while (index + 1 < suffixes.length && abs >= Math.pow(1000, index + 2)) index++;
        if (abs < 1000) return full(v, 2, "");
        double scaled = v / Math.pow(1000, index + 1);
        return full((long) (scaled * 100) / 100.0, 2, "") + suffixes[index];
    }
}
