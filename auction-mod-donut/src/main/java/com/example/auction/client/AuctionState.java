package com.example.auction.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * The one running auction (client-side, lives on the seller's game).
 * Bids are /pay payments: a player's bid is the TOTAL they have paid during the auction.
 */
public final class AuctionState {
    public static final AuctionState I = new AuctionState();

    public boolean active;
    public ItemStack item = ItemStack.EMPTY;   // count 1, used for the icon/name
    public int amount;
    public long minBid, startMs, endMs;
    public boolean announce;
    public String leader;
    public long leaderTotal, lastBidMs;
    public final Map<String, Long> totals = new LinkedHashMap<>();

    private String pendingAnnounce;
    private long lastSendMs;

    // ---------- start / end ----------

    public void start(MinecraftClient mc, ItemStack stack, int amount, long minBid, int minutes, boolean announce) {
        this.item = stack.copy();
        this.amount = amount;
        this.minBid = minBid;
        this.startMs = System.currentTimeMillis();
        this.endMs = startMs + minutes * 60_000L;
        this.announce = announce;
        this.leader = null;
        this.leaderTotal = 0;
        this.lastBidMs = 0;
        this.totals.clear();
        this.active = true;

        String name = item.getName().getString();
        msg(mc, Text.literal("Started: " + amount + "x " + name + " | min bid $" + Money.format(minBid)
            + " | " + minutes + " min. Players /pay you to bid - highest total wins.").formatted(Formatting.YELLOW));
        if (announce) {
            queue("AUCTION: " + amount + "x " + name + " | min bid $" + Money.format(minBid) + " | ends in "
                + minutes + " min | /pay " + mc.getSession().getUsername() + " <amount> to bid. Highest total wins!");
        }
    }

    public void cancel(MinecraftClient mc) {
        if (!active) return;
        active = false;
        msg(mc, Text.literal("Auction cancelled.").formatted(Formatting.RED));
        refunds(mc, null);
        if (announce) queue("Auction cancelled.");
    }

    private void finish(MinecraftClient mc) {
        active = false;
        String name = amount + "x " + item.getName().getString();
        if (leader == null) {
            msg(mc, Text.literal("Ended with no valid bids for " + name + ".").formatted(Formatting.GOLD));
            if (announce) queue("Auction over - no winner for " + name + ".");
        } else {
            msg(mc, Text.literal("SOLD! " + leader + " won " + name + " with $" + Money.format(leaderTotal)
                + ". Give them the item!").formatted(Formatting.GREEN));
            if (announce) queue("Auction over! " + leader + " won " + name + " with $" + Money.format(leaderTotal) + "!");
        }
        refunds(mc, leader);
    }

    /** Tell the seller who still needs a refund (we never auto-pay anyone). */
    private void refunds(MinecraftClient mc, String winner) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : totals.entrySet()) {
            if (winner != null && e.getKey().equalsIgnoreCase(winner)) continue;
            if (e.getValue() > 0) lines.add("/pay " + e.getKey() + " " + Money.format(e.getValue()));
        }
        if (lines.isEmpty()) return;
        msg(mc, Text.literal("Refunds owed (not sent automatically):").formatted(Formatting.GOLD));
        for (String l : lines) msg(mc, Text.literal("  " + l).formatted(Formatting.GRAY));
    }

    // ---------- payments ----------

    public void onPayment(MinecraftClient mc, String name, long amt) {
        if (!active || amt <= 0) return;
        long total = totals.merge(name, amt, Long::sum);
        boolean isLeader = leader != null && leader.equalsIgnoreCase(name);

        if (isLeader) {
            leaderTotal = total;
            lastBidMs = System.currentTimeMillis();
            msg(mc, Text.literal(name + " raised their bid to $" + Money.format(total) + " (still leading).")
                .formatted(Formatting.GREEN));
            announceLead();
        } else if (total >= minBid && total > leaderTotal) {
            leader = name;
            leaderTotal = total;
            lastBidMs = System.currentTimeMillis();
            msg(mc, Text.literal("NEW HIGHEST BID: " + name + " - $" + Money.format(total)).formatted(Formatting.GREEN));
            announceLead();
        } else if (total < minBid) {
            msg(mc, Text.literal(name + " paid $" + Money.format(amt) + " (total $" + Money.format(total)
                + ") - below the minimum $" + Money.format(minBid) + ".").formatted(Formatting.GRAY));
        } else {
            msg(mc, Text.literal(name + " paid $" + Money.format(amt) + " (total $" + Money.format(total)
                + ") - not enough to beat $" + Money.format(leaderTotal) + ".").formatted(Formatting.GRAY));
        }
    }

    private void announceLead() {
        if (!announce) return;
        long left = Math.max(0, (endMs - System.currentTimeMillis()) / 1000);
        queue("Highest bid: " + leader + " $" + Money.format(leaderTotal) + " for " + amount + "x "
            + item.getName().getString() + " | " + Ui.time(left) + " left");
    }

    // ---------- per-tick ----------

    public void tick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (pendingAnnounce != null && mc.player != null && now - lastSendMs >= 3000) {
            mc.player.networkHandler.sendChatMessage(pendingAnnounce);
            pendingAnnounce = null;
            lastSendMs = now;
        }
        if (active && now >= endMs) finish(mc);
    }

    private void queue(String s) { pendingAnnounce = s; }

    private static void msg(MinecraftClient mc, Text t) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Auction] ").formatted(Formatting.GOLD).append(t), false);
        }
    }
}
