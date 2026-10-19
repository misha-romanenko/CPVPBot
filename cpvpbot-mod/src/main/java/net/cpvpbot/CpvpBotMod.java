package net.cpvpbot;

import baritone.api.BaritoneAPI;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CPVP Bot Control - client mod that exposes a localhost TCP control channel for a
 * Baritone-powered Crystal PvP bot. Every observable action is appended to
 * <gamedir>/cpvpbot-actions.log as evidence for the test report.
 */
public class CpvpBotMod implements ClientModInitializer {

    public static Path logFile;
    public static final List<String> chatLines = new ArrayList<>();
    public static int chatSeq = 0;
    public static long ticks = 0;

    // --- autonomy (client thread only) ---
    public static final String SERVER = System.getProperty("cpvpbot.server", "valkyriemc.net");
    public static final String PASSWORD = System.getProperty("cpvpbot.password",
            System.getenv().getOrDefault("BOT_PASSWORD", ""));
    private static boolean registered = false;
    private static boolean spawnedOnce = false;
    private static boolean connecting = false;
    private static long lastConnectTick = -10000;
    private static long lastChatTick = -10000;
    private static long cooldownUntil = 0;
    private static int fails = 0;
    private static String lastDisconnectReason = null;

    // --- watchers (client thread only) ---
    private static int prevTotems = -1;
    private static boolean wasUsing = false;
    private static String usingItemDesc = "";
    private static int verifyTicks = -1;
    private static BlockPos verifyPos;
    private static String verifyItem = "";
    private static int trackedCrystalId = -1;
    private static int trackedCrystalTicks = 0;
    private static BlockPos lastPos = null;

