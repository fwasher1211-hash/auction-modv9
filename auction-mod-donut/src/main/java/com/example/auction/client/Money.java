package com.example.auction.client;

import java.math.BigDecimal;
import java.util.Locale;

/** Parses and formats DonutSMP-style amounts: 500k, 2.5m, 1b, 1,000,000. */
public final class Money {
    private Money() {}

    /** @return amount, or -1 if the text isn't a valid amount. */
    public static long parse(String raw) {
        if (raw == null) return -1;
        String s = raw.toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").trim();
        if (!s.matches("^(\\d+\\.?\\d*|\\.\\d+)[kmbt]?$")) return -1;
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        if (Character.isLetter(last)) {
            s = s.substring(0, s.length() - 1);
            mult = switch (last) {
                case 'k' -> 1_000L;
                case 'm' -> 1_000_000L;
                case 'b' -> 1_000_000_000L;
                default -> 1_000_000_000_000L;
            };
        }
        try {
            return new BigDecimal(s).multiply(BigDecimal.valueOf(mult)).longValue();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String format(long v) {
        if (v >= 1_000_000_000_000L) return trim(v / 1e12) + "t";
        if (v >= 1_000_000_000L) return trim(v / 1e9) + "b";
        if (v >= 1_000_000L) return trim(v / 1e6) + "m";
        if (v >= 1_000L) return trim(v / 1e3) + "k";
        return String.valueOf(v);
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
