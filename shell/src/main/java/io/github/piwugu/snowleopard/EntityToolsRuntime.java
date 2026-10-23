package io.github.piwugu.snowleopard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import io.github.piwugu.snowleopard.mc.EntityToolsModule;
import snowleopard.core.result.Result;

/**
 * 实体工具运行时（壳侧装配点，M1，2026-10-23 05:40 (UTC+8)）。
 *
 * <p>每模型（caller）一个 {@link EntityToolsModule}——"ID 文件夹=模型的家"在实体域的投影。
 * 调度入口 {@link #dispatch} 供宿主/模型侧调用；死亡事件挂 NeoForge 事件总线（裁决：死亡→关感官）。
 * 壳只做装配与转发，业务语义在 entitytools（薄壳边界）。
 */
public final class EntityToolsRuntime {

    private static final Map<String, EntityToolsModule> MODULES = new ConcurrentHashMap<>();

    /**
     * 自动记录挂核心 TriggerRegistry（M6）——坏钩子不杀主链：fire() 按钩子隔离异常。
     * 挂点=全部生命周期点（自动记录是连续态势，不分轮次）。
     */
    private static final snowleopard.core.trigger.TriggerRegistry TRIGGERS =
            new snowleopard.core.trigger.TriggerRegistry();

    static {
        TRIGGERS.register(new snowleopard.core.trigger.TriggerRegistry.Hook() {
            @Override
            public String groupFull() {
                return "mod:snowleopard:watch";
            }

            @Override
            public void fire(snowleopard.core.trigger.TriggerRegistry.Point point) {
                for (EntityToolsModule m : MODULES.values()) {
                    m.tickWatch();   // 自动记录：玩家入册 + 轨迹限频采样 + 保留窗口淘汰
                }
            }
        });
    }

    private EntityToolsRuntime() {
    }

    /** 触发器挂点（自测可注入坏钩子验证隔离；宿主按会话生命周期 fire）。 */
    public static snowleopard.core.trigger.TriggerRegistry triggers() {
        return TRIGGERS;
    }

    /** 感知锚点覆盖：钉住某模型的扫描/查询中心（宿主可用；测试用它把各模型锚进各自房间）。 */
    private static final Map<String, net.minecraft.core.BlockPos> SCAN_ANCHORS = new ConcurrentHashMap<>();

    public static void setScanAnchor(String modelId, net.minecraft.core.BlockPos anchor) {
        if (modelId == null || anchor == null) {
            return;
        }
        SCAN_ANCHORS.put(modelId, anchor.immutable());
    }

    /** 取（或建）某模型的模块。世界上下文：有玩家取其所在世界与位置；无玩家退回出生点（专用服务器自测可用）。 */
    /** 模型当前绑定的实体编号（载体绑定用；null = 未绑定）。注意：会创建模块实例。 */
    public static String boundEntityRef(String modelId) {
        EntityToolsModule m = MODULES.get(modelId);
        return m == null ? null : m.bindings().entityOf(modelId);
    }

    public static EntityToolsModule module(String modelId) {
        return MODULES.computeIfAbsent(modelId, id -> new EntityToolsModule(id, new EntityToolsModule.Ctx() {
            @Override
            public ServerLevel level() {
                MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                if (s == null) {
                    return null;
                }
                ServerPlayer p = firstPlayer(s);
                return p != null ? p.serverLevel() : s.overworld();
            }

            @Override
            public net.minecraft.core.BlockPos center() {
                net.minecraft.core.BlockPos anchor = SCAN_ANCHORS.get(id);
                if (anchor != null) {
                    return anchor;
                }
                MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                if (s == null) {
                    return null;
                }
                ServerPlayer p = firstPlayer(s);
                return p != null ? p.blockPosition() : s.overworld().getSharedSpawnPos();
            }
        }));
    }

    private static ServerPlayer firstPlayer(MinecraftServer s) {
        return s.getPlayerList().getPlayers().isEmpty()
                ? null : s.getPlayerList().getPlayers().get(0);
    }

