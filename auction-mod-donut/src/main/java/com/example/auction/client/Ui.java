package com.example.auction.client;

import net.minecraft.client.gui.DrawContext;

/** Shared colours and tiny drawing helpers. */
final class Ui {
    private Ui() {}

    static final int PANEL = 0xFF171B26, PANEL2 = 0xFF202636, PANEL3 = 0xFF2A3145;
    static final int ACCENT = 0xFF5B8CFF, ACCENT_HI = 0xFF7BA2FF, GOOD = 0xFF3DDC84;
    static final int TEXT = 0xFFE8ECF4, MUTED = 0xFF8A93A8, WARN = 0xFFFF6B6B;

    static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static void roundRect(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x + 2, y, x + w - 2, y + h, col);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, col);
        c.fill(x, y + 2, x + w, y + h - 2, col);
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    static String time(long secs) {
        if (secs >= 3600) return String.format("%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        return String.format("%d:%02d", secs / 60, secs % 60);
    }
}
