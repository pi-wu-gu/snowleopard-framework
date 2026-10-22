package io.github.piwugu.snowleopard.mc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 休眠管理（M1，2026-10-23 05:40 (UTC+8)；裁决"未选取的实体进入休眠"）。
 *
 * <p>休眠语义（冻结于 M0 契约）：冻结 AI/移动（{@code setNoAi}）、不响应本工具组交互、
 * 查询标注 {@code dormant}。休眠是<b>身份消歧</b>手段不是删除——实体仍在世界里。
 * 重新 open 感官时全部唤醒（新一轮候选公平）。
 */
public final class DormancyManager {

    private final Set<String> dormant = ConcurrentHashMap.newKeySet();

    /** 休眠：冻结 AI（setNoAi 仅 Mob 有——非 Mob 只做 dormant 标注）。 */
    public void sleep(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        dormant.add(key(entity));
        if (entity instanceof Mob m) {
            m.setNoAi(true);
        }
    }

    /** 唤醒：恢复 AI。 */
    public void wake(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        dormant.remove(key(entity));
        if (entity instanceof Mob m) {
            m.setNoAi(false);
        }
    }

    /** 全部唤醒（重新开感官/收尾）。 */
    public void wakeAll(Iterable<? extends LivingEntity> entities) {
        for (LivingEntity e : entities) {
            wake(e);
        }
    }

    public boolean isDormant(LivingEntity entity) {
        return entity != null && dormant.contains(key(entity));
    }

    public int size() {
        return dormant.size();
    }

    private static String key(LivingEntity entity) {
        return entity.getUUID().toString();
    }
}
