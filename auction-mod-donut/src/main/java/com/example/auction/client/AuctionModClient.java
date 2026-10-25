package com.example.auction.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuctionModClient implements ClientModInitializer {
    private static final KeyBinding.Category CATEGORY =
        KeyBinding.Category.create(Identifier.of("auctionmod", "main"));

    /** Player names may start with "." (Bedrock players), e.g. ".JynxiTalks". */
    private static final Pattern NAME = Pattern.compile("^\\.?\\w{1,16}$");
    /** What follows "paid you": any currency symbol/space, then 5, 2.5, 1,000 + optional k/m/b/t. */
    private static final Pattern AMOUNT = Pattern.compile(
        "^[^\\d\\p{L}]*?([\\d,]*\\.?\\d+)\\s*([kKmMbBtT]?)\\W*$");

    private static KeyBinding openKey;
    static boolean debug;

    @Override
    public void onInitializeClient() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.auctionmod.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null && client.player != null) client.setScreen(new AuctionScreen());
            }
            AuctionState.I.tick(client);
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.of("auctionmod", "hud"), AuctionHud::render);

        // Payment notices arrive as system ("game") messages. Ignore the action-bar copy.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            handle(message.getString());
        });
    }

    private static void handle(String raw) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (debug && mc.player != null && raw.toLowerCase().contains("paid")) {
            mc.player.sendMessage(Text.literal("[Auction debug] " + raw), false);
        }
        String s = raw.replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF]", "").trim();
        int i = s.indexOf(" paid you");
        if (i <= 0) return;
        // The whole text before "paid you" must be just a player name, so chat lines can't fake a payment.
        String name = s.substring(0, i).trim();
        if (!NAME.matcher(name).matches()) return;
        Matcher m = AMOUNT.matcher(s.substring(i + " paid you".length()));
        if (!m.matches()) return;
        long amt = Money.parse(m.group(1) + m.group(2));
        if (amt > 0) AuctionState.I.onPayment(mc, name, amt);
    }
}
