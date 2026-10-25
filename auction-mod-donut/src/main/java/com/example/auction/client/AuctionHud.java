package com.example.auction.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Small always-on panel at the top of the screen showing the highest bid. */
final class AuctionHud {
    private AuctionHud() {}

    static void render(DrawContext ctx, RenderTickCounter tick) {
        AuctionState s = AuctionState.I;
        if (!s.active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        long now = System.currentTimeMillis();

        String l1 = s.amount + "x " + s.item.getName().getString();
        String l2 = s.leader == null
            ? "No bids yet - min $" + Money.format(s.minBid)
            : "Highest: $" + Money.format(s.leaderTotal) + " by " + s.leader;
        long left = Math.max(0, (s.endMs - now) / 1000);
        String l3 = Ui.time(left) + " left";

        int w = Math.max(tr.getWidth(l1), Math.max(tr.getWidth(l2), tr.getWidth(l3))) + 46;
        int h = 42;
        int x = (ctx.getScaledWindowWidth() - w) / 2;
        int y = 30;

        Ui.roundRect(ctx, x - 1, y - 1, w + 2, h + 2, 0xFF0B0D14);
        Ui.roundRect(ctx, x, y, w, h, Ui.PANEL);
        ctx.fill(x + 2, y + 2, x + 4, y + h - 2, Ui.ACCENT);
        ctx.drawItem(s.item, x + 10, y + 12);

        float flash = Ui.clamp(1f - (now - s.lastBidMs) / 1200f, 0f, 1f);
        if (s.lastBidMs == 0) flash = 0f;
        ctx.drawText(tr, l1, x + 32, y + 5, Ui.TEXT, false);
        ctx.drawText(tr, l2, x + 32, y + 16, Ui.lerp(Ui.GOOD, 0xFFFFFFFF, flash), false);
        ctx.drawText(tr, l3, x + 32, y + 27, Ui.MUTED, false);

        long total = Math.max(1, s.endMs - s.startMs);
        int bw = (int) ((w - 8) * Ui.clamp((float) (s.endMs - now) / total, 0f, 1f));
        ctx.fill(x + 4, y + h - 4, x + 4 + (w - 8), y + h - 2, Ui.PANEL3);
        ctx.fill(x + 4, y + h - 4, x + 4 + bw, y + h - 2, Ui.ACCENT);
    }
}
