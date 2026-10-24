package io.github.piwugu.snowleopard.tools;

/**
 * 支持自动拾取配置的载体（v1.10.0，2026-10-24 18:55 (UTC+8)，分析 §3.2）。
 *
 * <p>{@code pickup_auto} / {@code pickup} 工具经本契约操作开关与过滤——不反依赖壳侧实体类
 * （依赖方向 shell → entitytools，同 {@link PackCarrier} 的理由）。
 *
 * <p><b>开关的落点</b>：{@code Mob.setCanPickUpLoot/canPickUpLoot}（原版 API，载体覆盖后
 * 路由到自己的字段并随原版 NBT {@code "CanPickUpLoot"} 持久化）——本接口只管"过滤词"这一件
 * 原版没有的事。开关与过滤分离是有意的：开关走原版 API 白拿持久化，过滤词存实体 NBT
 * {@code "PickupFilter"}（壳侧管）。
 */
public interface PickupCarrier extends PackCarrier {

    /** 拾取过滤词（registry id 子串，忽略大小写；空=全部）。 */
    void setPickupFilter(String filter);

    /** 当前过滤词。 */
    String pickupFilter();
}