    @Override
    public void onInitializeClient() {
        Minecraft mc = Minecraft.getInstance();
        try {
            logFile = mc.gameDirectory.toPath().resolve("cpvpbot-actions.log");
            Files.deleteIfExists(logFile);
        } catch (IOException e) {
            logFile = Path.of("cpvpbot-actions.log");
        }

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChat(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(CpvpBotMod::tick);

        // Baritone setup: don't let it break blocks in a (protected) arena
        try {
            BaritoneAPI.getSettings().allowBreak.value = false;
        } catch (Throwable t) {
            // settings may not be ready yet; non-fatal
        }

        ControlServer.start();
        log("MOD", "cpvpbot control mod initialized (control port " + ControlServer.PORT + ")");
    }

    // ------------------------------------------------------------------ logging
    public static void log(String event, String detail) {
        String line = String.format("[%s] %-14s %s", Instant.now(), event, detail);
        System.out.println(line);
        if (logFile != null) {
            try {
                Files.writeString(logFile, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    public static void onChat(String text) {
        if (text == null || text.isBlank()) return;
        synchronized (chatLines) {
            chatLines.add(chatSeq + " " + text);
            chatSeq++;
            if (chatLines.size() > 600) chatLines.remove(0);
        }
        log("CHAT_IN", text.trim());
        lastChatTick = ticks;

        Minecraft mc = Minecraft.getInstance();
        String lower = text.toLowerCase();

        // AuthMe: "Please, register to the server with the command: /register <password> ..."
        if (!registered && lower.contains("register") && lower.contains("/register")) {
            Actions.sendChat(mc, "/register " + PASSWORD + " " + PASSWORD);
            registered = true;
            log("MOD", "auto-registered");
            return;
        }
        // AuthMe: "Please, login with the command: /login <password>"
        if (lower.contains("/login") && lower.contains("password")) {
            Actions.sendChat(mc, "/login " + PASSWORD);
            log("MOD", "auto-logged-in");
            return;
        }
        // Duel request: "You have received a duel request from X in ..." -> accept it
        java.util.regex.Matcher m = DUEL_REQUEST.matcher(text);
        if (m.find()) {
            String from = m.group(1);
            Actions.sendChat(mc, "/duel accept " + from);
            log("MOD", "auto-accepted duel from " + from);
        }
    }

    private static final java.util.regex.Pattern DUEL_REQUEST =
            java.util.regex.Pattern.compile("duel request from (\\S+?) in");

    // ------------------------------------------------------------------ watchers
    static void scheduleVerify(BlockPos pos, String item, int ticks) {
        verifyPos = pos;
        verifyItem = item;
        verifyTicks = ticks;
    }

    static void trackCrystal(int entityId) {
        trackedCrystalId = entityId;
        trackedCrystalTicks = 0;
    }

    private static void tick(Minecraft mc) {
        ticks++;

        // ---- autonomy: (re)connect to the server by itself ----
        if (mc.player == null || mc.level == null) {
            // log hidden connect failures (e.g. Velocity "logging in too fast")
            JsonObject si = new JsonObject();
            Actions.screenInfo(mc, si);
            if (si.has("disconnectReason")) {
                String reason = si.get("disconnectReason").getAsString();
                if (!reason.equals(lastDisconnectReason)) {
                    lastDisconnectReason = reason;
                    log("DISCONNECT", reason);
                    // rate-limited by the proxy: back off hard instead of hammering
                    if (reason.contains("too fast") || reason.contains("throttled")) {
                        cooldownUntil = ticks + 1200;           // wait 60s before next try
                        log("MOD", "throttled - waiting 60s before reconnecting");
                    }
                }
            }
            Object conn = mc.getConnection();
            net.minecraft.client.gui.screens.Screen scr = mc.gui != null ? mc.gui.screen() : null;
            boolean failedNow = connecting && scr instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
            if (failedNow) {
                // attempt died (usually proxy RST = "logging in too fast" throttle)
                connecting = false;
                fails++;
                long wait = Math.min(300L * (1L << Math.min(fails, 5)), 1800L); // 15s,30s,60s,120s,240s,300s
                cooldownUntil = ticks + wait;
                log("MOD", "connect attempt " + fails + " failed - backing off " + (wait / 20) + "s");
            }
            if (conn != null) {
                connecting = false;              // handshake progressed
            } else if (connecting && ticks - lastConnectTick > 600) {
                connecting = false;              // attempt died (>30s > MC connect timeout)
                fails++;
                cooldownUntil = ticks + Math.min(300L * (1L << Math.min(fails, 5)), 1800L);
            }
            if (!connecting && conn == null && ticks > cooldownUntil
                    && ticks - lastConnectTick > 300) {
                connecting = true;               // every 15s when idle (respecting cooldown)
                lastConnectTick = ticks;
                Actions.doConnect(mc, SERVER);
            }
            return;
        }
        connecting = false;
        lastDisconnectReason = null;
        fails = 0;                                                   // healthy - clear backoff
        if (!spawnedOnce) {
            spawnedOnce = true;
            registered = false;                                     // fresh session
            log("MOD", "in-world as " + mc.player.getGameProfile().name()
                    + " @ " + Actions.posString(mc));
        }

        int totems = Actions.countItem(mc.player, Items.TOTEM_OF_UNDYING);
        if (prevTotems >= 0 && totems < prevTotems && mc.player.getAbsorptionAmount() > 0.01f) {
            log("TOTEM_POP", String.format("totems %d -> %d | health=%.1f absorption=%.1f food=%d",
                    prevTotems, totems, mc.player.getHealth(), mc.player.getAbsorptionAmount(),
                    mc.player.getFoodData().getFoodLevel()));
        }
        prevTotems = totems;

        // item use (golden apple eat) completed
        if (wasUsing && !mc.player.isUsingItem()) {
            log("USE_COMPLETE", "finished using " + usingItemDesc
                    + " | health=" + String.format("%.1f", mc.player.getHealth())
                    + " food=" + mc.player.getFoodData().getFoodLevel());
        }
        wasUsing = mc.player.isUsingItem();
        if (wasUsing) {
            usingItemDesc = BuiltInRegistries.ITEM
                    .getKey(mc.player.getInventory().getItem(mc.player.getInventory().getSelectedSlot()).getItem())
                    .toString();
        }

        // scheduled block verification after placement
        if (verifyTicks > 0) {
            verifyTicks--;
            if (verifyTicks == 0 && verifyPos != null && mc.level != null) {
                BlockState st = mc.level.getBlockState(verifyPos);
                log("PLACE_VERIFY", verifyPos + " expected=" + verifyItem + " -> state=" + Actions.stateString(st));
            }
        }

        // attacked end crystal disappearing == popped
        if (trackedCrystalId >= 0) {
            Entity e = Actions.getEntity(mc, trackedCrystalId);
            trackedCrystalTicks++;
            if (e == null) {
                log("CRYSTAL_POPPED", "tracked end crystal id=" + trackedCrystalId + " exploded");
                trackedCrystalId = -1;
            } else if (trackedCrystalTicks > 400) {
                trackedCrystalId = -1;
            }
        }

        // movement evidence every 2 seconds
        if (ticks % 40 == 0) {
            BlockPos bp = mc.player.blockPosition();
            if (lastPos == null) {
                lastPos = bp;
            } else if (!bp.equals(lastPos)) {
                double d = Math.sqrt(bp.distSqr(lastPos));
                log("MOVE", String.format("%.1f blocks | %s | pathing=%s",
                        d, Actions.posString(mc), Actions.baritonePathing()));
                lastPos = bp;
            }
        }
    }

    /** Convenience for the driver: recent chat lines with sequence numbers. */
    public static JsonArray chatSlice(int since) {
        JsonArray arr = new JsonArray();
        synchronized (chatLines) {
            for (String l : chatLines) {
                int sp = l.indexOf(' ');
                try {
                    int seq = Integer.parseInt(l.substring(0, sp));
                    if (seq >= since) arr.add(l);
                } catch (Exception ignored) {
                }
            }
        }
        return arr;
    }
}
