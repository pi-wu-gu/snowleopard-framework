package io.github.piwugu.snowleopard.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import io.github.piwugu.snowleopard.tools.CandidateInfo;

/**
 * 候选三件套采集（M1，2026-10-23 05:40 (UTC+8)；裁决"提供实体信息、位置信息、周围环境信息给模型"）。
 *
 * <p>候选=半径内 LivingEntity，排除：发起选取的本人类玩家、已绑定给他人的实体（无法使用其他编号实体）。
 * 三件套字段表见 {@link CandidateInfo}（M0 冻结）。
 */
public final class CandidateScanner {

    /** 默认扫描半径（方块）。 */
    public static final double DEFAULT_RADIUS = 32.0;

    private CandidateScanner() {
    }

    /**
     * 扫描候选。center 为半径中心（坐标——无玩家的专用服务器也能跑）；
     * excludeRefs 为不可选实体（已被其他模型绑定）。
     */
    public static List<CandidateInfo> scan(ServerLevel level, BlockPos center,
                                           double radius, java.util.function.Predicate<String> excludeRefs) {
        List<CandidateInfo> out = new ArrayList<>();
        if (level == null || center == null) {
            return out;
        }
        AABB box = new AABB(center).inflate(radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> !(e instanceof Player))) {
            String ref = e.getUUID().toString();
            if (excludeRefs != null && excludeRefs.test(ref)) {
                continue;
            }
            out.add(new CandidateInfo(ref,
                    entityInfo(e),
                    position(e),
                    surroundings(e)));
        }
        return out;
    }

    /** 实体信息（冻结键：type/name/health/controller）。 */
    public static Map<String, Object> entityInfo(LivingEntity e) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        return Map.of(
                "type", type,
                "name", e.getName().getString(),
                "health", (double) e.getHealth(),
                "controller", "");   // 一般实体=空；绑定实体由上层标注模型编号
    }

    /** 位置信息（冻结键：x/y/z/dimension）。 */
    public static Map<String, Object> position(LivingEntity e) {
        return Map.of(
                "x", e.getX(), "y", e.getY(), "z", e.getZ(),
                "dimension", e.level().dimension().location().toString());
    }

    /** 周围环境信息（冻结键：block/light/nearbyEntities）。 */
    public static Map<String, Object> surroundings(LivingEntity e) {
        String block = e.level().getBlockState(e.blockPosition()).getBlock().toString();
        int light = e.level().getMaxLocalRawBrightness(e.blockPosition());
        int nearby = e.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(e.blockPosition()).inflate(8)).size();
        return Map.of("block", block, "light", light, "nearbyEntities", nearby);
    }
}
