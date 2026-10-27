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
    private final List<ToolRegistry.Tool> tools;

    public EntityToolsProvider(String group, String category, String summary,
                               List<ToolRegistry.Tool> tools) {
        super(Version.CORE_API, "snowleopard");
        this.group = group;
        this.category = category;
        this.summary = summary;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    @Override
    public ToolGroupProvider.Descriptor describe() {
        return new ToolGroupProvider.Descriptor(group, category, summary,
                "实体控制工具组（雪豹框架 entitytools）——详情随里程碑逐步充实。",
                ToolRegistry.Availability.NORMAL, tools);
    }

    /**
     * 工具组提供者清单（M1 起的冻结组名；vision 组 v1.12.9 才真正挂上）。
     *
     * <p>**2026-10-27 13:25 (UTC+8) 踩过**：{@code ToolSchemas.VISION_TOOLS} 从 v1.12.5 起就定义好了，
     * 但这里一直没把它挂进任何组 → 三个视觉工具**从未进入 LLM 工具清单**。
     * 验收脚本直接按注册名 {@code dispatch("vision","shot")} 派发，绕过了"模型能不能看见"这一层，
     * 于是 30 条断言全绿、模型却根本不知道有视觉这回事（用户实测："模型似乎并不知道如何使用视觉"）。
     * 教训：**"工具能执行"与"工具对模型可见"是两件事**，断言必须走清单（暴露名）而不是执行面注册名。
     *
     * @param withVision 是否挂 vision 组（由 {@code vision.enabled} 决定；关掉=工具面回落 44）
     */
    public static List<EntityToolsProvider> skeletons(boolean withVision) {
        List<EntityToolsProvider> groups = new java.util.ArrayList<>();
        // **vision 排最前**（2026-10-27 13:50 (UTC+8)）：模型对清单**末尾**的工具视而不见。
        // 实测（deepseek-flash / mimo-v2.6-flash / step-1o-turbo-vision，78 个工具、同一问法）：
        // vision 在末位时前两家**一次都没调**（转头去调 status_all / chat_pull），移到最前后才命中。
        // 顺序不影响功能，只影响"模型记不记得有这么个东西"，所以重要的放前面。
        if (withVision) {
            groups.add(new EntityToolsProvider(GroupNames.VISION, "vision",
                    "视觉：射线扫描 / 申请截图 / 重看已拍图", ToolSchemas.VISION_TOOLS));
        }
        groups.addAll(List.of(
                new EntityToolsProvider(GroupNames.SENSE, "entity/sense",
                        "感官开关与候选选取", ToolSchemas.SENSE_TOOLS),
                new EntityToolsProvider(GroupNames.ENTITY, "entity/life",
                        "实体-模型绑定生命周期", ToolSchemas.ENTITY_TOOLS),
                new EntityToolsProvider(GroupNames.ENTITY_SELF, "entity/self",
                        "自我状态观测", ToolSchemas.SELF_TOOLS),
                new EntityToolsProvider(GroupNames.MOVE, "move", "移动（三驱动/寻路/阻塞判定）", ToolSchemas.MOVE_TOOLS),
                new EntityToolsProvider(GroupNames.VIEW, "view", "视角朝向与锁定", ToolSchemas.VIEW_TOOLS),
                new EntityToolsProvider(GroupNames.INTERACT, "interact", "交互（攻击/使用/放置/破坏）", ToolSchemas.INTERACT_TOOLS),
                new EntityToolsProvider(GroupNames.ITEM, "item", "物品（双索引检索/容器/移动）", ToolSchemas.ITEM_TOOLS),
                new EntityToolsProvider(GroupNames.WORLD, "world", "世界区域查询（单一工具）", ToolSchemas.WORLD_TOOLS),
                new EntityToolsProvider(GroupNames.WATCH, "watch", "动态感知（含绑定实体，带控制模型编号）", ToolSchemas.WATCH_TOOLS),
                new EntityToolsProvider(GroupNames.CHAT, "chat", "聊天：注入未读 / 发言 / 检索（M12）", ToolSchemas.CHAT_TOOLS),
                new EntityToolsProvider(GroupNames.AVATAR, "avatar", "载体：绑定 / 定时唤醒（M13）", ToolSchemas.AVATAR_TOOLS),
                new EntityToolsProvider(GroupNames.CRAFT, "craft", "合成：配方发现 + 直合成（H4.6）", ToolSchemas.CRAFT_TOOLS)));
        return groups;
    }

    /** 默认挂全组（测试与全量场景）。 */
    public static List<EntityToolsProvider> skeletons() {
        return skeletons(true);
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
