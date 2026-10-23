package io.github.piwugu.snowleopard.tools;

import snowleopard.core.result.Result;

/**
 * 挖掘任务（M3，2026-10-23 13:25 (UTC+8)）——破坏是<b>持续动作</b>，纯 Java 可测。
 *
 * <p>tick 驱动推进：PROGRESS→READY（到时长，MC 层执行 removeBlock）→COMPLETED（方块真没了
 * ——完成判定以服务端事实为准）；超时=TIMEOUT（超时报因）。方块被他人提前挖掉=COMPLETED
 * （目标达成），不造假失败。
 */
public final class MiningTask {

    public enum Outcome { NONE, PROGRESS, READY, COMPLETED, TIMEOUT }

    private String target;
    private int requiredTicks;
    private int maxTicks;
    private int elapsed;
    private Outcome outcome = Outcome.NONE;

    public Outcome outcome() {
        return outcome;
    }

    public String target() {
        return target;
    }

    public int elapsed() {
        return elapsed;
    }

    public int requiredTicks() {
        return requiredTicks;
    }

    /** 开工。requiredTicks=方块硬度推算；maxTicks=预算（硬方块超预算→TIMEOUT 报因）。 */
    public Result start(String target, int requiredTicks, int maxTicks) {
        if (target == null || target.isBlank()) {
            return Result.error("break 需要 target（x,y,z）");
        }
        if (isBusy()) {
            return Result.warn(java.util.Map.of("target", this.target),
                    "已有挖掘进行中——先完成或等待超时");
        }
        this.target = target;
        this.requiredTicks = Math.max(1, requiredTicks);
        this.maxTicks = Math.max(1, maxTicks);
        this.elapsed = 0;
        this.outcome = Outcome.PROGRESS;
        return Result.ok(java.util.Map.of("target", target, "requiredTicks", this.requiredTicks,
                "maxTicks", this.maxTicks));
    }

    /**
     * 推进一 tick。blockGone=目标方块已不存在（服务端事实）。
     * 返回当前 outcome：READY=本 tick 起 MC 层可执行移除。
     */
    public synchronized Outcome tick(boolean blockGone) {
        if (!isBusy()) {
            return outcome;
        }
        if (blockGone) {
            outcome = Outcome.COMPLETED;
            return outcome;
        }
        elapsed++;
        if (elapsed >= requiredTicks) {
            outcome = Outcome.READY;   // 到时长优先于超时（二者同 tick 时算挖完）
            return outcome;
        }
        if (elapsed >= maxTicks) {
            outcome = Outcome.TIMEOUT;
            return outcome;
        }
        return outcome;   // PROGRESS
    }

    public synchronized void abort() {
        if (isBusy()) {
            outcome = Outcome.TIMEOUT;
        }
    }

    public boolean isBusy() {
        return outcome == Outcome.PROGRESS || outcome == Outcome.READY;
    }
}
