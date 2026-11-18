package slowmoact.modid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Actor Studio Lite v0.2 - record takes, replay them as actors, build scenes.
 * See README.md for the full command list.
 */
public class ActorStudioLite implements ModInitializer {

    // ================= data =================
    static class Frame {
        double x, y, z; float yaw, pitch, head;
        boolean sneak, sprint, swing; String pose = "STANDING";
        String[] gear; // main, off, head, chest, legs, feet. null = unchanged since last frame
    }
    static class Event { int tick; String type, value; }
    static class Take {
        String name, skin, tag = "", dim = "overworld";
        int delay = 0, cutStart = 0, cutEnd = 0;
        double scale = 1.0;
        boolean enabled = true, survival = false;
        List<Frame> frames = new ArrayList<>();
        List<Event> events = new ArrayList<>();
    }
    static class Config { boolean sync = true, knockback = true, hurt = true; double speed = 1.0; }

    static class Rec { Take take; ServerPlayer player; String lastGear = ""; }
    static class Play {
        Take take; UUID entity; Level level; boolean spawned;
        int wait; double pos; int start, end, lastApplied = -1; int lastEvent = -1; Vec3 kb = Vec3.ZERO;
    }

    static final Gson GSON = new GsonBuilder().create();
    static Path dir;
    static Config cfg = new Config();
    static boolean paused = false;
    static Map<String, List<String>> scenes = new HashMap<>();
    static final Map<UUID, Rec> recs = new HashMap<>();
    static final List<Play> plays = new ArrayList<>();

