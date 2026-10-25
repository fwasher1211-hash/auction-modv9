package com.example.auction.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

import static com.example.auction.client.Ui.*;

/** Auction menu: 1) pick an item  2) amount + minimum bid  3) duration. Live preview + Start button on the side. */
public class AuctionScreen extends Screen {
    private static final int CELL = 24;
    private static boolean announce = false;   // remembered between opens

    private enum Kind { INT, MONEY, TEXT }

    private static class Field {
        final Kind kind; final int maxLen; final long min; final long max; String text;
        Field(Kind kind, int maxLen, String text, long min, long max) {
            this.kind = kind; this.maxLen = maxLen; this.text = text; this.min = min; this.max = max;
        }
        long value() {
            if (kind == Kind.MONEY) { long v = Money.parse(text); return v < 0 ? min : v; }
            try { return Long.parseLong(text); } catch (NumberFormatException e) { return min; }
        }
        long clamped() { return Math.max(min, Math.min(max, value())); }
        void set(long v) {
            long c = Math.max(min, Math.min(max, v));
            text = kind == Kind.MONEY ? Money.format(c) : String.valueOf(c);
        }
        void normalize() { set(clamped()); }
    }

    private record Hit(int x, int y, int w, int h, Runnable run) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<ItemStack> allItems = new ArrayList<>();
    private final List<String> allKeys = new ArrayList<>();
    private final List<Boolean> allIsBlock = new ArrayList<>();
    private List<ItemStack> filtered = new ArrayList<>();
    private ItemStack selectedStack;      // null = nothing chosen yet
    private Item selected;
    private int filterMode = 0;           // 0 all, 1 blocks, 2 items
    private int lastFilterMode = -1;

    private int tab = 0;
    private float tabIndicator = 0f, open = 0f, dt = 0.016f, barAnim = 1f, slide = 0f;
    private long lastNs = System.nanoTime();

    private final Field search = new Field(Kind.TEXT, 32, "", 0, 0);
    private final Field amount = new Field(Kind.INT, 4, "1", 1, 9999);
    private final Field bid = new Field(Kind.MONEY, 10, "1m", 1, 1_000_000_000_000_000L);
    private final Field minutes = new Field(Kind.INT, 4, "10", 1, 1440);
    private Field focused;
    private String lastSearch = "\u0000";

    private float scrollTarget, scrollCur;
    private final Map<String, Float> hover = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();

    // layout (recomputed each frame). lx = fixed left edge, cx = content left edge (slides between tabs)
    private int px, py, pw, ph, lx, cx, cy, cw, cb, navY, sideW;
    private int gridX, gridY, gridW, gridH, gridOffX, cols = 1;

