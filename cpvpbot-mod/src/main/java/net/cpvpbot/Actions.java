package net.cpvpbot;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.stream.Collectors;

/**
 * All control commands. dispatch() MUST run on the Minecraft client thread.
 *
 * Commands (JSON per line over TCP 127.0.0.1:4777):
 *   status, chat, chatlog, select, look, place, use, attack, block,
 *   goto, follow, stop, bcmd, ping
 */
public class Actions {

    // ------------------------------------------------------------------ helpers
    static JsonObject okBase() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    static String str(JsonObject c, String k, String def) {
        return c.has(k) && !c.get(k).isJsonNull() ? c.get(k).getAsString() : def;
    }

    static int num(JsonObject c, String k, int def) {
        return c.has(k) && !c.get(k).isJsonNull() ? c.get(k).getAsInt() : def;
    }

    static boolean bool(JsonObject c, String k, boolean def) {
        return c.has(k) && !c.get(k).isJsonNull() ? c.get(k).getAsBoolean() : def;
    }

    public static String posString(Minecraft mc) {
        return String.format("(%.1f, %.1f, %.1f)", mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    /** Send a chat line or /command on the client thread. */
    public static void sendChat(Minecraft mc, String text) {
        try {
            if (text.startsWith("/")) {
                mc.getConnection().sendCommand(text.substring(1));
            } else {
                mc.getConnection().sendChat(text);
            }
            CpvpBotMod.log("SENT_CHAT", text);
        } catch (Throwable t) {
            CpvpBotMod.log("SENT_CHAT", "FAILED " + text + " (" + t + ")");
        }
    }

    /** Reflect the current GUI screen + any hidden connect-failure reason into a JSON object. */
    public static void screenInfo(Minecraft mc, JsonObject o) {
        net.minecraft.client.gui.screens.Screen s = mc.gui != null ? mc.gui.screen() : null;
        o.addProperty("screen", s == null ? "null" : s.getClass().getName());
        if (s instanceof net.minecraft.client.gui.screens.DisconnectedScreen ds) {
            try {
                java.lang.reflect.Field f =
                        net.minecraft.client.gui.screens.DisconnectedScreen.class
                                .getDeclaredField("details");
                f.setAccessible(true);
                Object details = f.get(ds);
                Object reason = details.getClass().getMethod("reason").invoke(details);
                o.addProperty("disconnectReason", String.valueOf(reason));
            } catch (Throwable t) {
                o.addProperty("disconnectReason", "read_failed:" + t);
            }
            try {
                java.lang.reflect.Field tf =
                        net.minecraft.client.gui.screens.Screen.class.getDeclaredField("title");
                tf.setAccessible(true);
                o.addProperty("title", String.valueOf(tf.get(ds)));
            } catch (Throwable ignored) {
            }
        } else if (s instanceof net.minecraft.client.gui.screens.ConnectScreen cs) {
            try {
                java.lang.reflect.Field f =
                        net.minecraft.client.gui.screens.ConnectScreen.class
                                .getDeclaredField("status");
                f.setAccessible(true);
                o.addProperty("status", String.valueOf(f.get(cs)));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Connect to a server from anywhere (title screen, in-world, disconnect screen). */
    public static JsonObject doConnect(Minecraft mc, String address) {
        try {
            // If a previous attempt is stuck on a ConnectScreen whose handshake died
            // silently, the "already connecting" guard will no-op every retry. Detect
            // that state (ConnectScreen present but no live connection behind it) and
            // clear the screen so this connect actually starts a fresh handshake.
            net.minecraft.client.gui.screens.Screen cur = mc.gui != null ? mc.gui.screen() : null;
            if (cur instanceof net.minecraft.client.gui.screens.ConnectScreen) {
                boolean live = false;
                try {
                    java.lang.reflect.Field f =
                            net.minecraft.client.gui.screens.ConnectScreen.class.getDeclaredField("connection");
                    f.setAccessible(true);
                    Object c = f.get(cur);
                    live = c != null;
                } catch (Throwable ignored) {
                }
                if (!live) {
                    CpvpBotMod.log("CONNECT", "clearing stuck ConnectScreen (no live handshake)");
                    mc.gui.setScreen(null);
                } else {
                    CpvpBotMod.log("CONNECT", "connect already in progress, not restarting");
                    JsonObject o = okBase();
                    o.addProperty("address", address);
                    o.addProperty("alreadyConnecting", true);
                    return o;
                }
            }

            net.minecraft.client.multiplayer.resolver.ServerAddress sa =
                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address);
            net.minecraft.client.multiplayer.ServerData sd = new net.minecraft.client.multiplayer.ServerData(
                    "valkyrie", address, net.minecraft.client.multiplayer.ServerData.Type.OTHER);
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                    null, mc, sa, sd, false,
                    new net.minecraft.client.multiplayer.TransferState(
                            java.util.Map.of(), java.util.Map.of(), false));
            CpvpBotMod.log("CONNECT", "connecting to " + address);
            JsonObject o = okBase();
            o.addProperty("address", address);
            return o;
        } catch (Throwable t) {
            return err("connect_failed:" + t);
        }
    }

    public static String baritonePathing() {
        IBaritone b = baritone();
        if (b == null) return "n/a";
        try {
            return String.valueOf(b.getPathingBehavior().isPathing());
        } catch (Throwable t) {
            return "n/a";
        }
    }

    static IBaritone baritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable t) {
            return null;
        }
    }

    static Entity getEntity(Minecraft mc, int id) {
        if (mc.level == null) return null;
        try {
            return mc.level.getEntity(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int countItem(Player player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    static String itemKey(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    static String stateString(BlockState st) {
        String props = st.getValues()
                .map(v -> v.property().getName() + "=" + v.value())
                .collect(Collectors.joining(","));
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        return props.isEmpty() ? id : id + "[" + props + "]";
    }

    /** Translate an inventory slot index to the player inventory menu slot index. */
    static int menuSlot(int invSlot) {
        if (invSlot <= 8) return invSlot + 36;      // hotbar -> 36..44
        if (invSlot <= 35) return invSlot;          // main   -> 9..35
        if (invSlot <= 39) return invSlot - 31;     // armor  -> 5..8
        if (invSlot == 40) return 45;               // offhand -> 45
        return invSlot;
    }

    // ------------------------------------------------------------------ rotation
    static void lookAt(Minecraft mc, double tx, double ty, double tz) {
        double dx = tx - mc.player.getX();
        double dy = ty - (mc.player.getY() + mc.player.getEyeHeight());
        double dz = tz - mc.player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horiz));
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(pitch);
        // Force the new rotation onto the wire BEFORE any interaction packet,
        // because the server re-raytraces with the last rotation it received.
        try {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    yaw, pitch, mc.player.onGround(), false));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ inventory
    /** Select an item into the main hand (or offhand). Returns an ok/err json. */
    static JsonObject select(Minecraft mc, String itemId, boolean offhand) {
        Inventory inv = mc.player.getInventory();
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));

        if (offhand) {
            if (inv.getItem(Inventory.SLOT_OFFHAND).is(item)) {
                JsonObject o = okBase();
                o.addProperty("slot", Inventory.SLOT_OFFHAND);
                o.addProperty("already", true);
                return o;
            }
            for (int i = 0; i < 36; i++) {
                if (inv.getItem(i).is(item)) {
                    mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                            menuSlot(i), Inventory.SLOT_OFFHAND, ContainerInput.SWAP, mc.player);
                    JsonObject o = okBase();
                    o.addProperty("slot", Inventory.SLOT_OFFHAND);
                    o.addProperty("from", i);
                    return o;
                }
            }
            return err("not_found:" + itemId);
        }

        // main hand: hotbar first
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                inv.setSelectedSlot(i);
                try {
                    mc.getConnection().send(new ServerboundSetCarriedItemPacket(i));
                } catch (Throwable ignored) {
                }
                JsonObject o = okBase();
                o.addProperty("slot", i);
                return o;
            }
        }
        // offhand -> currently selected hotbar slot
        int sel = inv.getSelectedSlot();
        if (inv.getItem(Inventory.SLOT_OFFHAND).is(item)) {
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                    menuSlot(Inventory.SLOT_OFFHAND), sel, ContainerInput.SWAP, mc.player);
            JsonObject o = okBase();
            o.addProperty("slot", sel);
            o.addProperty("from", Inventory.SLOT_OFFHAND);
            return o;
        }
        // main inventory -> currently selected hotbar slot
        for (int i = 9; i <= 35; i++) {
            if (inv.getItem(i).is(item)) {
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                        menuSlot(i), sel, ContainerInput.SWAP, mc.player);
                JsonObject o = okBase();
                o.addProperty("slot", sel);
                o.addProperty("from", i);
                return o;
            }
        }
        return err("not_found:" + itemId);
    }

    // ------------------------------------------------------------------ targets
    static Entity resolveTarget(Minecraft mc, JsonObject c) {
        if (c.has("id") && !c.get("id").isJsonNull()) {
            return getEntity(mc, c.get("id").getAsInt());
        }
        String t = str(c, "target", "nearest_crystal");
        Vec3 p = mc.player.position();
        AABB box = new AABB(p, p).inflate(48, 32, 48);
        if (t.startsWith("player:") || t.startsWith("nearest_player")) {
            String want = t.startsWith("player:") ? t.substring("player:".length()) : null;
            Player best = null;
            double bd = Double.MAX_VALUE;
            for (Player pl : mc.level.getEntitiesOfClass(Player.class, box)) {
                if (pl == mc.player || !pl.isAlive()) continue;
                String name = pl.getGameProfile().name();
                if (want != null && !want.equalsIgnoreCase(name)) continue;
                double d = pl.distanceToSqr(mc.player);
                if (d < bd) {
                    bd = d;
                    best = pl;
                }
            }
            return best;
        }
        // default: nearest end crystal
        EndCrystal best = null;
        double bd = Double.MAX_VALUE;
        for (EndCrystal e : mc.level.getEntitiesOfClass(EndCrystal.class, box)) {
            double d = e.distanceToSqr(mc.player);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ dispatch
    static JsonObject dispatch(JsonObject c) {
        Minecraft mc = Minecraft.getInstance();
        String cmd = str(c, "cmd", "");

        if (cmd.equals("ping")) return okBase();

        // connect works from the title screen (player==null), so handle it early
        if (cmd.equals("connect")) {
            String address = str(c, "address", "valkyriemc.net");
            return doConnect(mc, address);
        }

        if (cmd.equals("chatlog")) {
            JsonObject o = okBase();
            o.addProperty("next", CpvpBotMod.chatSeq);
            o.add("lines", CpvpBotMod.chatSlice(num(c, "since", 0)));
            return o;
        }

        // diagnostic: current screen + hidden connect-failure reason
        if (cmd.equals("screen")) {
            JsonObject o = okBase();
            screenInfo(mc, o);
            return o;
        }

        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return err("not_spawned");
        }

        switch (cmd) {
            // ------------------------------------------------------------ status
            case "status": {
                JsonObject o = okBase();
                Inventory inv = mc.player.getInventory();
                int sel = inv.getSelectedSlot();
                o.addProperty("name", mc.player.getGameProfile().name());
                o.addProperty("tick", CpvpBotMod.ticks);
                JsonArray pos = new JsonArray();
                pos.add(mc.player.getX());
                pos.add(mc.player.getY());
                pos.add(mc.player.getZ());
                o.add("pos", pos);
                o.addProperty("yaw", mc.player.getYRot());
                o.addProperty("pitch", mc.player.getXRot());
                o.addProperty("health", mc.player.getHealth());
                o.addProperty("absorption", mc.player.getAbsorptionAmount());
                o.addProperty("food", mc.player.getFoodData().getFoodLevel());
                o.addProperty("using", mc.player.isUsingItem());
                o.addProperty("onGround", mc.player.onGround());
                o.addProperty("dimension", mc.level.dimension().identifier().toString());
                o.addProperty("selected", sel);
                o.addProperty("selectedItem", itemKey(inv.getItem(sel)));
                o.addProperty("totems", countItem(mc.player, Items.TOTEM_OF_UNDYING));
                o.addProperty("crystals", countItem(mc.player, Items.END_CRYSTAL));
                o.addProperty("obsidian", countItem(mc.player, Items.OBSIDIAN));
                o.addProperty("anchors", countItem(mc.player, Items.RESPAWN_ANCHOR));
                o.addProperty("glowstone", countItem(mc.player, Items.GLOWSTONE));
                o.addProperty("gapples", countItem(mc.player, Items.GOLDEN_APPLE));
                JsonArray hot = new JsonArray();
                for (int i = 0; i < 9; i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.isEmpty()) continue;
                    JsonObject h = new JsonObject();
                    h.addProperty("slot", i);
                    h.addProperty("id", itemKey(s));
                    h.addProperty("count", s.getCount());
                    hot.add(h);
                }
                o.add("hotbar", hot);
                JsonArray full = new JsonArray();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.isEmpty()) continue;
                    JsonObject h = new JsonObject();
                    h.addProperty("slot", i);
                    h.addProperty("id", itemKey(s));
                    h.addProperty("count", s.getCount());
                    full.add(h);
                }
                o.add("inventory", full);
                JsonObject bar = new JsonObject();
                IBaritone b = baritone();
                bar.addProperty("available", b != null);
                if (b != null) {
                    try {
                        Goal g = b.getPathingBehavior().getGoal();
                        bar.addProperty("pathing", b.getPathingBehavior().isPathing());
                        bar.addProperty("hasGoal", g != null);
                        if (g instanceof GoalBlock gb) {
                            JsonArray d = new JsonArray();
                            d.add(gb.x);
                            d.add(gb.y);
                            d.add(gb.z);
                            bar.add("goalBlock", d);
                        }
                    } catch (Throwable t) {
                        bar.addProperty("error", String.valueOf(t));
                    }
                }
                o.add("baritone", bar);
                return o;
            }

            // ------------------------------------------------------------ chat
            case "chat": {
                String text = str(c, "text", "");
                if (text.isEmpty()) return err("empty_text");
                try {
                    if (text.startsWith("/")) {
                        mc.getConnection().sendCommand(text.substring(1));
                    } else {
                        mc.getConnection().sendChat(text);
                    }
                } catch (Throwable t) {
                    return err("chat_failed:" + t);
                }
                CpvpBotMod.log("SENT_CHAT", text);
                JsonObject o = okBase();
                o.addProperty("sent", text);
                return o;
            }

            // ------------------------------------------------------------ select
            case "select": {
                String item = str(c, "item", "");
                boolean offhand = bool(c, "offhand", false);
                JsonObject r = select(mc, item, offhand);
                if (r.get("ok").getAsBoolean()) {
                    CpvpBotMod.log("SELECT", item + (offhand ? " -> offhand" : " -> mainhand")
                            + " slot=" + r.get("slot").getAsInt());
                }
                return r;
            }

            // ------------------------------------------------------------ look
            case "look": {
                lookAt(mc, c.get("x").getAsDouble(), c.get("y").getAsDouble(), c.get("z").getAsDouble());
                JsonObject o = okBase();
                o.addProperty("yaw", mc.player.getYRot());
                o.addProperty("pitch", mc.player.getXRot());
                return o;
            }

            // ------------------------------------------------------------ place
            case "place": {
                BlockPos pos = new BlockPos(num(c, "x", 0), num(c, "y", 0), num(c, "z", 0));
                Direction dir;
                try {
                    dir = Direction.valueOf(str(c, "face", "UP").toUpperCase());
                } catch (IllegalArgumentException e) {
                    return err("bad_face");
                }
                String item = str(c, "item", "");
                if (!item.isEmpty()) {
                    JsonObject sel = select(mc, item, false);
                    if (!sel.get("ok").getAsBoolean()) return sel;
                }
                boolean keepPath = bool(c, "keep_path", false);
                IBaritone bar = baritone();
                if (bar != null && !keepPath) {
                    try {
                        if (bar.getPathingBehavior().isPathing() || bar.getCustomGoalProcess().getGoal() != null) {
                            bar.getPathingBehavior().cancelEverything();
                            CpvpBotMod.log("PATH_STOP", "paused Baritone to perform place");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                Vec3 loc = Vec3.atCenterOf(pos).add(0.5 * dir.getStepX(), 0.5 * dir.getStepY(), 0.5 * dir.getStepZ());
                lookAt(mc, loc.x, loc.y, loc.z);
                BlockHitResult hit = new BlockHitResult(loc, dir, pos, false);
                InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                String held = itemKey(mc.player.getInventory().getItem(mc.player.getInventory().getSelectedSlot()));
                CpvpBotMod.log("PLACE", held + " @ " + pos.toShortString() + " face=" + dir.getSerializedName()
                        + " result=" + r);
                CpvpBotMod.scheduleVerify(pos, held, 8);
                JsonObject o = okBase();
                o.addProperty("result", String.valueOf(r));
                o.addProperty("held", held);
                o.addProperty("pos", pos.toShortString());
                return o;
            }

            // ------------------------------------------------------------ use (eat etc)
            case "use": {
                InteractionHand hand = str(c, "hand", "main").equalsIgnoreCase("off")
                        ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                InteractionResult r = mc.gameMode.useItem(mc.player, hand);
                mc.player.swing(hand);
                String held = itemKey(mc.player.getInventory().getItem(
                        hand == InteractionHand.MAIN_HAND
                                ? mc.player.getInventory().getSelectedSlot()
                                : Inventory.SLOT_OFFHAND));
                CpvpBotMod.log("USE_ITEM", held + " hand=" + hand + " result=" + r);
                JsonObject o = okBase();
                o.addProperty("result", String.valueOf(r));
                o.addProperty("item", held);
                return o;
            }

            // ------------------------------------------------------------ attack
            case "attack": {
                Entity target = resolveTarget(mc, c);
                if (target == null) return err("target_not_found");
                Vec3 tp = target.position().add(0, target.getBbHeight() / 2.0, 0);
                lookAt(mc, tp.x, tp.y, tp.z);
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
                String desc = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType())
                        + " id=" + target.getId()
                        + " name=" + target.getName().getString();
                CpvpBotMod.log("ATTACK", desc);
                if (target instanceof EndCrystal) {
                    CpvpBotMod.trackCrystal(target.getId());
                }
                JsonObject o = okBase();
                o.addProperty("target", desc);
                o.addProperty("distance", Math.sqrt(target.distanceToSqr(mc.player)));
                return o;
            }

            // ------------------------------------------------------------ block query
            case "block": {
                BlockPos pos = new BlockPos(num(c, "x", 0), num(c, "y", 0), num(c, "z", 0));
                BlockState st = mc.level.getBlockState(pos);
                String state = stateString(st);
                CpvpBotMod.log("BLOCK_CHECK", pos.toShortString() + " = " + state);
                JsonObject o = okBase();
                o.addProperty("state", state);
                o.addProperty("id", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
                return o;
            }

            // ------------------------------------------------------------ baritone
            case "goto": {
                IBaritone bar = baritone();
                if (bar == null) return err("baritone_unavailable");
                int x = num(c, "x", 0), y = num(c, "y", 0), z = num(c, "z", 0);
                bar.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
                CpvpBotMod.log("PATH_START", "goto " + x + "," + y + "," + z);
                JsonObject o = okBase();
                o.addProperty("goal", x + "," + y + "," + z);
                return o;
            }

            case "follow": {
                IBaritone bar = baritone();
                if (bar == null) return err("baritone_unavailable");
                String who = str(c, "player", "dummy");
                bar.getFollowProcess().follow(e -> {
                    if (e == mc.player) return false;
                    if (e instanceof Player pl) {
                        return who.equalsIgnoreCase(pl.getGameProfile().name());
                    }
                    return who.equalsIgnoreCase(e.getName().getString());
                });
                CpvpBotMod.log("PATH_START", "follow " + who);
                JsonObject o = okBase();
                o.addProperty("following", who);
                return o;
            }

            case "stop": {
                IBaritone bar = baritone();
                if (bar == null) return err("baritone_unavailable");
                boolean cancelled = bar.getPathingBehavior().cancelEverything();
                CpvpBotMod.log("PATH_STOP", "manual stop (cancelled=" + cancelled + ")");
                return okBase();
            }

            case "bcmd": {
                IBaritone bar = baritone();
                if (bar == null) return err("baritone_unavailable");
                String text = str(c, "command", "");
                boolean ran = bar.getCommandManager().execute(text);
                CpvpBotMod.log("BARITONE_CMD", text + " -> " + ran);
                JsonObject o = okBase();
                o.addProperty("executed", ran);
                return o;
            }

            default:
                return err("unknown_cmd:" + cmd);
        }
    }
}