    // ================= init =================
    @Override
    public void onInitialize() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("actorstudio");
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        loadMeta();
        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(
            Commands.literal("studio")
                .then(Commands.literal("record").then(Commands.argument("name", StringArgumentType.word())
                    .executes(c -> record(c, cfg.sync))
                    .then(Commands.literal("sync").executes(c -> record(c, true)))
                    .then(Commands.literal("solo").executes(c -> record(c, false)))))
                .then(Commands.literal("stop").executes(this::stop))
                .then(Commands.literal("play").then(Commands.argument("name", StringArgumentType.word()).executes(this::play)))
                .then(Commands.literal("playall").executes(c -> { int n = playAll(srv(c), null); msg(c, "Playing " + n + " actor(s)"); return n; }))
                .then(Commands.literal("pause").executes(c -> { paused = !paused; msg(c, paused ? "Paused" : "Resumed"); return 1; }))
                .then(Commands.literal("speed").then(Commands.argument("x", DoubleArgumentType.doubleArg(0.01, 4))
                    .executes(c -> { cfg.speed = DoubleArgumentType.getDouble(c, "x"); saveMeta(); msg(c, "Playback speed " + cfg.speed + "x"); return 1; })))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("info").then(Commands.argument("name", StringArgumentType.word()).executes(this::info)))
                .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).executes(this::delete)))
                .then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("new", StringArgumentType.word()).executes(this::rename))))
                .then(Commands.literal("undo").then(Commands.argument("name", StringArgumentType.word()).executes(c -> swap(c, "Undo"))))
                .then(Commands.literal("redo").then(Commands.argument("name", StringArgumentType.word()).executes(c -> swap(c, "Redo"))))
                .then(Commands.literal("set").then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("key", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.greedyString()).executes(this::set)))))
                .then(Commands.literal("at").then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.literal("clear").executes(this::eventClear))
                    .then(Commands.argument("tick", IntegerArgumentType.integer(0))
                        .then(Commands.literal("sound").then(Commands.argument("id", StringArgumentType.greedyString())
                            .executes(c -> eventAdd(c, "sound"))))
                        .then(Commands.literal("command").then(Commands.argument("id", StringArgumentType.greedyString())
                            .executes(c -> eventAdd(c, "command")))))))
                .then(Commands.literal("scene")
                    .then(Commands.literal("add").then(Commands.argument("scene", StringArgumentType.word())
                        .then(Commands.argument("take", StringArgumentType.word()).executes(c -> sceneEdit(c, true)))))
                    .then(Commands.literal("remove").then(Commands.argument("scene", StringArgumentType.word())
                        .then(Commands.argument("take", StringArgumentType.word()).executes(c -> sceneEdit(c, false)))))
                    .then(Commands.literal("play").then(Commands.argument("scene", StringArgumentType.word()).executes(this::scenePlay)))
                    .then(Commands.literal("list").executes(this::sceneList)))
                .then(Commands.literal("config").then(Commands.argument("key", StringArgumentType.word())
                    .then(Commands.argument("value", BoolArgumentType.bool()).executes(this::config))))
        ));
    }

    // ================= helpers =================
    static MinecraftServer srv(CommandContext<CommandSourceStack> c) { return c.getSource().getServer(); }
    static void msg(CommandContext<CommandSourceStack> c, String m) {
        c.getSource().sendSuccess(() -> Component.literal("[Actor Studio] " + m), false);
    }
    static boolean valid(String n) { return n.matches("[A-Za-z0-9_-]{1,32}"); }
    static String clean(String s) { return s == null ? "" : s.replaceAll("[^A-Za-z0-9_ ]", ""); }
    static String dimName(Level l) { return l == Level.NETHER ? "nether" : l == Level.END ? "end" : "overworld"; }
    static Level dimKey(String d) { return d.equals("nether") ? Level.NETHER : d.equals("end") ? Level.END : Level.OVERWORLD; }
    static String dimId(String d) { return d.equals("nether") ? "minecraft:the_nether" : d.equals("end") ? "minecraft:the_end" : "minecraft:overworld"; }
    static void run(MinecraftServer s, String cmd) {
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd);
    }
    static String id(ItemStack st) { return BuiltInRegistries.ITEM.getKey(st.getItem()).toString(); }

    // ================= storage =================
    static Path file(String n) { return dir.resolve(n + ".json"); }
    static Path bak(String n) { return dir.resolve(n + ".bak"); }
    static void save(Take t) { try { Files.writeString(file(t.name), GSON.toJson(t)); } catch (Exception ignored) {} }
    static Take load(String n) {
        if (!valid(n)) return null;
        try { return GSON.fromJson(Files.readString(file(n)), Take.class); } catch (Exception e) { return null; }
    }
    static void saveMeta() {
        try {
            Files.writeString(dir.resolve("_settings.cfg"), GSON.toJson(cfg));
            Files.writeString(dir.resolve("_scenes.cfg"), GSON.toJson(scenes));
        } catch (Exception ignored) {}
    }
    static void loadMeta() {
        try { Config c = GSON.fromJson(Files.readString(dir.resolve("_settings.cfg")), Config.class); if (c != null) cfg = c; } catch (Exception ignored) {}
        try {
            Type t = new TypeToken<Map<String, List<String>>>() {}.getType();
            Map<String, List<String>> m = GSON.fromJson(Files.readString(dir.resolve("_scenes.cfg")), t);
            if (m != null) scenes = m;
        } catch (Exception ignored) {}
    }
    static List<String> allTakes() {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        } catch (Exception e) { return List.of(); }
    }

    // ================= commands =================
    int record(CommandContext<CommandSourceStack> c, boolean sync) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        if (!valid(name)) { msg(c, "Names: letters, numbers, _ and - only."); return 0; }
        Rec r = new Rec(); r.player = p; r.take = new Take(); r.take.name = name;
        r.take.dim = dimName(p.level());
        Take old = load(name);
        if (old != null) { // re-record keeps the actor's settings and timeline events
            r.take.skin = old.skin; r.take.tag = old.tag; r.take.delay = old.delay; r.take.scale = old.scale;
            r.take.enabled = old.enabled; r.take.survival = old.survival; r.take.events = old.events;
        } else r.take.skin = p.getGameProfile().name();
        recs.put(p.getUUID(), r);
        if (sync) playAll(srv(c), name);
        msg(c, "Recording '" + name + "'" + (sync ? " with the scene playing" : "") + ". /studio stop to finish.");
        return 1;
    }

    int stop(CommandContext<CommandSourceStack> c) {
        for (Rec r : recs.values()) {
            try { if (Files.exists(file(r.take.name))) Files.copy(file(r.take.name), bak(r.take.name), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception ignored) {}
            save(r.take);
            msg(c, "Saved '" + r.take.name + "' (" + r.take.frames.size() + " ticks)");
        }
        recs.clear();
        stopPlays(srv(c));
        return 1;
    }

    int play(CommandContext<CommandSourceStack> c) {
        Take t = load(StringArgumentType.getString(c, "name"));
        if (t == null || t.frames.isEmpty()) { msg(c, "No such take."); return 0; }
        startPlay(t); msg(c, "Playing '" + t.name + "'"); return 1;
    }

    int list(CommandContext<CommandSourceStack> c) {
        List<String> n = allTakes();
        msg(c, n.isEmpty() ? "No takes yet." : "Takes: " + String.join(", ", n)); return 1;
    }

    int info(CommandContext<CommandSourceStack> c) {
        Take t = load(StringArgumentType.getString(c, "name"));
        if (t == null) { msg(c, "No such take."); return 0; }
        msg(c, t.name + ": " + t.frames.size() + " ticks, skin=" + t.skin + ", tag='" + t.tag + "', delay=" + t.delay
            + ", cut=" + t.cutStart + "/" + t.cutEnd + ", scale=" + t.scale + ", enabled=" + t.enabled
            + ", survival=" + t.survival + ", dim=" + t.dim + ", events=" + t.events.size());
        return 1;
    }

    int delete(CommandContext<CommandSourceStack> c) {
        String n = StringArgumentType.getString(c, "name");
        if (!valid(n)) return 0;
        try { Files.deleteIfExists(file(n)); Files.deleteIfExists(bak(n)); msg(c, "Deleted."); } catch (Exception e) { msg(c, "Failed."); }
        return 1;
    }

    int rename(CommandContext<CommandSourceStack> c) {
        String a = StringArgumentType.getString(c, "name"), b = StringArgumentType.getString(c, "new");
        Take t = load(a);
        if (t == null || !valid(b) || Files.exists(file(b))) { msg(c, "Can't rename."); return 0; }
        try { Files.delete(file(a)); Files.deleteIfExists(bak(a)); } catch (Exception ignored) {}
        t.name = b; save(t); msg(c, "Renamed to " + b); return 1;
    }

    int swap(CommandContext<CommandSourceStack> c, String label) { // undo/redo toggle between current and previous take
        String n = StringArgumentType.getString(c, "name");
        if (!valid(n) || !Files.exists(bak(n))) { msg(c, "Nothing to " + label.toLowerCase() + "."); return 0; }
        try {
            Path tmp = dir.resolve(n + ".tmp");
            Files.move(file(n), tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(bak(n), file(n), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, bak(n), StandardCopyOption.REPLACE_EXISTING);
            msg(c, label + " done for '" + n + "'");
        } catch (Exception e) { msg(c, "Failed."); }
        return 1;
    }

    int set(CommandContext<CommandSourceStack> c) {
        Take t = load(StringArgumentType.getString(c, "name"));
        String key = StringArgumentType.getString(c, "key").toLowerCase(Locale.ROOT);
        String v = StringArgumentType.getString(c, "value").trim();
        if (t == null) { msg(c, "No such take."); return 0; }
        try {
            switch (key) {
                case "tag" -> t.tag = clean(v);
                case "skin" -> t.skin = v.matches("[A-Za-z0-9_]{1,16}") ? v : "Steve";
                case "delay" -> t.delay = Math.max(0, Integer.parseInt(v));
                case "cutstart" -> t.cutStart = Math.max(0, Integer.parseInt(v));
                case "cutend" -> t.cutEnd = Math.max(0, Integer.parseInt(v));
                case "scale" -> t.scale = Mth.clamp(Double.parseDouble(v), 0.0625, 16);
                case "enabled" -> t.enabled = Boolean.parseBoolean(v);
                case "survival" -> t.survival = Boolean.parseBoolean(v);
                default -> { msg(c, "Keys: tag, skin, delay, cutstart, cutend, scale, enabled, survival"); return 0; }
            }
        } catch (NumberFormatException e) { msg(c, "Bad number."); return 0; }
        save(t); msg(c, key + " updated on '" + t.name + "'"); return 1;
    }

    int eventAdd(CommandContext<CommandSourceStack> c, String type) {
        Take t = load(StringArgumentType.getString(c, "name"));
        if (t == null) { msg(c, "No such take."); return 0; }
        Event e = new Event(); e.tick = IntegerArgumentType.getInteger(c, "tick"); e.type = type;
        e.value = StringArgumentType.getString(c, "id").trim();
        if (type.equals("command") && e.value.startsWith("/")) e.value = e.value.substring(1);
        t.events.add(e); save(t); msg(c, "Added " + type + " at tick " + e.tick); return 1;
    }

    int eventClear(CommandContext<CommandSourceStack> c) {
        Take t = load(StringArgumentType.getString(c, "name"));
        if (t == null) { msg(c, "No such take."); return 0; }
        t.events.clear(); save(t); msg(c, "Timeline cleared."); return 1;
    }

    int sceneEdit(CommandContext<CommandSourceStack> c, boolean add) {
        String s = StringArgumentType.getString(c, "scene"), t = StringArgumentType.getString(c, "take");
        if (!valid(s) || !valid(t)) return 0;
        List<String> l = scenes.computeIfAbsent(s, k -> new ArrayList<>());
        if (add && !l.contains(t)) l.add(t); else if (!add) l.remove(t);
        saveMeta(); msg(c, "Scene '" + s + "': " + l); return 1;
    }

    int scenePlay(CommandContext<CommandSourceStack> c) {
        List<String> l = scenes.get(StringArgumentType.getString(c, "scene"));
        if (l == null) { msg(c, "No such scene."); return 0; }
        int n = 0;
        for (String nm : l) { Take t = load(nm); if (t != null && t.enabled && !t.frames.isEmpty()) { startPlay(t); n++; } }
        msg(c, "Playing scene with " + n + " actor(s)"); return n;
    }

    int sceneList(CommandContext<CommandSourceStack> c) { msg(c, scenes.isEmpty() ? "No scenes." : scenes.toString()); return 1; }

    int config(CommandContext<CommandSourceStack> c) {
        String k = StringArgumentType.getString(c, "key").toLowerCase(Locale.ROOT);
        boolean v = BoolArgumentType.getBool(c, "value");
        switch (k) {
            case "sync" -> cfg.sync = v;            // play the scene while recording
            case "knockback" -> cfg.knockback = v;  // actors get pushed when hit
            case "hurt" -> cfg.hurt = v;            // actors marked survival can be hurt
            default -> { msg(c, "Keys: sync, knockback, hurt"); return 0; }
        }
        saveMeta(); msg(c, k + " = " + v); return 1;
    }

    // ================= playback =================
    static int playAll(MinecraftServer s, String except) {
        int n = 0;
        for (String nm : allTakes()) {
            if (nm.equals(except)) continue;
            Take t = load(nm);
            if (t != null && t.enabled && !t.frames.isEmpty()) { startPlay(t); n++; }
        }
        return n;
    }

    static void startPlay(Take t) {
        Play p = new Play(); p.take = t;
        p.level = dimKey(t.dim); p.wait = t.delay;
        p.start = Mth.clamp(t.cutStart, 0, t.frames.size() - 1);
        p.end = t.cutEnd > 0 ? Math.min(t.cutEnd, t.frames.size()) : t.frames.size();
        if (p.end <= p.start) p.end = t.frames.size();
        p.pos = p.start; p.lastApplied = p.start - 1; p.lastEvent = p.start - 1;
        plays.add(p);
    }

    static void spawn(MinecraftServer s, Play p) {
        Take t = p.take; Frame f = t.frames.get(p.start);
        p.entity = UUID.randomUUID();
        int[] a = UUIDUtil.uuidToIntArray(p.entity);
        boolean named = !t.tag.isEmpty();
        run(s, String.format(Locale.ROOT,
            "execute in %s run summon minecraft:mannequin %f %f %f {UUID:[I;%d,%d,%d,%d],NoAI:1b,Invulnerable:%db,NoGravity:1b,"
            + "Silent:1b,immovable:1b,hide_description:1b,CustomName:\"%s\",CustomNameVisible:%db,profile:\"%s\"}",
            dimId(t.dim), f.x, f.y, f.z, a[0], a[1], a[2], a[3],
            (t.survival && cfg.hurt) ? 0 : 1, named ? t.tag : t.name, named ? 1 : 0,
            (t.skin == null || t.skin.isEmpty()) ? "Steve" : t.skin));
        if (t.scale != 1.0) run(s, String.format(Locale.ROOT, "attribute %s minecraft:scale base set %f", p.entity, t.scale));
        p.spawned = true;
    }

    static void stopPlays(MinecraftServer s) {
        for (Play p : plays) {
            if (p.entity == null) continue;
            ServerLevel l = s.getLevel(p.level);
            Entity e = l == null ? null : l.getEntity(p.entity);
            if (e != null) e.discard();
        }
        plays.clear();
    }

    // ================= tick =================
    void tick(MinecraftServer s) {
        for (Rec r : recs.values()) recordTick(r);
        if (!paused) playTick(s);
    }

    void recordTick(Rec r) {
        ServerPlayer p = r.player; Frame f = new Frame();
        f.x = p.getX(); f.y = p.getY(); f.z = p.getZ();
        f.yaw = p.getYRot(); f.pitch = p.getXRot(); f.head = p.getYHeadRot();
        f.sneak = p.isShiftKeyDown(); f.sprint = p.isSprinting();
        f.swing = p.swinging && p.swingTime == 0;
        f.pose = p.getPose().name();
        String[] g = { id(p.getMainHandItem()), id(p.getOffhandItem()), id(p.getItemBySlot(EquipmentSlot.HEAD)),
            id(p.getItemBySlot(EquipmentSlot.CHEST)), id(p.getItemBySlot(EquipmentSlot.LEGS)), id(p.getItemBySlot(EquipmentSlot.FEET)) };
        String key = String.join("|", g);
        if (!key.equals(r.lastGear)) { f.gear = g; r.lastGear = key; }
        r.take.frames.add(f);
    }

    static final String[] SLOTS = { "weapon.mainhand", "weapon.offhand", "armor.head", "armor.chest", "armor.legs", "armor.feet" };

    void playTick(MinecraftServer s) {
        Iterator<Play> it = plays.iterator();
        while (it.hasNext()) {
            Play p = it.next();
            if (p.wait > 0) { p.wait--; continue; }
            if (!p.spawned) spawn(s, p);
            ServerLevel lvl = s.getLevel(p.level);
            Entity e = lvl == null ? null : lvl.getEntity(p.entity);
            if (e == null) { it.remove(); continue; } // killed or removed
            if (!e.isAlive()) continue;               // let the death animation play

            List<Frame> fr = p.take.frames;
            int i = (int) p.pos;
            if (i >= p.end - 1) { e.discard(); it.remove(); continue; }
            double frac = p.pos - i;
            Frame a = fr.get(i), b = fr.get(i + 1);

            // knockback addon: hits push the actor, push fades out
            if (cfg.knockback && p.take.survival) {
                Vec3 dm = e.getDeltaMovement();
                p.kb = p.kb.add(dm.x, 0, dm.z).scale(0.8);
            } else p.kb = Vec3.ZERO;
            e.setDeltaMovement(Vec3.ZERO);

            e.snapTo(Mth.lerp(frac, a.x, b.x) + p.kb.x, Mth.lerp(frac, a.y, b.y), Mth.lerp(frac, a.z, b.z) + p.kb.z,
                Mth.rotLerp((float) frac, a.yaw, b.yaw), Mth.lerp((float) frac, a.pitch, b.pitch));
            e.setYHeadRot(Mth.rotLerp((float) frac, a.head, b.head));

            boolean swung = false;
            for (int k = p.lastApplied + 1; k <= i; k++) {
                Frame fk = fr.get(k);
                swung |= fk.swing;
                if (fk.gear != null) for (int sl = 0; sl < SLOTS.length; sl++)
                    run(s, "item replace entity " + p.entity + " " + SLOTS[sl] + " with " + fk.gear[sl]);
            }
            p.lastApplied = i;

            if (e instanceof LivingEntity le) {
                le.setYBodyRot(a.yaw);
                le.setShiftKeyDown(a.sneak);
                le.setSprinting(a.sprint);
                try { le.setPose(Pose.valueOf(a.pose)); } catch (Exception ignored) {}
                if (swung) le.swing(InteractionHand.MAIN_HAND);
            }

            for (Event ev : p.take.events) {
                if (ev.tick > p.lastEvent && ev.tick <= i) {
                    if (ev.type.equals("sound")) run(s, "execute at " + p.entity + " run playsound " + ev.value + " master @a ~ ~ ~ 1 1");
                    else run(s, "execute at " + p.entity + " run " + ev.value);
                }
            }
            p.lastEvent = i;
            p.pos += cfg.speed;
        }
    }
}