    public AuctionScreen() {
        super(Text.translatable("screen.auctionmod.title"));
        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) continue;
            ItemStack s = new ItemStack(item);
            if (s.isEmpty()) continue;
            allItems.add(s);
            allIsBlock.add(item instanceof BlockItem);
            allKeys.add((s.getName().getString() + " " + Registries.ITEM.getId(item).getPath()).toLowerCase(Locale.ROOT));
        }
        refilter();
    }

    @Override public boolean shouldPause() { return false; }

    // ---------------- helpers ----------------

    private float smooth(float k) { return 1f - (float) Math.exp(-k * dt); }

    private float hoverVal(String key, boolean hov) {
        float v = hover.getOrDefault(key, 0f);
        v += ((hov ? 1f : 0f) - v) * smooth(20f);
        hover.put(key, v);
        return v;
    }

    private String caret(Field f) {
        return focused == f && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
    }

    private static String fmtMinutes(int m) {
        if (m < 60) return m + " min";
        int h = m / 60, r = m % 60;
        return r == 0 ? h + " h" : h + " h " + r + " min";
    }

    private String fit(String s, int w) {
        if (textRenderer.getWidth(s) <= w) return s;
        while (s.length() > 1 && textRenderer.getWidth(s + "...") > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private void layout() {
        pw = Math.min(560, width - 16);
        ph = Math.min(300, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1f - open) * 16f);
        sideW = (!AuctionState.I.active && pw >= 480) ? 156 : 0;
        lx = px + 14;
        cw = pw - 28 - (sideW > 0 ? sideW + 12 : 0);
        cx = lx + Math.round(slide);
        cy = py + 56; cb = py + ph - 46; navY = py + ph - 34;
        gridX = cx; gridY = cy + 24; gridW = cw - 8;
        gridH = Math.max(CELL, (cb - 28) - gridY);
        cols = Math.max(1, gridW / CELL);
        gridOffX = (gridW - cols * CELL) / 2;
    }

    private void refilter() {
        lastSearch = search.text;
        lastFilterMode = filterMode;
        String q = search.text.toLowerCase(Locale.ROOT).trim();
        filtered = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++) {
            boolean isBlock = allIsBlock.get(i);
            if (filterMode == 1 && !isBlock) continue;
            if (filterMode == 2 && isBlock) continue;
            if (q.isEmpty() || allKeys.get(i).contains(q)) filtered.add(allItems.get(i));
        }
        scrollTarget = 0; scrollCur = 0;
    }

    private int rows() { return (filtered.size() + cols - 1) / cols; }
    private int maxScroll() { return Math.max(0, rows() * CELL - gridH); }

    private void setTab(int t) {
        t = Math.max(0, Math.min(2, t));
        if (t == tab) return;
        if (focused != null && focused.kind != Kind.TEXT) focused.normalize();
        focused = null;
        slide = (t > tab ? 1 : -1) * 40f;
        tab = t;
    }

    private void select(ItemStack s) {
        selectedStack = s.copy();
        selectedStack.setCount(1);
        selected = s.getItem();
    }

    private void useHeld() {
        var p = MinecraftClient.getInstance().player;
        if (p == null || p.getMainHandStack().isEmpty()) return;
        ItemStack held = p.getMainHandStack();
        select(held);
        amount.set(held.getCount());
    }

    private void submit() {
        if (selectedStack == null) return;
        amount.normalize(); bid.normalize(); minutes.normalize();
        AuctionState.I.start(MinecraftClient.getInstance(), selectedStack,
            (int) amount.clamped(), bid.clamped(), (int) minutes.clamped(), announce);
        close();
    }

    // ---------------- rendering ----------------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;
        open += (1f - open) * smooth(12f);
        slide *= (1f - smooth(14f));
        if (Math.abs(slide) < 0.4f) slide = 0f;
        hits.clear();
        layout();
        if (!search.text.equals(lastSearch) || filterMode != lastFilterMode) refilter();
        scrollTarget = clamp(scrollTarget, 0, maxScroll());
        scrollCur += (scrollTarget - scrollCur) * smooth(16f);

        ctx.fill(0, 0, width, height, ((int) (140 * open)) << 24);
        roundRect(ctx, px - 1, py - 1, pw + 2, ph + 2, 0xFF0B0D14);
        roundRect(ctx, px, py, pw, ph, PANEL);

        ctx.drawText(textRenderer, "Auction House", lx, py + 11, TEXT, false);
        String hint = "Right Shift to close";
        ctx.drawText(textRenderer, hint, px + pw - 14 - textRenderer.getWidth(hint), py + 11, MUTED, false);

        if (AuctionState.I.active) {
            drawStatus(ctx, mx, my);
            return;
        }

        drawTabs(ctx, mx, my);
        ItemStack tip = null;
        ctx.enableScissor(px + 2, py + 50, px + pw - 2, py + ph - 2);
        switch (tab) {
            case 0 -> tip = drawItemTab(ctx, mx, my);
            case 1 -> drawPriceTab(ctx, mx, my);
            default -> drawTimeTab(ctx, mx, my);
        }
        drawNav(ctx, mx, my);
        ctx.disableScissor();
        if (sideW > 0) drawSidebar(ctx, mx, my);
        if (tip != null) ctx.drawItemTooltip(textRenderer, tip, mx, my);
    }

    private void drawTabs(DrawContext ctx, int mx, int my) {
        String[] names = {"1  Item", "2  Price", "3  Time"};
        int tabsW = pw - 28;
        int tw = tabsW / 3;
        int ty = py + 26;
        tabIndicator += (tab * tw - tabIndicator) * smooth(16f);
        for (int i = 0; i < 3; i++) {
            int x = lx + i * tw;
            boolean hov = mx >= x && mx < x + tw && my >= ty && my < ty + 20;
            float v = hoverVal("tab" + i, hov);
            int col = lerp(MUTED, TEXT, i == tab ? 1f : v * 0.8f);
            ctx.drawText(textRenderer, names[i], x + (tw - textRenderer.getWidth(names[i])) / 2, ty + 6, col, false);
            final int idx = i;
            hits.add(new Hit(x, ty, tw, 20, () -> setTab(idx)));
        }
        ctx.fill(lx, ty + 22, px + pw - 14, ty + 23, PANEL3);
        int ix = lx + Math.round(tabIndicator);
        ctx.fill(ix + 6, ty + 21, ix + tw - 6, ty + 23, ACCENT);
    }

    private ItemStack drawItemTab(DrawContext ctx, int mx, int my) {
        // search box + filter chips
        int chipW = 44, chipGap = 4;
        int chipsStart = cx + cw - (chipW * 3 + chipGap * 2);
        int sw = chipsStart - cx - 4;
        roundRect(ctx, cx, cy, sw, 18, focused == search ? ACCENT : PANEL3);
        roundRect(ctx, cx + 1, cy + 1, sw - 2, 16, PANEL2);
        String shown; int col = TEXT;
        if (search.text.isEmpty() && focused != search) { shown = "Search every item..."; col = MUTED; }
        else shown = search.text + caret(search);
        ctx.drawText(textRenderer, shown, cx + 7, cy + 5, col, false);
        hits.add(new Hit(cx, cy, sw, 18, () -> focused = search));
        String[] fl = {"All", "Blocks", "Items"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            button(ctx, mx, my, chipsStart + i * (chipW + chipGap), cy, chipW, 18, fl[i], true, filterMode == i, () -> filterMode = m);
        }

        // grid
        roundRect(ctx, gridX, gridY, gridW, gridH, PANEL2);
        ItemStack hovered = null;
        boolean inGrid = mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH;
        int scroll = Math.round(scrollCur);
        ctx.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        int first = Math.max(0, scroll / CELL);
        int rowsVisible = gridH / CELL + 2;
        for (int r = first; r < first + rowsVisible; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i >= filtered.size()) break;
                ItemStack s = filtered.get(i);
                int x = gridX + gridOffX + c * CELL;
                int y = gridY + r * CELL - scroll;
                boolean hov = inGrid && mx >= x && mx < x + CELL && my >= y && my < y + CELL;
                boolean sel = selected == s.getItem();
                float v = hoverVal("c" + i, hov);
                if (sel) roundRect(ctx, x + 1, y + 1, CELL - 2, CELL - 2, ACCENT);
                else if (v > 0.02f) roundRect(ctx, x + 1, y + 1, CELL - 2, CELL - 2, lerp(PANEL2, PANEL3, v));
                ctx.drawItem(s, x + 4, y + 4);
                if (hov) hovered = s;
            }
        }
        ctx.disableScissor();
        if (filtered.isEmpty()) {
            String t = "Nothing found";
            ctx.drawText(textRenderer, t, gridX + (gridW - textRenderer.getWidth(t)) / 2, gridY + gridH / 2 - 4, MUTED, false);
        }
        int max = maxScroll();
        if (max > 0) {
            int th = Math.max(16, gridH * gridH / (rows() * CELL));
            int ty = gridY + (int) ((gridH - th) * (scrollCur / max));
            roundRect(ctx, gridX + gridW + 3, gridY, 3, gridH, PANEL2);
            roundRect(ctx, gridX + gridW + 3, ty, 3, th, MUTED);
        }

        // selected strip
        roundRect(ctx, cx, cb - 22, cw, 22, PANEL2);
        int stripText = cw - 108;
        if (selectedStack == null) {
            ctx.drawText(textRenderer, fit("Click an item (or use the one in your hand)", stripText), cx + 8, cb - 15, MUTED, false);
        } else {
            ctx.drawItem(selectedStack, cx + 4, cb - 19);
            ctx.drawText(textRenderer, fit(selectedStack.getName().getString(), stripText - 24), cx + 26, cb - 15, TEXT, false);
        }
        button(ctx, mx, my, cx + cw - 96, cb - 21, 92, 20, "Use held item", true, false, this::useHeld);
        return hovered;
    }

    private void drawPriceTab(DrawContext ctx, int mx, int my) {
        stepper(ctx, mx, my, cy + 2, "Amount", "How many items you are selling", MUTED, amount);
        int[] av = {1, 16, 32, 64, 128, 256};
        int g = 4, w = (cw - g * 5) / 6;
        for (int i = 0; i < 6; i++) {
            int v = av[i];
            button(ctx, mx, my, cx + i * (w + g), cy + 52, w, 20, String.valueOf(v), true, amount.clamped() == v, () -> amount.set(v));
        }

        int y = cy + 84;
        ctx.drawText(textRenderer, "Minimum bid", cx, y, TEXT, false);
        ctx.drawText(textRenderer, "Type 500k, 2.5m, 1b ... or pick one below", cx, y + 11, MUTED, false);
        int fw = 120, bw = 34, gap = 4;
        int total = bw + gap + fw + gap + bw;
        int x = cx + (cw - total) / 2;
        int by = y + 26;
        button(ctx, mx, my, x, by, bw, 20, "/2", true, false, () -> bid.set(bid.clamped() / 2));
        int fx = x + bw + gap;
        roundRect(ctx, fx, by, fw, 20, focused == bid ? ACCENT : PANEL3);
        roundRect(ctx, fx + 1, by + 1, fw - 2, 18, PANEL2);
        String t = "$" + bid.text + caret(bid);
        ctx.drawText(textRenderer, t, fx + (fw - textRenderer.getWidth(t)) / 2, by + 6, TEXT, false);
        hits.add(new Hit(fx, by, fw, 20, () -> focused = bid));
        button(ctx, mx, my, fx + fw + gap, by, bw, 20, "x2", true, false, () -> bid.set(bid.clamped() * 2));

        long[] vals = {100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L, 50_000_000L, 100_000_000L, 1_000_000_000L};
        int cg = 4, cwid = (cw - cg * 7) / 8;
        for (int i = 0; i < 8; i++) {
            long v = vals[i];
            button(ctx, mx, my, cx + i * (cwid + cg), y + 54, cwid, 20, Money.format(v), true, bid.clamped() == v, () -> bid.set(v));
        }
    }

    private void drawTimeTab(DrawContext ctx, int mx, int my) {
        int[] vals = {1, 5, 10, 30, 60, 360, 1440};
        String[] labels = {"1m", "5m", "10m", "30m", "1h", "6h", "24h"};
        int gap = 4, w = (cw - gap * 6) / 7;
        ctx.drawText(textRenderer, "Quick pick", cx, cy + 2, TEXT, false);
        for (int i = 0; i < 7; i++) {
            int v = vals[i];
            button(ctx, mx, my, cx + i * (w + gap), cy + 16, w, 20, labels[i], true, minutes.clamped() == v, () -> minutes.set(v));
        }
        stepper(ctx, mx, my, cy + 48, "Custom time", "Minutes (1 - 1440)", MUTED, minutes);

        button(ctx, mx, my, cx, cy + 104, cw, 20,
            "Announce bids in public chat: " + (announce ? "ON" : "OFF"), true, announce, () -> announce = !announce);
        button(ctx, mx, my, cx, cy + 128, cw, 20,
            "Show raw payment messages (troubleshooting): " + (AuctionModClient.debug ? "ON" : "OFF"),
            true, AuctionModClient.debug, () -> AuctionModClient.debug = !AuctionModClient.debug);

        if (sideW == 0) {
            int sy = cb - 44;
            roundRect(ctx, cx, sy, cw, 44, PANEL2);
            if (selectedStack == null) {
                String t = "No item selected - go back to step 1";
                ctx.drawText(textRenderer, t, cx + (cw - textRenderer.getWidth(t)) / 2, sy + 18, WARN, false);
            } else {
                ctx.drawItem(selectedStack, cx + 12, sy + 14);
                ctx.drawText(textRenderer, fit(amount.clamped() + "x " + selectedStack.getName().getString(), cw - 50), cx + 38, sy + 10, TEXT, false);
                ctx.drawText(textRenderer, "Min bid $" + Money.format(bid.clamped()) + "  -  " + fmtMinutes((int) minutes.clamped()),
                    cx + 38, sy + 24, MUTED, false);
            }
        }
    }

    /** Live preview of the auction + an always-available Start button. */
    private void drawSidebar(DrawContext ctx, int mx, int my) {
        int x = px + pw - 14 - sideW, y = cy - 4, w = sideW, h = (navY + 22) - y;
        roundRect(ctx, x, y, w, h, PANEL2);
        ctx.drawText(textRenderer, "PREVIEW", x + 10, y + 8, MUTED, false);

        roundRect(ctx, x + 10, y + 22, 40, 40, PANEL3);
        if (selectedStack != null) {
            ctx.drawItem(selectedStack, x + 22, y + 34);
            ctx.drawText(textRenderer, fit(selectedStack.getName().getString(), w - 66), x + 58, y + 28, TEXT, false);
            ctx.drawText(textRenderer, amount.clamped() + "x", x + 58, y + 41, MUTED, false);
        } else {
            ctx.drawText(textRenderer, "?", x + 28, y + 38, MUTED, false);
            ctx.drawText(textRenderer, "No item yet", x + 58, y + 34, MUTED, false);
        }

        String[][] rows = {
            {"Min bid", "$" + Money.format(bid.clamped())},
            {"Duration", fmtMinutes((int) minutes.clamped())},
            {"Announce", announce ? "On" : "Off"}
        };
        int ry = y + 76;
        for (String[] r : rows) {
            ctx.drawText(textRenderer, r[0], x + 10, ry, MUTED, false);
            ctx.drawText(textRenderer, r[1], x + w - 10 - textRenderer.getWidth(r[1]), ry, TEXT, false);
            ry += 15;
        }
        ctx.fill(x + 10, ry + 2, x + w - 10, ry + 3, PANEL3);

        boolean ready = selectedStack != null;
        String st = ready ? "Ready to start" : "Pick an item first";
        ctx.drawText(textRenderer, st, x + 10, ry + 10, ready ? GOOD : WARN, false);

        button(ctx, mx, my, x + 10, y + h - 32, w - 20, 22, "Start Auction", ready, true, this::submit);
    }

    /** Shown instead of the 3 tabs while an auction is running. */
    private void drawStatus(DrawContext ctx, int mx, int my) {
        AuctionState s = AuctionState.I;
        long nowMs = System.currentTimeMillis();
        int w = pw - 28;

        roundRect(ctx, lx, cy - 20, w, 44, PANEL2);
        ctx.drawItem(s.item, lx + 12, cy - 6);
        ctx.drawText(textRenderer, s.amount + "x " + s.item.getName().getString(), lx + 38, cy - 10, TEXT, false);
        ctx.drawText(textRenderer, "Min bid $" + Money.format(s.minBid), lx + 38, cy + 4, MUTED, false);

        int y = cy + 32;
        roundRect(ctx, lx, y, w, 44, PANEL2);
        String top = s.leader == null ? "No bids yet" : "$" + Money.format(s.leaderTotal) + "  -  " + s.leader;
        ctx.drawText(textRenderer, "Highest bid", lx + 12, y + 7, MUTED, false);
        ctx.drawText(textRenderer, top, lx + 12, y + 19, s.leader == null ? TEXT : GOOD, false);
        long left = Math.max(0, (s.endMs - nowMs) / 1000);
        String lt = Ui.time(left) + " left";
        ctx.drawText(textRenderer, lt, lx + w - 12 - textRenderer.getWidth(lt), y + 19, TEXT, false);
        float target = clamp((float) (s.endMs - nowMs) / Math.max(1, s.endMs - s.startMs), 0f, 1f);
        barAnim += (target - barAnim) * smooth(10f);
        roundRect(ctx, lx + 12, y + 35, w - 24, 4, PANEL3);
        int fill = Math.max(0, (int) ((w - 24) * barAnim));
        if (fill > 4) roundRect(ctx, lx + 12, y + 35, fill, 4, ACCENT);

        int ly = y + 54;
        ctx.drawText(textRenderer, "Bids (total paid by each player)", lx, ly, MUTED, false);
        List<Map.Entry<String, Long>> list = new ArrayList<>(s.totals.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (list.isEmpty()) {
            ctx.drawText(textRenderer, "Waiting for someone to /pay you...", lx, ly + 14, MUTED, false);
        }
        for (int i = 0; i < Math.min(6, list.size()); i++) {
            Map.Entry<String, Long> e = list.get(i);
            boolean lead = s.leader != null && s.leader.equalsIgnoreCase(e.getKey());
            ctx.drawText(textRenderer, (i + 1) + ". " + e.getKey(), lx, ly + 14 + i * 12, lead ? GOOD : TEXT, false);
            String v = "$" + Money.format(e.getValue());
            ctx.drawText(textRenderer, v, lx + w - textRenderer.getWidth(v), ly + 14 + i * 12, lead ? GOOD : MUTED, false);
        }

        button(ctx, mx, my, lx, navY, 110, 22, "Cancel Auction", true, false, () -> AuctionState.I.cancel(MinecraftClient.getInstance()));
        button(ctx, mx, my, lx + 118, navY, 90, 22, "Test bid", true, false, () ->
            AuctionState.I.onPayment(MinecraftClient.getInstance(), "TestPlayer", Math.max(1, AuctionState.I.minBid)));
        button(ctx, mx, my, lx + w - 80, navY, 80, 22, "Close", true, true, this::close);
    }

    private void drawNav(DrawContext ctx, int mx, int my) {
        button(ctx, mx, my, lx, navY, 80, 22, "Back", tab > 0, false, () -> setTab(tab - 1));
        String step = "Step " + (tab + 1) + " of 3";
        ctx.drawText(textRenderer, step, lx + (cw - textRenderer.getWidth(step)) / 2, navY + 7, MUTED, false);
        int nx = lx + cw - 110;
        if (tab < 2) {
            button(ctx, mx, my, nx, navY, 110, 22, "Next", tab != 0 || selectedStack != null, true, () -> setTab(tab + 1));
        } else if (sideW == 0) {
            button(ctx, mx, my, nx, navY, 110, 22, "Start Auction", selectedStack != null, true, this::submit);
        }
    }

    private void button(DrawContext ctx, int mx, int my, int x, int y, int w, int h,
                        String label, boolean enabled, boolean primary, Runnable onClick) {
        boolean hov = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        float v = hoverVal(label + "@" + y + "#" + w, hov);
        int base = !enabled ? PANEL2 : primary ? ACCENT : PANEL3;
        int hi = primary ? ACCENT_HI : 0xFF3A4360;
        roundRect(ctx, x, y, w, h, lerp(base, hi, v));
        String shown = fit(label, w - 6);
        ctx.drawText(textRenderer, shown, x + (w - textRenderer.getWidth(shown)) / 2, y + (h - 8) / 2,
            enabled ? TEXT : MUTED, false);
        if (enabled) hits.add(new Hit(x, y, w, h, onClick));
    }

    private void stepper(DrawContext ctx, int mx, int my, int y, String label, String sub, int subCol, Field f) {
        ctx.drawText(textRenderer, label, cx, y, TEXT, false);
        ctx.drawText(textRenderer, sub, cx, y + 11, subCol, false);
        int bw = 34, fw = 84, gap = 4;
        int total = bw * 4 + fw + gap * 4;
        int x = cx + (cw - total) / 2;
        int by = y + 26;
        int fx = x + 2 * (bw + gap);
        int[] steps = {-10, -1, 1, 10};
        for (int i = 0; i < 4; i++) {
            int bx = i < 2 ? x + i * (bw + gap) : fx + fw + gap + (i - 2) * (bw + gap);
            int s = steps[i];
            button(ctx, mx, my, bx, by, bw, 20, (s > 0 ? "+" : "") + s, true, false, () -> f.set(f.clamped() + s));
        }
        roundRect(ctx, fx, by, fw, 20, focused == f ? ACCENT : PANEL3);
        roundRect(ctx, fx + 1, by + 1, fw - 2, 18, PANEL2);
        String t = f.text + caret(f);
        ctx.drawText(textRenderer, t, fx + (fw - textRenderer.getWidth(t)) / 2, by + 6, TEXT, false);
        hits.add(new Hit(fx, by, fw, 20, () -> focused = f));
    }

    // ---------------- input (1.21.9+ event objects) ----------------

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        double mx = click.x(), my = click.y();
        Field prev = focused;
        focused = null;
        if (prev != null && prev.kind != Kind.TEXT) prev.normalize();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(mx, my)) { h.run().run(); return true; }
        }
        if (!AuctionState.I.active && tab == 0
            && mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
            double gx = mx - gridX - gridOffX;
            if (gx >= 0) {
                int c = (int) (gx / CELL);
                int r = (int) ((my - gridY + Math.round(scrollCur)) / CELL);
                int i = r * cols + c;
                if (c < cols && i >= 0 && i < filtered.size()) select(filtered.get(i));
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (!AuctionState.I.active && tab == 0
            && mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
            scrollTarget = clamp(scrollTarget - (float) vAmount * CELL * 2f, 0, maxScroll());
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (focused == null) return false;
        String s = input.asString();
        boolean any = false;
        for (char chr : s.toCharArray()) {
            if (focused.text.length() >= focused.maxLen) break;
            boolean ok;
            switch (focused.kind) {
                case INT -> ok = chr >= '0' && chr <= '9';
                case MONEY -> {
                    char lc = Character.toLowerCase(chr);
                    boolean digit = lc >= '0' && lc <= '9';
                    boolean dot = lc == '.' && !focused.text.contains(".") && !focused.text.isEmpty();
                    boolean suffix = "kmbt".indexOf(lc) >= 0 && !focused.text.isEmpty()
                        && Character.isDigit(focused.text.charAt(focused.text.length() - 1));
                    boolean hasSuffix = !focused.text.isEmpty()
                        && Character.isLetter(focused.text.charAt(focused.text.length() - 1));
                    ok = !hasSuffix && (digit || dot || suffix);
                    chr = lc;
                }
                default -> ok = chr >= 32 && chr != 127;
            }
            if (ok) { focused.text += chr; any = true; }
        }
        return any;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();
        if (focused != null) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!focused.text.isEmpty()) focused.text = focused.text.substring(0, focused.text.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (focused.kind != Kind.TEXT) focused.normalize();
                focused = null;
                return true;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) { close(); return true; }
        return super.keyPressed(input);
    }
}
