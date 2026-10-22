package io.github.piwugu.snowleopard.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 自我状态观测（M1，2026-10-23 05:40 (UTC+8)）：位置/朝向/血/饥饿/状态效果/装备/维度/水/火。
 * 字段全读世界事实——不缓存不猜测（假成功比失败更坏）。
 */
public final class SelfStatusReader {

    private SelfStatusReader() {
    }

    public static Map<String, Object> read(LivingEntity e) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("x", e.getX());
        d.put("y", e.getY());
        d.put("z", e.getZ());
        d.put("yaw", (double) e.getYRot());
        d.put("pitch", (double) e.getXRot());
        d.put("health", (double) e.getHealth());
        d.put("maxHealth", (double) e.getMaxHealth());
        if (e instanceof Player p) {
            d.put("food", p.getFoodData().getFoodLevel());
        } else {
            d.put("food", -1);
        }
        List<String> effects = new ArrayList<>();
        e.getActiveEffects().forEach(ef -> effects.add(
                ef.getEffect().unwrapKey().map(k -> k.location().toString()).orElse("?")));
        d.put("effects", effects);
        List<String> armor = new ArrayList<>();
        for (ItemStack stack : e.getArmorSlots()) {
            armor.add(stack.isEmpty() ? "empty"
                    : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        d.put("armor", armor);
        d.put("dimension", e.level().dimension().location().toString());
        d.put("onFire", e.isOnFire());
        d.put("inWater", e.isInWater());
        d.put("sneaking", e.isCrouching());
        return d;
    }
}