    /**
     * 工具调度（宿主入口）。caller_id 由调用上下文注入——不可作为工具参数伪造（红线 6）。
     * 其他模型已绑定的实体自动进排除集（裁决：无法使用其他编号实体）。
     */
    public static Result dispatch(String modelId, String group, String tool, Map<String, Object> params) {
        if (modelId == null || modelId.isBlank()) {
            return Result.error("dispatch 需要调用方模型编号（core 注入，不可伪造）");
        }
        // 内置四组（会话/记忆/任务/笔记）走核心执行面——分流必须在实体组解析之前，
        // 否则会先按 mod:snowleopard: 前缀做权限判定（内置组会一律被拒）
        if (BuiltinRuntime.isBuiltin(group)) {
            return BuiltinRuntime.dispatch(modelId, group, tool, params);
        }
        // 权限门禁预检（核心权限表；组=mod:snowleopard:<group>）
        snowleopard.core.SnowLeopard core = SnowLeopardMod.core();
        if (core != null && core.permissions() != null
                && !core.permissions().table().allows("mod:snowleopard:" + group, tool)) {
            return Result.error("权限拒绝: mod:snowleopard:" + group + "." + tool + "（权限文件可改判）");
        }
        // 聊天组：权限预检已过（组全名 mod:snowleopard:chat），执行面在 ChatRuntime
        if (ChatRuntime.isChat(group)) {
            return ChatRuntime.dispatch(modelId, tool, params);
        }
        // 载体组：执行面在 AvatarWake（唤醒源与工具同处，共享登记表与冷却）
        if (io.github.piwugu.snowleopard.tools.GroupNames.AVATAR.equals(group)) {
            return AvatarWake.dispatch(modelId, tool, params);
        }
        EntityToolsModule m = module(modelId);
        return m.call(group, tool, params, ref -> {
            for (Map.Entry<String, EntityToolsModule> e : MODULES.entrySet()) {
                if (e.getKey().equals(modelId)) {
                    continue;
                }
                if (ref.equals(e.getValue().bindings().entityOf(e.getKey()))) {
                    return true;
                }
            }
            return false;
        }, EntityToolsRuntime::controllerOf);
    }

    /** 实体死亡事件 → 各模块死亡流（绑定实体死亡=感官自动关）+ DIED 动态。 */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (e == null) {
            return;
        }
        String ref = e.getUUID().toString();
        for (EntityToolsModule m : MODULES.values()) {
            m.onEntityDeath(ref);
        }
    }

    /** 受伤事件 → 攻击源动态（ATTACKED）+ 攻击者自动入册（裁决：自动记录攻击源）。
     *  事件名注意：NeoForge 21.1 无 LivingHurtEvent，是 LivingIncomingDamageEvent（2026-10-23 编译踩过）。 */
    @SubscribeEvent
    public static void onLivingHurt(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target == null) {
            return;
        }
        String targetRef = target.getUUID().toString();
        net.minecraft.world.damagesource.DamageSource src = event.getSource();
        net.minecraft.world.entity.Entity attacker = src == null ? null : src.getEntity();
        String attackerRef = attacker == null ? "" : attacker.getUUID().toString();
        for (EntityToolsModule m : MODULES.values()) {
            m.onHurt(targetRef, attackerRef, event.getAmount(), EntityToolsRuntime::controllerOf);
        }
    }

    /** 归属反查：实体 → 控制模型编号（无主/真人=空；红线 6：来自绑定表，不可伪造）。 */
    public static String controllerOf(String entityRef) {
        if (entityRef == null) {
            return "";
        }
        for (Map.Entry<String, EntityToolsModule> e : MODULES.entrySet()) {
            if (entityRef.equals(e.getValue().bindings().entityOf(e.getKey()))) {
                return e.getKey();
            }
        }
        return "";
    }

    /** 服务端每 tick：视角锁定（M2）+ 挖掘推进（M3）+ 移动推进（M4）+ 动态自动记录（M6）。 */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        for (EntityToolsModule m : MODULES.values()) {
            m.tickViewLock();
            m.tickInteract();
            m.tickMove();
            m.tickWatch();
        }
    }

    /** 空场景自测用：模块数与登记快照。 */
    public static Map<String, String> bindingSnapshot() {
        Map<String, String> out = new ConcurrentHashMap<>();
        MODULES.forEach((k, v) -> {
            String ent = v.bindings().entityOf(k);
            if (ent != null) {
                out.put(k, ent);
            }
        });
        return out;
    }
}
