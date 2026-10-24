package io.github.piwugu.snowleopard;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import snowleopard.core.SnowLeopard;
import snowleopard.core.builtin.BuiltinGroups;
import snowleopard.core.result.Result;

/**
 * 内置四组的壳侧接线（2026-10-23 20:05 (UTC+8)）——把 {@code builtin:core:*} 的调用
 * 转发到对应模型 ID 的 {@link SnowLeopard.CallerScope}。
 *
 * <p><b>为什么壳里只需要这一层</b>：清单（schema）由核心的 {@code BuiltinGroups} 在
 * {@code bootstrap} 时注册进注册表（注入面/查询面白拿）；壳要补的只有"执行面的人是谁"——
 * 也就是<b>把模型编号映射到它自己的家</b>（红线 3：ID 文件夹=模型的家，跨 ID 一律禁止）。
 *
 * <p><b>不缓存能力对象、只缓存句柄</b>：{@code CallerScope} 是文件视图的持有者，
 * 重建不丢数据；{@code forget(model)} 只丢句柄（会话结束），盘上产物照旧在。
 *
 * <p><b>核心未装配必须显式报错</b>：{@code SnowLeopardMod.core() == null} 时返回 ERROR，
 * 不静默降级成"没有这个工具"——否则模型会把"能力缺失"误读成"用法不对"。
 */
public final class BuiltinRuntime {

    /** 模型编号 → 它的家（首次调用时建；句柄缓存，非能力缓存）。 */
    private static final Map<String, SnowLeopard.CallerScope> SCOPES = new ConcurrentHashMap<>();

    private BuiltinRuntime() {
    }

    /** 该组名是否属于内置四组（壳侧统一入口据此分流到核心执行面）。 */
    public static boolean isBuiltin(String group) {
        return group != null && BuiltinGroups.GROUP_NAMES.contains(group);
    }

    /** 取（或建）模型的家；核心未装配返回 {@code null}（调用方须报错，不许猜）。 */
    public static SnowLeopard.CallerScope scope(String modelId) {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null || modelId == null || modelId.isBlank()) {
            return null;
        }
        return SCOPES.computeIfAbsent(modelId, core::forCaller);
    }

    /** 模型的家目录（自测/诊断核对盘上路径用）。 */
    public static Path home(String modelId) {
        SnowLeopard.CallerScope sc = scope(modelId);
        return sc == null ? null : sc.home();
    }

    /**
     * 统一分发：{@code builtin:core:<group>} + 工具名 + 参数 → 核心执行面。
     *
     * <p>权限先过核心权限表（组全名 {@code builtin:core:<group>}）——与实体组同一道门，
     * 内置组不因"自带"而免检。
     */
    public static Result dispatch(String modelId, String group, String tool,
                                  Map<String, Object> params) {
        if (modelId == null || modelId.isBlank()) {
            return Result.error("内置工具需要调用方模型编号（core 注入，不可伪造）");
        }
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null) {
            return Result.error("核心未装配——内置四组不可用（见启动日志的装配失败原因）");
        }
        if (core.permissions() != null
                && !core.permissions().table().allows("builtin:core:" + group, tool)) {
            return Result.error("权限拒绝: builtin:core:" + group + "." + tool + "（权限文件可改判）");
        }
        SnowLeopard.CallerScope sc = scope(modelId);
        return sc.tools().call(group, tool, params);
    }

    /** 注入面：内置四组的组简介（"每轮进上下文"的那份，不含工具详解）。 */
    public static List<String> briefs() {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null) {
            return List.of();
        }
        return core.registry().briefs().stream()
                .filter(line -> line.contains("builtin:core:"))
                .toList();
    }

    /** 会话结束丢掉句柄（盘上数据不动——存储类是文件视图，重建即恢复）。 */
    public static void forget(String modelId) {
        if (modelId != null) {
            SCOPES.remove(modelId);
        }
    }

    // **这里以前有个 knownCallers()（句柄缓存的 keySet），已被移除**（2026-10-24 05:45 (UTC+8)）：
    // 三处消费（提及候选/推送巡检/游标 ensure）都拿它当"已知模型"，可它只反映"谁调用过内置工具"，
    // 于是没调过的模型永不在册、@它唤不醒。已知模型的唯一来源是 ModelRegistry——
    // 句柄缓存是执行面副产品，**不许再拿它当注册表**（同一职责两份实现必然漂移，且不报错）。
}
