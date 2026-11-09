package com.ledgerplus;

import java.util.Locale;

public final class Fmt {
    private Fmt() {}

    public static String money(double v, boolean shortNum) {
        String sign = v < 0 ? "-" : "";
        double a = Math.abs(v);
        if (shortNum) {
            if (a >= 1e12) return sign + "$" + trim(a / 1e12) + "T";
            if (a >= 1e9) return sign + "$" + trim(a / 1e9) + "B";
            if (a >= 1e6) return sign + "$" + trim(a / 1e6) + "M";
            if (a >= 1e3) return sign + "$" + trim(a / 1e3) + "K";
            return sign + "$" + trim(a);
        }
        return sign + "$" + String.format(Locale.US, "%,.2f", a).replaceAll("\\.00$", "");
    }

    public static String money(double v) {
        return money(v, LedgerPlus.CONFIG.shortNumbers);
    }

    public static String signed(double v) {
        return (v > 0 ? "+" : "") + money(v);
    }

    private static String trim(double d) {
        String s = String.format(Locale.US, "%.2f", d);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return s;
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    /** "1,500" + "k" -> 1500000 style parsing. */
    public static double parseAmount(String num, String suffix) {
        double v = Double.parseDouble(num.replace(",", ""));
        if (suffix == null) return v;
        switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k": return v * 1e3;
            case "m": return v * 1e6;
            case "b": return v * 1e9;
            case "t": return v * 1e12;
            default: return v;
        }
    }

    /** "#RRGGBB" -> opaque ARGB, or null if invalid. */
    public static Integer hex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (!s.matches("[0-9a-fA-F]{6}")) return null;
        return 0xFF000000 | Integer.parseInt(s, 16);
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public static int alpha(int rgb, int percent) {
        int a = Math.max(0, Math.min(255, percent * 255 / 100));
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
