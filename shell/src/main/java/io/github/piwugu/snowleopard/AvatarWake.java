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

import io.github.piwugu.snowleopard.host.AiHost;
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
    /** 玩家侧回执开关（裁决 2026-10-24 02:10：反馈属系统信息，**默认关**；要看得自己开）。 */
    private static boolean feedback = false;
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

    /**
     * 该模型有没有主载体（"有没有身体可以动"的查询）。
     *
     * <p>对外公开的是**有没有**这个事实，不是登记项本身——身份解析（叫它什么名字）仍只有
     * {@link #primaryName} 一处；跨包问"我能不能移动/破坏"不需要拿到 Entry。
     */
    /**
     * 该模型**此刻在世界里真的有载体吗**（扫实体，不看登记表）。
     *
     * <p>2026-10-28 用户要求："载体信息每次自动更新，不要写死在文件里"。
     * 原来对外只暴露"登记表里有没有这条"，于是出现两种自相矛盾：
     * ① 登记在、实体没了 → 界面显示"有载体"，一点传送却报"没有载体"；
     * ② 实体在、登记丢了 → 界面显示"没有"，实际能操作。
     *
     * <p>**存在性只能来自世界里真实存在的实体**；`avatars.json` 是排程与地址缓存，不是事实来源。
     * 注意：这里只看得见**已加载**的实体——远处未加载的载体扫不到，所以"写操作"（生成新载体）
     * 还要额外查一次登记表（见 {@link #hasCarrierRecord}），避免一个 ID 生成出两只载体。
     */
    public static boolean hasCarrierNow(String model) {
        return findAvatarInWorlds(model) != null;
    }

    /** 登记表里是否还有该模型的记录（**不代表实体存在**，写操作防重复时才用）。 */
    public static boolean hasCarrierRecord(String model) {
        return primaryName(model) != null;
    }

    /** 跨维度扫世界里的载体（身份=实体上的 SnowModelId）。 */
    private static AvatarEntity findAvatarInWorlds(String modelId) {
        MinecraftServer srv = server();
        if (srv == null || modelId == null || modelId.isBlank()) {
            return null;
        }
        for (ServerLevel level : srv.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof AvatarEntity av && modelId.equals(av.boundModel())) {
                    return av;
                }
            }
        }
        return null;
    }

    /**
     * 该模型当前是否已有主载体——**兼容旧名，语义已改为实时**（等同 {@link #hasCarrierNow}）。
     *
     * @deprecated 用 {@link #hasCarrierNow}（读操作）或 {@link #hasCarrierRecord}（写操作防重复），
     *             别再用一个名字同时表达两件事。
     */
    @Deprecated
    public static boolean hasPrimary(String model) {
        return hasCarrierNow(model);
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
        feedback = false;
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
            if (m.get("feedback") instanceof Boolean b) {
                feedback = b;
            }
            if (m.get("cooldown_ms") instanceof Number n && n.longValue() >= 0) {
                cooldownMs = n.longValue();
            }
        } catch (Exception e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][载体] 配置读不懂，用默认值: {} ({})", f, e.getMessage());
        }
    }

    /** 玩家侧回执（配置 {@code feedback} 关闭时为 no-op——反馈属系统信息，默认不打扰）。 */
    private static void note(ServerPlayer player, String msg) {
        if (feedback) {
            ChatPush.pushTo(player, msg);
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
        // ── 投喂（P2，裁决⑤⑥）──
        // 主副手优先级：主手可用→主手；主手不可用+副手可用→副手；两侧都可用→主手
        // EntityInteract 对主副手各触发一次 → 只在 MAIN_HAND 时处理"两侧都可用"的情形
        if (tryFeed(avatar, player, event)) {
            return;   // 喂了就不再走唤醒
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

    /**
     * 尝试投喂（P2，裁决⑤⑥）。返回 true=已喂。
     * 主副手优先级：主手食物→用主手；主手无+副手食物→用副手；都食物→主手。
     * EntityInteract 双触发：只在 MAIN_HAND 且主手无食物时才检查副手。
     */
    private static boolean tryFeed(AvatarEntity avatar, ServerPlayer player,
                                   PlayerInteractEvent.EntityInteract event) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean mainFood = isFood(main);
        boolean offFood = isFood(off);

        // 两侧都无食物 → 不是投喂
        if (!mainFood && !offFood) {
            return false;
        }
        // EntityInteract 对主副手各触发一次：
        // - 主手有食物：只在 MAIN_HAND 处理（OFF_HAND 那次跳过，否则喂两口）
        // - 主手无食物+副手有：只在 OFF_HAND 处理（MAIN_HAND 那次跳过）
        if (mainFood && event.getHand() != InteractionHand.MAIN_HAND) {
            return false;
        }
        if (!mainFood && offFood && event.getHand() != InteractionHand.OFF_HAND) {
            return false;
        }

        // 用哪只手
        ItemStack food = mainFood ? main : off;
        var foodComp = food.get(net.minecraft.core.component.DataComponents.FOOD);
        int nutrition = foodComp != null ? foodComp.nutrition() : 4;
        int hungerBefore = avatar.getVitalsHunger();
        int recovered = avatar.eatFood(nutrition);
        // 少量回血（投喂附带；经验回血是 avatar_heal 的职责）
        float hpBonus = Math.min(2.0F, avatar.getMaxHealth() - avatar.getHealth());
        if (hpBonus > 0 && avatar.getVitalsHunger() > 0) {
            avatar.setHealth(avatar.getHealth() + hpBonus);
        }
        food.shrink(1);
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                        String.format("§a[投喂] %s 回饥饿 %d/%d → %d/%d，回血 +%.0f",
                                (mainFood ? "主手" : "副手"), hungerBefore, 20,
                                avatar.getVitalsHunger(), 20, hpBonus)),
                true);
        return true;
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
        // **受击提示必须能直接支持决策**（2026-10-27 20:20 用户要求）：只说"你被攻击了，来源 Dev"
        // 的话，模型分不清是剑砍、箭射、摔伤还是掉进岩浆——而这三种该做的反应完全不同
        // （回击 / 找掩体 / 找路 / 离开岩浆）。所以把类型、直接命中物、方位距离、血量一次给全。
        wakeFromPlayer("ext-player-" + safe(who), avatar, "interact:attacked",
                "你被攻击了：来源 " + who + describeHit(source, attackerEntity, avatar,
                        event.getContainer().getNewDamage()),
                attackerEntity instanceof ServerPlayer sp ? sp : null);
    }

    /**
     * 受击详情：类型 / 直接命中物 / 武器 / 方位距离 / 血量。
     *
     * <p>刻意用**不用术语的短句**（"你的右侧 3.2 格"而不是"相对 yaw +87°"）：
     * 这段文本直接进模型上下文，它要能据此决定"回击还是躲"，而不是再花一轮去查自己的朝向。
     */
    static String describeHit(net.minecraft.world.damagesource.DamageSource src,
                                      Entity attacker, AvatarEntity self, float newDamage) {
        StringBuilder b = new StringBuilder();
        // ① 伤害类型：mob_attack / player_attack / arrow / fall / lava …（比"来源"更能决定反应）
        String type = src.typeHolder().unwrapKey()
                .map(k -> k.location().getPath())
                .orElseGet(src::getMsgId);
        b.append("，类型 ").append(type);
        // ② 直接命中物（箭/雪球/火球）：和责任实体（射手）分开说，否则"被箭射"会写成像近战
        Entity direct = src.getDirectEntity();
        if (direct != null && direct != attacker) {
            b.append("，直接命中物 ").append(entityTypeOf(direct));
        }
        // ③ 武器（近战时能区分"空手打"与"拿剑砍"）
        var weapon = src.getWeaponItem();
        if (weapon != null && !weapon.isEmpty()) {
            b.append("，武器 ").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(weapon.getItem()).getPath());
        }
        // ④ 方位与距离：让"转身"这件事不需要模型自己算
        var pos = attacker != null ? attacker.position()
                : (direct != null ? direct.position() : null);
        if (pos != null) {
            double dx = pos.x - self.getX();
            double dz = pos.z - self.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.3) {
                // 同一格里：算方位角没有意义（dx=dz≈0 时会胡乱指向某个方向），如实说"贴着"
                b.append("，贴着你（同一格）");
            } else {
                b.append("，来自").append(relativeDir(self, dx, dz))
                        .append(String.format(Locale.ROOT, " %.1f 格", dist));
            }
        } else {
            b.append("，无可定位来源（环境伤害：落地/岩浆/窒息等）");
        }
        // ⑤ 血量：决定"还能不能继续打"的第一手依据
        b.append(String.format(Locale.ROOT, "；伤害约 %.1f，当前血量 %.1f/%.1f",
                newDamage, self.getHealth(), self.getMaxHealth()));
        return b.toString();
    }

    /** 方位：以载体自身朝向为基准（正前/正后/左/右），yaw 增大 = 向右。 */
    private static String relativeDir(net.minecraft.world.entity.LivingEntity self, double dx, double dz) {
        double wantYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = io.github.piwugu.snowleopard.tools.AvatarGaze.wrap(wantYaw - self.getYRot());
        String side = Math.abs(rel) <= 45 ? "正前方"
                : Math.abs(rel) >= 135 ? "正后方"
                        : rel > 0 ? "你的右侧" : "你的左侧";
        return side + String.format(Locale.ROOT, "（相对朝向 %+.0f°）", rel);
    }

    /** 实体类型短名（minecraft:arrow → arrow），比 toString 稳定。 */
    private static String entityTypeOf(Entity e) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** 说话者在多少度以外才值得转头（正前方就没必要做多余动作）。 */
    private static final double LOOK_AT_SPEAKER_MIN_DEG = 25;
    /** 转头看说话的时长（tick）：5 秒后自动解锁，别长期锁死视线。 */
    private static final int LOOK_AT_SPEAKER_TICKS = 100;

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
                note(feedbackTo, name + "：冷却中，约 " + remainSec + " 秒后再试");
                LAST_COOLDOWN_NOTE.put(avatar.getStringUUID(), window);
            }
            return;
        }
        LAST_COOLDOWN_NOTE.remove(avatar.getStringUUID());
        LAST_WAKE.put(avatar.getStringUUID(), now);
        // **叫它 = 它会转头看你**（2026-10-27 19:00 用户真机反馈："玩家在身后叫它，它没转头"）。
        // 根因不是朝向逻辑坏了，而是模型**根本不会想到调 look_at**——它只会说话或截图。
        // 所以这条不做成"建议模型去转"，而是唤醒链路里的固有反应：有人叫我，我抬头看他。
        lookAtSpeaker(avatar, feedbackTo, entry.modelId());
        requestWake(from, entry.modelId(), prompt, reason, name, feedbackTo);
    }

    /**
     * 让载体转头看说话的玩家。
     *
     * <p>只在玩家偏离 25° 以上时才转：站在正前方时转头是多余动作，反而显得没在听你说话（人在正前方时
     * 眼睛动一下就够）。走 {@code look_at} 产品路径，因此头先转、身体到限位才跟，与手动锁定同一条链路。
     *
     * <p>失败不阻断唤醒：模型可能还没 {@code pick} 实体（未绑定 → requireBound 拒），那就只丢一行日志。
     */
    private static void lookAtSpeaker(AvatarEntity avatar, ServerPlayer player, String modelId) {
        if (player == null || modelId == null || modelId.isBlank()) {
            return;
        }
        double dx = player.getX() - avatar.getX();
        double dz = player.getZ() - avatar.getZ();
        if (dx * dx + dz * dz < 1.0e-4) {
            return;   // 站在同一个点上：算不出朝向
        }
        double wantYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double delta = io.github.piwugu.snowleopard.tools.FacingMath.deltaDeg(avatar.getYRot(), wantYaw);
        if (delta < LOOK_AT_SPEAKER_MIN_DEG) {
            return;
        }
        var r = EntityToolsRuntime.dispatch(modelId, "view", "look_at",
                java.util.Map.of("target_type", "entity", "target", player.getStringUUID(),
                        "duration_ticks", LOOK_AT_SPEAKER_TICKS));
        SnowLeopardMod.LOG.info("[雪豹框架][唤醒] 说话者偏离 {}°，让载体转头看他（{}）",
                Math.round(delta), r instanceof snowleopard.core.result.Result.Ok ? "已锁定"
                        : "未生效——多半是模型还没绑定实体");
    }

    // ------------------------------------------------------------- ② 定时唤醒

    /** 每 20 tick（1 秒）扫一次到期项——**绝不能挂实体 tick**（区块卸载后实体不 tick，定时会静默失效）。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ready() || ++tickCounter % 20 != 0) {
            return;
        }
        runScheduler(event.getServer());
        // Vitals 递减（P2）：hunger 随时间 -1，hunger==0 时移速 ×0.85
        tickAllVitals(event.getServer());
    }

    /** 给所有在线载体跑 vitals tick（P2）。 */
    private static void tickAllVitals(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof AvatarEntity avatar) {
                    avatar.tickVitals();
                }
            }
        }
    }

    /** 调度器本体（自测直接调，绕开 tick 计数）。 */
    public static void runScheduler(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (AvatarStore.Entry entry : store.due(now)) {
            store.advance(entry.uuid(), now);   // 先推进：**跳过也不堆积、不补发**（裁决 3）
            Entity entity = resolveCarrier(server, entry);   // 身份=modelId：旧 uuid 失效时按实体上的 ID 自愈
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
                note(speaker, shown + "：已记录提示（编号提及不唤醒，名称才会）");
                continue;
            }
            AvatarStore.Entry primary = store.primaryOf(model);
            if (primary == null) {
                note(speaker, shown + "：被提及，但没有主载体，未唤醒");
                continue;   // 没载体：只提示，不唤醒
            }
            Entity carrier = resolveCarrier(server(), primary);
            if (!(carrier instanceof AvatarEntity avatar)) {
                note(speaker, shown + "：被提及，但载体未加载，本轮只提示");
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
            case "avatar_vitals" -> vitals(model);
            case "avatar_eat" -> eat(model, params);
            case "avatar_heal" -> heal(model, params);
            default -> Result.error("未知的载体工具: " + tool
                    + "（可用: avatar_bind / avatar_unbind / avatar_schedule / avatar_status / avatar_vitals / avatar_eat / avatar_heal）");
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
            // 登记点之一：有载体 = 这个模型在世界里真的有个身体，从这一刻起它必须能被 @到
            ModelRegistry.register(model);
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
        for (AvatarStore.Entry snapshot : store.byModel(model)) {
            // **先解析，再取一次登记**（2026-10-28 真机踩到）：resolveCarrier 可能把地址回写成新值，
            // 而这里的 snapshot 是回写前取好的副本——直接用它输出，会把一个已经失效的死地址报给模型/运维，
            // 表现是"自愈日志说改了、status 却还是老 uuid"。
            Entity carrier = resolveCarrier(server, snapshot);
            AvatarStore.Entry e = carrier == null ? snapshot
                    : store.byUuid(carrier.getStringUUID());
            if (e == null) {
                e = snapshot;
            }
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("uuid", e.uuid());
            m.put("name", e.name().isEmpty() ? model : e.name());
            m.put("primary", e.primary());
            m.put("intervalSec", e.intervalSec());
            m.put("nextFireAt", e.nextFireAt());
            m.put("loaded", carrier != null);
            out.add(m);
        }
        return Result.ok(Map.of("avatars", out));
    }

    // ── Vitals（P2，分析 v3 §3.3/§3.4）──

    private static AvatarEntity primaryAvatar(String model) {
        for (AvatarStore.Entry e : store.byModel(model)) {
            if (!e.primary()) {
                continue;
            }
            var entity = resolveCarrier(server(), e);
            if (entity instanceof AvatarEntity avatar) {
                return avatar;
            }
        }
        return null;
    }

    /** 供 WakeBridge 注入用（P2 vitals 段）。 */
    public static AvatarEntity primaryAvatarForInjection(String model) {
        return primaryAvatar(model);
    }

    private static Result vitals(String model) {
        AvatarEntity avatar = primaryAvatar(model);
        if (avatar == null) {
            return Result.error("主载体不在线——先用 avatar_bind 登记载体");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("xp", avatar.getVitalsXp());
        out.put("xpMax", avatar.getVitalsXpMax());
        out.put("hunger", avatar.getVitalsHunger());
        out.put("health", avatar.getHealth());
        out.put("maxHealth", avatar.getMaxHealth());
        out.put("xpPerHp", avatar.getVitalsXpPerHp());
        out.put("hpPerTick", avatar.getVitalsHpPerTick());
        return Result.ok(out);
    }

    private static Result eat(String model, Map<String, Object> params) {
        AvatarEntity avatar = primaryAvatar(model);
        if (avatar == null) {
            return Result.error("主载体不在线");
        }
        // 从九格包找食物（DataComponents.FOOD）
        int slot = -1;
        Object slotObj = params == null ? null : params.get("slot");
        if (slotObj instanceof Number n) {
            slot = n.intValue();
        }
        net.minecraft.world.Container pack = avatar.pack();
        if (slot >= 0 && slot < pack.getContainerSize()) {
            var stack = pack.getItem(slot);
            if (isFood(stack)) {
                return doEat(avatar, slot);
            }
            return Result.error("背包格 " + slot + " 不是食物");
        }
        // 自动找第一个食物
        for (int i = 0; i < pack.getContainerSize(); i++) {
            if (isFood(pack.getItem(i))) {
                return doEat(avatar, i);
            }
        }
        return Result.error("背包里没有食物");
    }

    private static boolean isFood(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty()
                && stack.has(net.minecraft.core.component.DataComponents.FOOD);
    }

    private static Result doEat(AvatarEntity avatar, int slot) {
        var pack = avatar.pack();
        var stack = pack.getItem(slot);
        var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        int nutrition = food != null ? food.nutrition() : 4;
        int hungerBefore = avatar.getVitalsHunger();
        int recovered = avatar.eatFood(nutrition);
        stack.shrink(1);
        return Result.ok(Map.of(
                "eaten", true,
                "hungerBefore", hungerBefore,
                "hungerAfter", avatar.getVitalsHunger(),
                "recovered", recovered));
    }

    private static Result heal(String model, Map<String, Object> params) {
        AvatarEntity avatar = primaryAvatar(model);
        if (avatar == null) {
            return Result.error("主载体不在线");
        }
        if (avatar.getVitalsHunger() <= 0) {
            return Result.error("饥饿归零，不能使用经验回血");
        }
        float hpRequest;
        Object hpObj = params == null ? null : params.get("hp");
        if (hpObj instanceof Number n) {
            hpRequest = n.floatValue();
        } else {
            hpRequest = avatar.getMaxHealth() - avatar.getHealth();   // 缺省回满
        }
        int xpBefore = avatar.getVitalsXp();
        float healed = avatar.healByXp(hpRequest);
        if (healed <= 0) {
            return Result.error("经验不足或血量已满");
        }
        return Result.ok(Map.of(
                "healed", healed,
                "xpCost", xpBefore - avatar.getVitalsXp(),
                "xpBefore", xpBefore,
                "xpAfter", avatar.getVitalsXp()));
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
            // **运行中被叫：不排队，直接注入正在跑的会话**（2026-10-27 17:10 (UTC+8)）
            // 唤醒闸有"目标在运行 → 拒"这条（防重复会话），但玩家右键/点名要的是"它现在知道"。
            // 真机反馈："唤醒被拒绝"——拒绝完只剩一条提示，模型那轮根本看不到。
            if (reason != null && (reason.startsWith("interact") || reason.startsWith("mention"))
                    && AiHost.interject(model, "【即时消息·" + reason + "】" + sanitize(prompt)
                    + "\n（这是运行中插进来的，不用重开任务；先看一眼再决定要不要调整）")) {
                SnowLeopardMod.LOG.info("[雪豹框架][唤醒] {} 正在运行 → 已注入当前会话（{}）", model, reason);
                note(feedbackTo, carrierName + "：正在忙，这条提醒已塞进它当前的会话");
                return;
            }
            // 唤醒失败 → 补提示（裁决：提示 ≠ 唤醒；"有人叫你但没叫起来"不能消失）
            SnowLeopardMod.LOG.warn("[雪豹框架][唤醒] {} → {} 失败：{}（已补一条提示）",
                    reason, model, err.desc());
            ChatRuntime.addPendingMention(model);
            note(feedbackTo, carrierName + "：唤醒被拒（" + brief(err.desc()) + "）· 已转为提示");
        } else {
            SnowLeopardMod.LOG.info("[雪豹框架][唤醒] {} → {} 已登记为 pending", reason, model);
            note(feedbackTo, carrierName + "：唤醒已登记（" + reason + " → " + model
                    + "）· 等待执行端拉取");
        }
    }

    /** 回执里的原因只留一行能读懂的（Error 描述可能很长）。 */
    private static String brief(String desc) {
        String s = desc == null ? "" : desc.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** 载体对外显示名（与身份单点一致：有名字用名字，没名字用模型编号）。 */
    /**
     * 解析某条登记**当前**对应的实体（2026-10-28 架构修正）。
     *
     * <p><b>身份是 modelId，uuid 只是地址</b>。旧实现只有 {@link #findEntity} 一条路：
     * 换世界 / 存档回档 / 实体被重生成后 uuid 全失效 → 定时唤醒永久跳过、点名唤醒永远回
     * "载体未加载"，而实体自己的 NBT（SnowModelId）明明还写着这个模型——**没人去用它反查**。
     *
     * <p>这里补上自愈：uuid 找不到时按实体侧 ID 重新发现，并把新地址回写进排程表，
     * 于是"把家搬到另一个世界"变成正常路径（重新绑定载体即恢复），不再需要删 avatars.json。
     */
    private static Entity resolveCarrier(MinecraftServer server, AvatarStore.Entry entry) {
        if (server == null || entry == null) {
            return null;
        }
        Entity direct = findEntity(server, entry.uuid());
        if (direct != null) {
            return direct;
        }
        // 多候选时的择优选（2026-10-28 真机踩到）：世界里可能残留同 modelId 的旧载体
        // （上一轮验收没清干净、或玩家手放了一只），先按**名字**匹配——名字也是身份的一部分
        // （`[雪豹·小白]` 就靠它区分谁被点了名）；都对不上才取第一个并留 WARN。
        Entity fallback = null;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity cand : level.getEntities().getAll()) {
                if (!(cand instanceof AvatarEntity av) || !entry.modelId().equals(av.boundModel())) {
                    continue;
                }
                boolean sameName = !entry.name().isEmpty() && cand.hasCustomName()
                        && entry.name().equals(cand.getCustomName().getString());
                if (sameName) {
                    return adoptAddress(entry, av);
                }
                if (fallback == null) {
                    fallback = cand;
                }
            }
        }
        if (fallback != null) {
            SnowLeopardMod.LOG.warn("[雪豹框架][载体] {} 有多个候选载体且名字都对不上"
                    + "（登记名='{}'），暂取 uuid={}——建议清理多余的载体",
                    entry.modelId(), entry.name(), fallback.getStringUUID());
            return adoptAddress(entry, (AvatarEntity) fallback);
        }
        return null;
    }

    /** 把"当下这只实体"的地址写回登记（身份不变，只换地址）。 */
    private static Entity adoptAddress(AvatarStore.Entry entry, AvatarEntity carrier) {
        String fresh = carrier.getStringUUID();
        if (store.rebindUuid(entry.modelId(), entry.uuid(), fresh)) {
            SnowLeopardMod.LOG.info(
                    "[雪豹框架][载体] 身份自愈 {}：uuid {} → {}（旧地址失效，按实体上的 modelId 重新发现）",
                    entry.modelId(), entry.uuid(), fresh);
        }
        return carrier;
    }

    /**
     * 把一个**已存在的实体**登记成某模型的主载体（玩家侧绑定用，2026-10-28 语义修正）。
     *
     * <p>绑定 = 消耗核心 + **生成载体**；这里是"生成之后登记"的那一步。
     * 与 {@code bind(model)} 的区别：那条走"当前 pick 到的实体"，这条直接收实体对象，
     * 供服务端生成后立刻登记（不给玩家中间态：生成了却没登记）。
     */
    public static Result bindEntityTo(AvatarEntity avatar, String modelId) {
        if (avatar == null || modelId == null || modelId.isBlank()) {
            return Result.error("载体或模型编号为空");
        }
        String name = avatar.hasCustomName() ? avatar.getCustomName().getString() : modelId;
        try {
            AvatarStore.Entry entry = store.bind(avatar.getStringUUID(), modelId, name);
            avatar.setBoundModel(modelId);
            // 登记点之一：有载体 = 这个模型在世界里真的有个身体，从这一刻起它必须能被 @到
            ModelRegistry.register(modelId);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("uuid", entry.uuid());
            out.put("model", modelId);
            out.put("name", entry.name().isEmpty() ? modelId : entry.name());
            return Result.ok(out);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());   // 重名 → 拒绝（裁决 4）
        }
    }

    /**
     * 跨维度解析某模型**当前的主载体**（2026-10-28）：身份是 modelId，实体只是身体。
     *
     * <p>与 {@code primaryAvatar} 的区别：那条只在"登记里已写死的地址"上找，换世界就找不到；
     * 这条按实体上的 SnowModelId 扫各维度，找不到再回落到登记地址。
     * 玩家侧"传送到某家"用它——跨维度也算"有载体"，由调用方决定是否允许跨维度传送。
     */
    public static AvatarEntity primaryAvatarOf(String modelId) {
        AvatarEntity found = findAvatarInWorlds(modelId);
        return found != null ? found : primaryAvatar(modelId);   // 都没有 → 按登记地址再试一次
    }

    /** 自检用：直接写一条登记（不需要真实实体），用来验证"登记在 ≠ 实体在"。 */
    public static boolean bindRecordForTest(String modelId, String uuid, String name) {
        if (store == null) {
            return false;
        }
        try {
            store.bind(uuid, modelId, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 自检用：把某条登记的"地址"改成指定值，模拟"换世界 / 回档后旧 uuid 失效"。
     *
     * <p>为什么需要这条缝：实体 uuid 是 final、没法在测试里改，而"旧地址失效"正是本次要验的场景。
     * 改地址后调 avatar_status，若 loaded 仍为 true 且 uuid 已回写，说明自愈真的发生了。
     */
    public static boolean rebindAddressForTest(String model, String oldUuid, String newUuid) {
        return store != null && store.rebindUuid(model, oldUuid, newUuid);
    }

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
