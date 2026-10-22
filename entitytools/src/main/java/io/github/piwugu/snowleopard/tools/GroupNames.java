package io.github.piwugu.snowleopard.tools;

import java.util.List;

/**
 * 工具组命名契约（M0 冻结，2026-10-23 05:30 (UTC+8)）。
 *
 * <p><b>命名规则</b>：组短名<b>不得含冒号</b>——冒号是限定名 {@code <类型>:<来源ID>:<组名>} 的
 * 分隔符（分析稿曾写 {@code entity:self}，冻结时改为 {@code entity_self}）。分类层级用
 * {@code category} 的斜杠表达（如 {@code entity/self}），不进组名。
 */
public final class GroupNames {

    /** 感官开关与选取（M1）。 */
    public static final String SENSE = "sense";
    /** 绑定生命周期（M1）。 */
    public static final String ENTITY = "entity";
    /** 自我状态观测（M1）。 */
    public static final String ENTITY_SELF = "entity_self";
    /** 移动（M4）。 */
    public static final String MOVE = "move";
    /** 视角（M2）。 */
    public static final String VIEW = "view";
    /** 交互（M3）。 */
    public static final String INTERACT = "interact";
    /** 物品（M5）。 */
    public static final String ITEM = "item";
    /** 世界查询（M5）。 */
    public static final String WORLD = "world";
    /** 动态感知（M6）。 */
    public static final String WATCH = "watch";

    /** 冻结的全部组名（顺序=实施顺序无关，仅稳定枚举）。 */
    public static final List<String> ALL = List.of(
            SENSE, ENTITY, ENTITY_SELF, MOVE, VIEW, INTERACT, ITEM, WORLD, WATCH);

    private GroupNames() {
    }
}
