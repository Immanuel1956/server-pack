package com.vexorstudios.vexcore.gui;

import java.util.ArrayList;
import java.util.List;

/** Reads slot lists: {@code 10}, {@code "0-8, 45-53"} or {@code [0, 1, "5-8"]}. */
public final class Slots {

    private Slots() {
    }

    public static List<Integer> parse(Object raw) {
        List<Integer> out = new ArrayList<>();
        add(out, raw);
        return out;
    }

    private static void add(List<Integer> out, Object raw) {
        if (raw == null) return;
        if (raw instanceof Number n) {
            out.add(n.intValue());
        } else if (raw instanceof List<?> list) {
            for (Object o : list) add(out, o);
        } else {
            for (String part : raw.toString().split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int dash = part.indexOf('-', 1);
                try {
                    if (dash > 0) {
                        int from = Integer.parseInt(part.substring(0, dash).trim());
                        int to = Integer.parseInt(part.substring(dash + 1).trim());
                        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) out.add(i);
                    } else {
                        out.add(Integer.parseInt(part));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
