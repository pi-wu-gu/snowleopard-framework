package io.github.piwugu.snowleopard;

import java.util.List;

import snowleopard.core.SnowLeopard;
import snowleopard.core.builtin.TurnSummary;
import snowleopard.core.result.Result;
import snowleopard.core.wake.LockFileRuntime;
import snowleopard.core.wake.WakeStore;

/**
 * 协作面的壳侧接线（M5，2026-10-23 22:30 (UTC+8)）。
 *
 * <p><b>只接两件宿主才有能力做的事</b>：
 * <ol>
 *   <li><b>运行态权威</b>——{@link LockFileRuntime}（进程内表 + {@code shared/run/<id>.lock} 双保险），
 *       由 {@code bootstrap} 的 {@code Options.withRuntime} 注入核心，核心的 {@link WakeStore} 与壳共用同一实例；</li>
 *   <li><b>待执行请求的可见性</b>——{@code pending()} 给诊断/自测用（谁被唤起了、什么原因）。</li>
 * </ol>
 *
 * <p><b>刻意不做"自动执行"</b>：框架壳里没有 LLM 会话驱动，真唤起必须由外部宿主（星期五那一层）完成。
 * 如果壳假装执行并回写 {@code done}，就是在报告里说谎；因此这里只提供
 * {@link #rejectUnrunnable}（明确失败 + 原因），不提供"假装成功"的路径。
 */
public final class WakeBridge {

    private static volatile LockFileRuntime runtime;

    private WakeBridge() {
    }

    /** 由 {@code SnowLeopardMod} 在装配时注入（与核心共用的同一实例）。 */
    static void install(LockFileRuntime rt) {
        runtime = rt;
    }

    /** 运行态（可能为 null：核心装配失败时）。 */
    public static LockFileRuntime runtime() {
        return runtime;
    }

    /** 标记某模型开始一次会话（抢到才返回 true；已被别人占用返回 false）。 */
    public static boolean beginSession(String modelId) {
        LockFileRuntime rt = runtime;
        return rt != null && rt.tryAcquire(modelId);
    }

    /** 会话结束（幂等）。 */
    public static void endSession(String modelId) {
        LockFileRuntime rt = runtime;
        if (rt != null) {
            rt.release(modelId);
        }
    }

    /** 待执行请求（诊断/自测用；真正执行属于外部宿主）。 */
    public static List<WakeStore.Request> pending() {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null) {
            return List.of();
        }
        return core.wake().pending();
    }

    /**
     * 明确拒绝一条无法在此环境执行的请求（带原因）——<b>不伪装成功</b>。
     * 外部宿主接手时应调用 {@code WakeStore.complete(reqId, true, ...)} 而不是本方法。
     */
    public static Result rejectUnrunnable(String reqId, String reason) {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null) {
            return Result.error("核心未装配——无法回写唤醒请求");
        }
        return core.wake().complete(reqId, false, reason);
    }

    /** 轮末汇总一行（无内容返回 null = 沉默）。 */
    public static String turnLine(String modelId) {
        var scope = BuiltinRuntime.scope(modelId);
        if (scope == null) {
            return null;
        }
        String base = TurnSummary.line(modelId, scope.groups(), scope.wake(),
                scope.memory(), java.time.LocalDate.now());
        // 聊天段在核心之外（core 不认识 MC 聊天），所以在这里追加——
        // 全仓只有这一处拼摘要，别处不许自己拼（"同一注入点统一装配"）
        String chat = ChatRuntime.turnSegment(modelId);
        if (chat == null) {
            return base;
        }
        return base == null ? "提醒：" + chat : base + " · " + chat;
    }

    /** 注入后记账（提示一次）：只标已提示，不动已读游标。 */
    /**
     * 数 pending 请求（自测用）。
     *
     * @param fromPrefix 发起方前缀（"any" = 不限；外部触发是 ext-clock / ext-player-* / ext-mention-*）
     * @param toModel    目标模型（"any" = 不限）
     */
    public static long pendingCount(String fromPrefix, String toModel) {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null) {
            return 0;
        }
        long n = 0;
        for (var r : core.wake().pending()) {
            boolean fromOk = "any".equals(fromPrefix) || r.from().startsWith(fromPrefix);
            boolean toOk = "any".equals(toModel) || r.to().equals(toModel);
            if (fromOk && toOk) {
                n++;
            }
        }
        return n;
    }

    public static void consumeTurnLine(String modelId) {
        var scope = BuiltinRuntime.scope(modelId);
        if (scope != null) {
            TurnSummary.consume(modelId, scope.groups(), scope.wake());
        }
        // 只清"被提及"（提示一次）；"新聊天 N 条"要等真被 pull+ack 才消失
        ChatRuntime.consume(modelId);
    }
}
