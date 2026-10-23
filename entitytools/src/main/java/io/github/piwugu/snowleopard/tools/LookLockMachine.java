package io.github.piwugu.snowleopard.tools;

import java.util.Map;

import snowleopard.core.result.Result;

/**
 * 视角锁定状态机（M2，2026-10-23 12:50 (UTC+8)）——纯 Java，六条结束路径的可执行规格。
 *
 * <p>结束路径（FridayLock 实证口径，<b>每条上报原因</b>）：显式解锁 / 方块消失 / 实体消失 /
 * 超距 {@value #TOO_FAR_DISTANCE} / 超时 / 被外力夺控。评估顺序=targetPresent→distance→timeout→drift。
 * MC 层每 tick 供输入（世界事实），状态语义只住这里（同一职责单实现）。
 */
public final class LookLockMachine {

    /** 超距阈值（方块）——FridayLock 同款 64。 */
    public static final double TOO_FAR_DISTANCE = 64.0;
    /** 外力夺控判定角差（度）：实际朝向偏离本机上次写入超过它=被夺控。 */
    public static final double DRIFT_EPS_DEG = 1.0;

    public enum End {
        EXPLICIT_UNLOCK, BLOCK_GONE, ENTITY_GONE, TOO_FAR, TIMEOUT, EXTERNAL_CONTROL
    }

    public enum Kind { BLOCK, ENTITY }

    public enum State { IDLE, LOCKED }

    private State state = State.IDLE;
    private Kind kind;
    private String target;
    private int durationTicks;
    private int age;
    private End lastEnd;

    public State state() {
        return state;
    }

    public boolean locked() {
        return state == State.LOCKED;
    }

    public Kind kind() {
        return kind;
    }

    public String target() {
        return target;
    }

    public int age() {
        return age;
    }

    /** 上次结束原因（未结束过为 null）。 */
    public End lastEnd() {
        return lastEnd;
    }

    /** 上锁。durationTicks<=0=不限时（仍受其余五条约束）。 */
    public synchronized Result start(Kind kind, String target, int durationTicks) {
        if (kind == null || target == null || target.isBlank()) {
            return Result.error("look_at 需要目标类型与目标");
        }
        if (state == State.LOCKED) {
            return Result.warn(Map.of("prev", String.valueOf(target)),
                    "已有锁定——先 unlock（旧锁定按 EXPLICIT_UNLOCK 结束）");
        }
        this.kind = kind;
        this.target = target;
        this.durationTicks = Math.max(0, durationTicks);
        this.age = 0;
        this.state = State.LOCKED;
        return Result.ok(Map.of("locked", true, "kind", kind.name(), "target", target,
                "durationTicks", this.durationTicks));
    }

    /** 显式解锁（路径 1）。未锁定=WARN 幂等。 */
    public synchronized Result unlock() {
        if (state != State.LOCKED) {
            return Result.warn(Map.of("locked", false), "当前未锁定");
        }
        state = State.IDLE;
        lastEnd = End.EXPLICIT_UNLOCK;
        return Result.ok(Map.of("locked", false, "reason", lastEnd.name()));
    }

    /**
     * 每 tick 评估。targetPresent/distance/drifted 由 MC 层读世界事实注入。
     * 返回 Ok(data.locked)：true=仍锁定，false=本 tick 结束（data.reason 为六条之一）。
     */
    public synchronized Result tick(boolean targetPresent, double distance, boolean drifted) {
        if (state != State.LOCKED) {
            return Result.warn(Map.of("locked", false), "当前未锁定");
        }
        age++;
        End end = null;
        if (!targetPresent) {
            end = kind == Kind.BLOCK ? End.BLOCK_GONE : End.ENTITY_GONE;
        } else if (distance > TOO_FAR_DISTANCE) {
            end = End.TOO_FAR;
        } else if (durationTicks > 0 && age >= durationTicks) {
            end = End.TIMEOUT;
        } else if (drifted) {
            end = End.EXTERNAL_CONTROL;
        }
        if (end != null) {
            state = State.IDLE;
            lastEnd = end;
            return Result.ok(Map.of("locked", false, "reason", end.name(), "age", age));
        }
        return Result.ok(Map.of("locked", true, "age", age));
    }
}
