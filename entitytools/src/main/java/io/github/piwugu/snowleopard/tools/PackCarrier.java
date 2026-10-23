package io.github.piwugu.snowleopard.tools;

import net.minecraft.world.Container;

/**
 * 有随身背包的载体（H4.5，2026-10-24 03:30 (UTC+8) 用户要求"给它一个九格背包"）。
 *
 * <p>物品工具经此接口访问随身背包，**不反依赖壳侧实体类**（entitytools 不能引用 shell——
 * 依赖方向是 shell → entitytools）。槽位引用 {@code self:pack:0..8}。
 */
public interface PackCarrier {

    /** 随身背包（9 格；随实体 NBT 持久化）。 */
    Container pack();
}
