package io.github.piwugu.snowleopard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import snowleopard.core.SnowLeopard;
import snowleopard.core.json.Json;
import snowleopard.core.result.Result;
import snowleopard.core.wake.WakeStore;

import io.github.piwugu.snowleopard.tools.avatar.AvatarStore;
import io.github.piwugu.snowleopard.tools.chat.ChatMention;

/**
 * 载体唤醒源（M13-2/3/4，2026-10-24 01:20 (UTC+8)，分析 §三）：
 * **互动**（右键/被打）、**定时**（真实时间）、**提及**（只名称）。
 *
 * <p><b>外部触发的 {@code from} 是伪 ID</b>：{@code ext-player-<名>} / {@code ext-clock} /
 * {@code ext-mention-<名>}。**核心的 {@code Ids.Caller} 不允许冒号**（只收 {@code [A-Za-z0-9_-]}），
 * 所以分析稿里的 {@code player:<名>} 冒号写法作废。绝不能借用真实模型 ID 当发起方——
 * 否则自唤起/成环/黑名单判定全部失真。
 *
 * <p><b>被取消的聊天不算提及</b>（与 M12 同一优先级 LOWEST + 收取消）：玩家自己都没说出去的话，
 * 不能拿来唤醒谁。
 *
 * <p><b>唤醒失败必须补提示</b>（裁决：提示 ≠ 唤醒）：目标在运行/配额满/被拉黑时，
 * "有人叫你但没叫起来"这件事不能凭空消失——给该模型 {@code pendingMentions +1}。
 */
public final class AvatarWake {

    /** 互动/提及共用的冷却（毫秒）——玩家连点右键等于每秒一次唤醒，不加会被刷。 */
    private static final long DEFAULT_COOLDOWN_MS = 10_000L;

    private static AvatarStore store;
    private static boolean onDamage = true;
    private static long cooldownMs = DEFAULT_COOLDOWN_MS;
    private static final Map<String, Long> LAST_WAKE = new ConcurrentHashMap<>();
    /** 冷却回执每窗口只发一次（连点右键不能刷屏，但第一次必须让人知道为什么没反应）。 */
    private static final Map<String, Long> LAST_COOLDOWN_NOTE = new ConcurrentHashMap<>();
    private static int tickCounter;

    private AvatarWake() {
    }

    public static boolean ready() {
        return store != null;
    }

    static AvatarStore store() {
        return store;
    }

    /** 模型的主载体（身份解析用；null = 没载体）。包级可见——身份解析只此一处对外。 */
    static AvatarStore.Entry primaryName(String model) {
        return ready() ? store.primaryOf(model) : null;
    }

    /** 装配（ChatRuntime.init 之后）。 */
    public static void init(Path gameDir, SnowLeopard core) {
        if (core == null) {
            SnowLeopardMod.LOG.warn("[雪豹框架][载体] 核心未装配——唤醒源不可用");
            return;
        }
        store = new AvatarStore(core.ids().shared().resolve("avatars.json"));
        loadConfig(gameDir);
        SnowLeopardMod.LOG.info("[雪豹框架][载体] 登记表 {} · 被打唤醒 {} · 冷却 {}ms",
                store.file(), onDamage ? "开" : "关", cooldownMs);
    }

