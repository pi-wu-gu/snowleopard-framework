package io.github.piwugu.snowleopard.tools;

import java.util.List;

import snowleopard.core.Version;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.spi.ToolGroupProvider;

/**
 * 实体控制工具组 SPI 提供者（M0 骨架，2026-10-23 05:30 (UTC+8)）。
 *
 * <p>M0 只注册**空工具组骨架**（组名/简介/分类冻结，tools 逐 M 填充）——契约先行：清单 schema
 * 过核心注册校验（summary 必填、工具 usage+params 必填）。来源 {@code snowleopard}，
 * 限定名由装载器拼：{@code mod:snowleopard:<组名>}。
 */
public final class EntityToolsProvider extends ToolGroupProvider.Simple {

    private final String group;
    private final String category;
    private final String summary;

    public EntityToolsProvider(String group, String category, String summary) {
        super(Version.CORE_API, "snowleopard");
        this.group = group;
        this.category = category;
        this.summary = summary;
    }

    @Override
    public ToolGroupProvider.Descriptor describe() {
        // M0：空 tools（骨架）；M1 起按组填充——工具进组即进清单，不改组名
        return new ToolGroupProvider.Descriptor(group, category, summary,
                "实体控制工具组（雪豹框架 entitytools）——详情随里程碑逐步充实。",
                ToolRegistry.Availability.NORMAL, List.of());
    }

    /** M0 骨架：九个冻结组名各注册一个空组（实施顺序见开发流程 M1–M6）。 */
    public static List<EntityToolsProvider> skeletons() {
        return List.of(
                new EntityToolsProvider(GroupNames.SENSE, "entity/sense", "感官开关与候选选取"),
                new EntityToolsProvider(GroupNames.ENTITY, "entity/life", "实体-模型绑定生命周期"),
                new EntityToolsProvider(GroupNames.ENTITY_SELF, "entity/self", "自我状态观测"),
                new EntityToolsProvider(GroupNames.MOVE, "move", "移动（三驱动/寻路/阻塞判定）"),
                new EntityToolsProvider(GroupNames.VIEW, "view", "视角朝向与锁定"),
                new EntityToolsProvider(GroupNames.INTERACT, "interact", "交互（攻击/使用/放置/破坏）"),
                new EntityToolsProvider(GroupNames.ITEM, "item", "物品（双索引检索/容器/移动）"),
                new EntityToolsProvider(GroupNames.WORLD, "world", "世界区域查询（单一工具）"),
                new EntityToolsProvider(GroupNames.WATCH, "watch", "动态感知（含绑定实体，带控制模型编号）"));
    }

    /** 冻结的参数 schema 键（M1 起工具 params 用语，防各组各写一套）。 */
    public static final class Params {
        public static final String DURATION_TICKS = "duration_ticks";
        public static final String DISTANCE = "distance";
        public static final String TARGET = "target";
        public static final String CONTROLLER = "controller";

        private Params() {
        }
    }

    /** 冻结的完成判定口径（M0 常量化，实现与断言共用同一出处）。 */
    public static final class Completion {
        /** DONE=连续 2 tick 贴住目标（防服务端回拉假成功）。 */
        public static final int DONE_STICKY_TICKS = 2;
        /** 阻塞=单 tick 水平位移 < 阈值连续 12 tick（3D 位移会把垂直漂移误判成进展）。 */
        public static final double BLOCKED_HORIZONTAL_EPS = 0.02;
        public static final int BLOCKED_TICKS = 12;

        private Completion() {
        }
    }
}
