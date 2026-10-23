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

    private EntityToolsRuntime() {
    }

    /** 取（或建）某模型的模块。世界上下文：有玩家取其所在世界与位置；无玩家退回出生点（专用服务器自测可用）。 */
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
        // 权限门禁预检（核心权限表；组=mod:snowleopard:<group>）
        snowleopard.core.SnowLeopard core = SnowLeopardMod.core();
        if (core != null && core.permissions() != null
                && !core.permissions().table().allows("mod:snowleopard:" + group, tool)) {
            return Result.error("权限拒绝: mod:snowleopard:" + group + "." + tool + "（权限文件可改判）");
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
        }, ref -> {
            for (Map.Entry<String, EntityToolsModule> e : MODULES.entrySet()) {
                if (ref.equals(e.getValue().bindings().entityOf(e.getKey()))) {
                    return e.getKey();
                }
            }
            return "";
        });
    }

    /** 实体死亡事件 → 各模块死亡流（绑定实体死亡=感官自动关）。 */
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

    /** 服务端每 tick：视角锁定（M2）+ 挖掘推进（M3）+ 移动推进（M4）。 */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        for (EntityToolsModule m : MODULES.values()) {
            m.tickViewLock();
            m.tickInteract();
            m.tickMove();
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
