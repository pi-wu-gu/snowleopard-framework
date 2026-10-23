package io.github.piwugu.snowleopard.tools;

import java.util.Map;

import snowleopard.core.result.Result;

/**
 * 移动状态机（M4，2026-10-23 14:10 (UTC+8)）——三驱动并存（裁决 05:10），纯 Java 可测。
 *
 * <p>判定口径（Friday Mover 真机实证）：<b>DONE=连续 2 tick 贴住目标</b>（防服务端回拉假成功）；
 * <b>阻塞=单 tick 水平位移 &lt; 0.02 连续 12 tick</b>（必须水平位移——3D 会把垂直漂移误判成进展）；
 * 超时=maxTicks 报因。方向基准：<b>开始时的朝向</b>解析成固定绝对向量（之后不随转身变）。
 */
public final class MoveMachine {

    /**
     * 阻塞判定抖动带（度量=单 tick 水平位移）。原口径 0.02 会在实体卡墙滑移（0.00~0.05 之间
     * 抖动）下假阴性——2026-10-23 真机校准为 0.05：真移动 0.15+/tick，滑移抖动 <0.05。
     */
    public static final double BLOCKED_EPS = 0.05;
    public static final int BLOCKED_TICKS = 12;
    public static final int DONE_STICKY_TICKS = 2;
    public static final double ARRIVE_EPS = 0.35;

    public enum Outcome { NONE, PROGRESS, DONE, BLOCKED, TIMEOUT }

    public enum Mode { TIME, DISTANCE, GOTO }

    private Mode mode;
    private Outcome outcome = Outcome.NONE;
    private double dirX, dirZ;             // 固定绝对方向（TIME/DISTANCE）
    private double targetX, targetY, targetZ;
    private double timeLeft;
    private double distanceLeft;
    private int maxTicks;
    private int age;
    private int stuck;
    private int sticky;

    public Outcome outcome() {
        return outcome;
    }

    public Mode mode() {
        return mode;
    }

    public boolean busy() {
        return outcome == Outcome.PROGRESS;
    }

    /** 固定方向向量（TIME/DISTANCE 模式，执行侧驱动用）。 */
    public synchronized double[] direction() {
        return new double[]{dirX, dirZ};
    }

    /** 目标点（GOTO 模式）。 */
    public synchronized double[] target() {
        return new double[]{targetX, targetY, targetZ};
    }

    /** 时长/距离驱动（三驱动之二）：direction=forward|back|left|right（相对开始朝向）。 */
    public synchronized Result startMove(String direction, Double durationTicks, Double distance, double yawDeg) {
        if (busy()) {
            return Result.warn(Map.of("outcome", outcome.name()), "已有移动进行中——先 stop");
        }
        boolean hasTime = durationTicks != null;
        boolean hasDist = distance != null;
        if (hasTime == hasDist) {
            return Result.error("move 需要 duration_ticks 或 distance 二选一（三驱动：时长|距离|goto）");
        }
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        switch (direction == null ? "" : direction.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> { dirX = fx; dirZ = fz; }
            case "back" -> { dirX = -fx; dirZ = -fz; }
            case "left" -> { dirX = fz; dirZ = -fx; }
            case "right" -> { dirX = -fz; dirZ = fx; }
            default -> {
                return Result.error("direction 需为 forward|back|left|right");
            }
        }
        mode = hasTime ? Mode.TIME : Mode.DISTANCE;
        timeLeft = hasTime ? Math.max(1, durationTicks) : 0;
        distanceLeft = hasDist ? Math.max(0.1, distance) : 0;
        maxTicks = Integer.MAX_VALUE;
        reset();
        return Result.ok(Map.of("mode", mode.name(), "direction", direction));
    }

    /** 目标驱动（goto）。不可达预检在 MC 层（高差/流体/未加载）——这里只管监督。 */
    public synchronized Result startGoto(double x, double y, double z, int maxTicks) {
        if (busy()) {
            return Result.warn(Map.of("outcome", outcome.name()), "已有移动进行中——先 stop");
        }
        mode = Mode.GOTO;
        targetX = x;
        targetY = y;
        targetZ = z;
        this.maxTicks = Math.max(1, maxTicks);
        reset();
        return Result.ok(Map.of("mode", mode.name(), "target", x + "," + y + "," + z,
                "maxTicks", this.maxTicks));
    }

    public synchronized Result stop() {
        if (!busy()) {
            return Result.warn(Map.of("outcome", outcome.name()), "无移动进行中");
        }
        outcome = Outcome.TIMEOUT;   // 中断=人为终止（status 可见）
        return Result.ok(Map.of("stopped", true));
    }

    /**
     * 每 tick 评估。moved=本 tick <b>水平</b>位移；arrived=已贴住目标（GOTO）。
     * 优先级：贴住/驱动完成 → 阻塞 → 超时。
     */
    public synchronized Outcome tick(double moved, boolean arrived) {
        if (!busy()) {
            return outcome;
        }
        age++;
        sticky = arrived ? sticky + 1 : 0;
        switch (mode) {
            case GOTO -> {
                if (sticky >= DONE_STICKY_TICKS) {
                    return finish(Outcome.DONE);
                }
            }
            case DISTANCE -> {
                distanceLeft -= Math.max(0, moved);
                if (distanceLeft <= 0) {
                    return finish(Outcome.DONE);
                }
            }
            case TIME -> {
                timeLeft--;
                if (timeLeft <= 0) {
                    return finish(Outcome.DONE);
                }
            }
            default -> {
            }
        }
        stuck = moved < BLOCKED_EPS ? stuck + 1 : 0;
        if (stuck >= BLOCKED_TICKS) {
            return finish(Outcome.BLOCKED);
        }
        if (mode == Mode.GOTO && age >= maxTicks) {
            return finish(Outcome.TIMEOUT);
        }
        return outcome;
    }

    /** 距目标水平距离（GOTO 模式）。 */
    public synchronized double horizontalDistanceToTarget(double x, double z) {
        return Math.sqrt((x - targetX) * (x - targetX) + (z - targetZ) * (z - targetZ));
    }

    public synchronized Map<String, Object> describe(double x, double y, double z) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("mode", mode == null ? "-" : mode.name());
        d.put("outcome", outcome.name());
        d.put("age", age);
        if (mode == Mode.GOTO) {
            d.put("target", targetX + "," + targetY + "," + targetZ);
            d.put("distToTarget", horizontalDistanceToTarget(x, z));
        }
        return d;
    }

    private void reset() {
        outcome = Outcome.PROGRESS;
        age = 0;
        stuck = 0;
        sticky = 0;
    }

    private Outcome finish(Outcome o) {
        outcome = o;
        return o;
    }
}