    private static void loadConfig(Path gameDir) {
        onDamage = true;
        cooldownMs = DEFAULT_COOLDOWN_MS;
        if (gameDir == null) {
            return;
        }
        Path f = gameDir.resolve("config").resolve("snowleopard-wake.json");
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (m.get("on_damage") instanceof Boolean b) {
                onDamage = b;
            }
            if (m.get("cooldown_ms") instanceof Number n && n.longValue() >= 0) {
                cooldownMs = n.longValue();
            }
        } catch (Exception e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][载体] 配置读不懂，用默认值: {} ({})", f, e.getMessage());
        }
    }

    // ------------------------------------------------------------- ① 互动唤醒

    /** 右键载体 → 唤醒（主载体才算）。 */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!ready() || event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getTarget() instanceof AvatarEntity avatar)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack hand = event.getItemStack();
        String item = hand.isEmpty() ? "空手" : hand.getItem().toString().substring(hand.getItem().toString().lastIndexOf('.') + 1);
        String handName = event.getHand() == InteractionHand.OFF_HAND ? "副手" : "主手";
        wakeFromPlayer("ext-player-" + safe(player.getGameProfile().getName()), avatar,
                "interact:right_click",
                "玩家 " + player.getGameProfile().getName() + " 用" + handName
                        + "右键了你（" + item + "，位置 " + pos(player) + "）",
                player);
    }

    /** 被打 → 唤醒（裁决 1：**可配置**，默认开；关掉后这里直接跳过）。 */
    public static void onAttacked(LivingIncomingDamageEvent event) {
        if (!ready() || !onDamage) {
            return;
        }
        if (!(event.getEntity() instanceof AvatarEntity avatar)) {
            return;
        }
        var source = event.getContainer().getSource();
        Entity attackerEntity = source.getEntity();
        String who = attackerEntity == null ? source.getMsgId()
                : (attackerEntity instanceof ServerPlayer sp ? sp.getGameProfile().getName()
                        : attackerEntity.getType().toShortString());
        wakeFromPlayer("ext-player-" + safe(who), avatar, "interact:attacked",
                "你被攻击了：来源 " + who + "（伤害约 "
                        + String.format(Locale.ROOT, "%.1f", event.getContainer().getNewDamage()) + "）",
                attackerEntity instanceof ServerPlayer sp ? sp : null);
    }

    /** 清冷却（自测/运维用）：跨测试段时冷却会把下一段的唤醒吞掉。 */
    public static void resetCooldowns() {
        LAST_WAKE.clear();
    }

    /** 载体死亡 → 解绑（**卸载不算**：那只是区块走了）。 */
    public static void onDeath(LivingDeathEvent event) {
        if (!ready() || !(event.getEntity() instanceof AvatarEntity avatar)) {
            return;
        }
        int n = store.unbindByModel(avatar.boundModel().isEmpty() ? "\u0000none\u0000" : avatar.boundModel());
        if (n > 0) {
            SnowLeopardMod.LOG.info("[雪豹框架][载体] 载体死亡，解绑 {}（{}）",
                    avatar.boundModel(), avatar.getStringUUID());
        }
    }

    private static void wakeFromPlayer(String from, AvatarEntity avatar, String reason,
                                       String prompt, ServerPlayer feedbackTo) {
        AvatarStore.Entry entry = entryOf(avatar);
        if (entry == null) {
            return;   // 没登记的载体：只是只猫，不接唤醒
        }
        String name = displayName(entry);
        long now = System.currentTimeMillis();
        Long last = LAST_WAKE.get(avatar.getStringUUID());
        if (last != null && now - last < cooldownMs) {
            // 冷却：第一次拒绝必须让人看见（否则玩家以为功能坏了），同一窗口内不重复刷
            long window = last / Math.max(1, cooldownMs);
            if (feedbackTo != null
                    && !Long.valueOf(window).equals(LAST_COOLDOWN_NOTE.get(avatar.getStringUUID()))) {
                long remainSec = (cooldownMs - (now - last) + 999) / 1000;
                ChatPush.pushTo(feedbackTo, name + "：冷却中，约 " + remainSec + " 秒后再试");
                LAST_COOLDOWN_NOTE.put(avatar.getStringUUID(), window);
            }
            return;
        }
        LAST_COOLDOWN_NOTE.remove(avatar.getStringUUID());
        LAST_WAKE.put(avatar.getStringUUID(), now);
        requestWake(from, entry.modelId(), prompt, reason, name, feedbackTo);
    }

    // ------------------------------------------------------------- ② 定时唤醒

    /** 每 20 tick（1 秒）扫一次到期项——**绝不能挂实体 tick**（区块卸载后实体不 tick，定时会静默失效）。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ready() || ++tickCounter % 20 != 0) {
            return;
        }
        runScheduler(event.getServer());
    }

    /** 调度器本体（自测直接调，绕开 tick 计数）。 */
    public static void runScheduler(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (AvatarStore.Entry entry : store.due(now)) {
            store.advance(entry.uuid(), now);   // 先推进：**跳过也不堆积、不补发**（裁决 3）
            Entity entity = findEntity(server, entry.uuid());
            if (entity == null) {
                SnowLeopardMod.LOG.info("[雪豹框架][唤醒] 定时跳过 {}（实体未加载，nextFireAt 已正常推进）",
                        entry.modelId());
                continue;
            }
            requestWake("ext-clock", entry.modelId(),
                    "定时唤醒（每 " + entry.intervalSec() + " 秒）", "clock",
                    displayName(entry), null);
        }
    }

    // ------------------------------------------------------------- ③ 提及唤醒

    /**
     * 聊天提及 → 只**名称**命中才唤醒（编号只提示，M12 的通道）。
     *
     * <p>由 {@code ChatRuntime.record} 在记录后调用；同一条消息提多次也只按模型去重（hits 已去重）。
     * **触发人每条分支都有聊天栏回执**——玩家点名之后"没有任何反应"看起来就像功能坏了。
     */
    public static void onMentions(List<String> mentioned, String text, String playerName) {
        if (!ready() || mentioned == null) {
            return;
        }
        String safeText = text == null ? "" : text;
        ServerPlayer speaker = playerName == null ? null
                : server() == null ? null
                : server().getPlayerList().getPlayerByName(playerName);
        for (String model : mentioned) {
            String shown = ChatRuntime.displayName(model);
            if (!ChatMention.mentionsName(safeText, shown)) {
                // 只按编号命中：不唤醒（提示已由 M12 记）——但要让玩家知道"看见了"
                ChatPush.pushTo(speaker, shown + "：已记录提示（编号提及不唤醒，名称才会）");
                continue;
            }
            AvatarStore.Entry primary = store.primaryOf(model);
            if (primary == null) {
                ChatPush.pushTo(speaker, shown + "：被提及，但没有主载体，未唤醒");
                continue;   // 没载体：只提示，不唤醒
            }
            Entity carrier = findEntity(server(), primary.uuid());
            if (!(carrier instanceof AvatarEntity avatar)) {
                ChatPush.pushTo(speaker, shown + "：被提及，但载体未加载，本轮只提示");
                continue;   // 主载体未加载：本轮只提示（M12 已记），下次点名还会尝试
            }
            wakeFromPlayer("ext-mention-" + safe(playerName), avatar, "mention:name",
                    "玩家 " + playerName + " 在聊天里点名了你：" + safeText, speaker);
        }
    }

    // ------------------------------------------------------------- 工具执行（组 avatar）

    public static Result dispatch(String modelId, String tool, Map<String, Object> params) {
        if (!ready()) {
            return Result.error("载体组未装配（核心未启动或初始化失败）");
        }
        String model = modelId == null ? "" : modelId.trim();
        if (model.isEmpty()) {
            return Result.error("载体工具需要调用方模型编号（core 注入，不可伪造）");
        }
        return switch (tool) {
            case "avatar_bind" -> bind(model);
            case "avatar_unbind" -> {
                int removed = store.unbindByModel(model);
                yield removed == 0
                        ? Result.warn(Map.of("removed", 0), "没有已登记的载体")
                        : Result.ok(Map.of("removed", removed));
            }
            case "avatar_schedule" -> schedule(model, params);
            case "avatar_status" -> status(model);
            default -> Result.error("未知的载体工具: " + tool
                    + "（可用: avatar_bind / avatar_unbind / avatar_schedule / avatar_status）");
        };
    }

    private static Result bind(String model) {
        String ref = EntityToolsRuntime.boundEntityRef(model);
        if (ref == null || ref.isBlank()) {
            return Result.error("当前没有绑定的实体——先 sense.open + entity.pick，"
                    + "并且绑定的必须是 snowleopard:avatar");
        }
        Entity entity = findEntity(server(), ref);
        if (!(entity instanceof AvatarEntity avatar)) {
            return Result.error("绑定的实体不是载体（snowleopard:avatar）——"
                    + "用 /summon snowleopard:avatar 生成一只，再 pick 它");
        }
        String name = avatar.hasCustomName() ? avatar.getCustomName().getString() : "";
        try {
            AvatarStore.Entry entry = store.bind(avatar.getStringUUID(), model, name);
            avatar.setBoundModel(model);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("uuid", entry.uuid());
            out.put("name", entry.name().isEmpty() ? model : entry.name());
            out.put("primary", entry.primary());
            return Result.ok(out);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());   // 重名 → 拒绝（裁决 4）
        }
    }

    private static Result schedule(String model, Map<String, Object> params) {
        Object v = params == null ? null : params.get("interval_sec");
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("avatar_schedule 需要 interval_sec（秒，>=60）");
        }
        try {
            AvatarStore.Entry e = store.setSchedule(model, n.longValue(), System.currentTimeMillis());
            return Result.ok(Map.of("intervalSec", e.intervalSec(), "nextFireAt", e.nextFireAt()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    private static Result status(String model) {
        List<Map<String, Object>> out = new ArrayList<>();
        MinecraftServer server = server();
        for (AvatarStore.Entry e : store.byModel(model)) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("uuid", e.uuid());
            m.put("name", e.name().isEmpty() ? model : e.name());
            m.put("primary", e.primary());
            m.put("intervalSec", e.intervalSec());
            m.put("nextFireAt", e.nextFireAt());
            m.put("loaded", findEntity(server, e.uuid()) != null);
            out.add(m);
        }
        return Result.ok(Map.of("avatars", out));
    }

    // ------------------------------------------------------------- 内部

    private static AvatarStore.Entry entryOf(AvatarEntity avatar) {
        AvatarStore.Entry e = store.byUuid(avatar.getStringUUID());
        if (e == null || !e.primary()) {
            return null;   // 非主载体：只是外观/位置存在，不接唤醒（裁决 5）
        }
        return e;
    }

    private static void requestWake(String from, String model, String prompt, String reason,
                                    String carrierName, ServerPlayer feedbackTo) {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null) {
            return;
        }
        String key = reason + "-" + (System.currentTimeMillis() / Math.max(1, cooldownMs));
        Result r = core.wake().request(new WakeStore.Chain(List.of(from)), from, model,
                sanitize(prompt), reason, key);
        if (r instanceof Result.Error err) {
            // 唤醒失败 → 补提示（裁决：提示 ≠ 唤醒；"有人叫你但没叫起来"不能消失）
            SnowLeopardMod.LOG.warn("[雪豹框架][唤醒] {} → {} 失败：{}（已补一条提示）",
                    reason, model, err.desc());
            ChatRuntime.addPendingMention(model);
            if (feedbackTo != null) {
                ChatPush.pushTo(feedbackTo, carrierName + "：唤醒被拒（"
                        + brief(err.desc()) + "）· 已转为提示");
            }
        } else {
            SnowLeopardMod.LOG.info("[雪豹框架][唤醒] {} → {} 已登记为 pending", reason, model);
            if (feedbackTo != null) {
                ChatPush.pushTo(feedbackTo, carrierName + "：唤醒已登记（" + reason
                        + " → " + model + "）· 等待执行端拉取");
            }
        }
    }

    /** 回执里的原因只留一行能读懂的（Error 描述可能很长）。 */
    private static String brief(String desc) {
        String s = desc == null ? "" : desc.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** 载体对外显示名（与身份单点一致：有名字用名字，没名字用模型编号）。 */
    private static String displayName(AvatarStore.Entry entry) {
        return entry.name().isEmpty() ? entry.modelId() : entry.name();
    }

    /** 唤醒 prompt 里也做单行化（它会被原样放进模型上下文）。 */
    private static String sanitize(String s) {
        return io.github.piwugu.snowleopard.tools.chat.ChatBlock.sanitize(s);
    }

    private static MinecraftServer server() {
        return net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
    }

    private static Entity findEntity(MinecraftServer server, String uuid) {
        if (server == null || uuid == null || uuid.isBlank()) {
            return null;
        }
        try {
            UUID id = UUID.fromString(uuid);
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(id);
                if (e != null) {
                    return e;
                }
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    /** 伪 ID 只允许 {@code [A-Za-z0-9_-]}（核心 Ids.Caller 的硬规则），其余字符换下划线。 */
    private static String safe(String s) {
        String out = (s == null ? "unknown" : s).replaceAll("[^A-Za-z0-9_-]", "_");
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    private static String pos(ServerPlayer p) {
        return Math.round(p.getX()) + "," + Math.round(p.getY()) + "," + Math.round(p.getZ());
    }
}
