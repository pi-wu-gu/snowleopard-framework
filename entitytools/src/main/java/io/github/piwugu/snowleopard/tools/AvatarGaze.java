package io.github.piwugu.snowleopard.tools;

/**
 * 视线渐进状态机（2026-10-27 18:30 (UTC+8)）——纯 Java，可单测；MC 层只负责把结果写进实体。
 *
 * <p>要解决的问题：原来"设定朝向"= 一帧 {@code setRotation}，于是头永远正、瞳孔永远居中（看起来像机器人），
 * 且大角度转向是瞬移。这里把朝向拆成三级——<b>瞳孔（平移）→ 头 → 身体</b>，各自限速，
 * 于是"眼睛先动、头随后追、眼睛回中、身体最后跟上"是速率差自然涌现的结果，
 * <b>不需要写"先瞳孔后头"的静态分配</b>（写了反而会在小角度时头不动、大角度时瞳孔永远居中）。
 *
 * <p>三条不变式：
 * <ol>
 *   <li><b>限速</b>：任何角度每 tick 只能走 {@code *_SLEW} 度，绝无瞬移（防抽搐的第一层）；</li>
 *   <li><b>最短弧</b>：所有差值走 {@link #wrap}，否则 179→-179 会绕 358° 长路（看起来像猛抽一圈）；</li>
 *   <li><b>身体让位</b>：移动时身体归行进方向，视线（头/瞳孔）才归注视目标——两者分开才不会互抢。</li>
 * </ol>
 */
public final class AvatarGaze {

    // ------------------------------------------------------------- 常量（客户端与服务端共用同一组数）

    /** 头相对身体的最大偏航（度）。猫能转约 ±90，但本模型脖子只有一段，再大就是"橡皮脖"。 */
    public static final double HEAD_YAW_LIMIT = 65;
    /** 头最大俯仰（度）。 */
    public static final double HEAD_PITCH_LIMIT = 35;
    /** 瞳孔能独立覆盖的角度——超过它头才开始动（"眼睛先动"的量纲）。 */
    public static final double EYE_ONLY_LIMIT = 15;
    /** 身体追视线的偏航速度（度/tick）= 120°/s。 */
    public static final double BODY_YAW_STEP = 6;
    /** 身体追视线的俯仰速度（度/tick）——比偏航慢，抬头/低头幅度小。 */
    public static final double BODY_PITCH_STEP = 3;
    /** 视线（含瞳孔）追目标的偏航速度（度/tick）。**必须大于 {@link #HEAD_SLEW}**，否则看不见瞳孔动。 */
    public static final double GAZE_SLEW = 12;
    /** 头追视线的偏航速度（度/tick）——比视线慢 = "头后动"。 */
    public static final double HEAD_SLEW = 8;
    /** 视线追目标的俯仰速度。 */
    public static final double GAZE_PITCH_SLEW = 8;
    /** 头追视线的俯仰速度。 */
    public static final double HEAD_PITCH_SLEW = 6;
    /** 瞳孔平移上限（像素）：眼白 2px、瞳孔 1px，理论 ±0.5，用 ±0.2 留边（超 ±0.35 侧视会露缝）。 */
    public static final double PUPIL_MAX_PX = 0.2;
    /** 瞳孔垂直平移上限（眼睛比宽扁）。 */
    public static final double PUPIL_MAX_PY = 0.15;
    /** 换目标后的最小驻留（tick）——200ms，防两个目标每 tick 互相顶替造成头左右摆。 */
    public static final int SWITCH_DWELL = 10;

    /** 目标来源（带优先级：数字大的可抢占）。 */
    public enum Source {
        /** 无指令：视线归身体朝向（自然回正）。 */
        IDLE(0),
        /** {@code look} 设定的持续目标。 */
        LOOK(1),
        /** 移动收束：视线跟行进方向。 */
        MOVE(2),
        /** 锁定（生物/方块）：最高，因为它是模型明确表达的"我在看它"。 */
        LOCK(3);

        private final int rank;

        Source(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    /**
     * 一 tick 的结果（世界角 → 渲染要用的相对量）。
     *
     * @param bodyYaw     本 tick 之后的**身体**偏航（调用方负责写进实体）
     * @param bodyPitch   本 tick 之后的**身体**俯仰
     * @param headYawRel  头相对身体（已夹 ±{@link #HEAD_YAW_LIMIT}），给客户端渲染
     * @param headPitchRel 头相对身体俯仰（夹 ±{@link #HEAD_PITCH_LIMIT}）
     * @param pupilX      瞳孔水平平移（像素，夹 ±{@link #PUPIL_MAX_PX}）
     * @param pupilY      瞳孔垂直平移（像素）
     * @param source      本 tick 实际生效的来源（被迟滞留在旧源时会与请求的不同）
     */
    public record Pose(double bodyYaw, double bodyPitch,
                       double headYawRel, double headPitchRel,
                       double pupilX, double pupilY, Source source) {
    }

    // ------------------------------------------------------------- 状态

    private double gazeYaw;
    private double gazePitch;
    private double headWorldYaw;
    private double headWorldPitch;
    private Source source = Source.IDLE;
    private int dwell;
    private boolean primed;

    public Source source() {
        return source;
    }

    /** 重置到"正对当前身体朝向"（解锁/换绑/重生时调，避免头歪着不动）。 */
    public void reset(double bodyYaw, double bodyPitch) {
        gazeYaw = bodyYaw;
        gazePitch = bodyPitch;
        headWorldYaw = bodyYaw;
        headWorldPitch = bodyPitch;
        source = Source.IDLE;
        dwell = 0;
        primed = false;
    }

    public boolean primed() {
        return primed;
    }

    /**
     * 推进一 tick。
     *
     * @param want        本 tick 请求的来源
     * @param targetYaw   该来源的注视目标（世界角，度）
     * @param targetPitch 同上
     * @param bodyYaw     当前身体偏航
     * @param bodyPitch   当前身体俯仰
     * @param moving      是否正在移动（身体归行进方向，视线不抢身体）
     * @param moveYaw     行进方向的偏航（{@code moving=false} 时忽略）
     */
    public Pose tick(Source want, double targetYaw, double targetPitch,
                     double bodyYaw, double bodyPitch, boolean moving, double moveYaw) {
        // ① 源切换：高优先级可立即抢占；同级/降级要过驻留期（防多点切换抽搐的第一层）
        if (!primed) {
            source = want;
            gazeYaw = bodyYaw;
            gazePitch = bodyPitch;
            headWorldYaw = bodyYaw;
            headWorldPitch = bodyPitch;
            primed = true;
        } else if (want != source) {
            if (want.rank() > source.rank() || dwell >= SWITCH_DWELL) {
                source = want;
                dwell = 0;
            } else {
                // 驻留未满且不是抢占：本 tick 仍按旧源走（旧目标即上次的 target，靠 gaze 角保持）
                dwell++;
            }
        } else {
            dwell++;
        }

        // ② 视线 → 头：各自限速，最短弧
        if (source != Source.IDLE) {
            gazeYaw = stepToward(gazeYaw, targetYaw, GAZE_SLEW);
            gazePitch = stepToward(gazePitch, targetPitch, GAZE_PITCH_SLEW);
        } else {
            // 无指令：视线缓慢归正（不瞬移回中，否则解开锁定会"弹"一下）
            gazeYaw = stepToward(gazeYaw, bodyYaw, GAZE_SLEW);
            gazePitch = stepToward(gazePitch, bodyPitch, GAZE_PITCH_SLEW);
        }
        headWorldYaw = stepToward(headWorldYaw, gazeYaw, HEAD_SLEW);
        headWorldPitch = stepToward(headWorldPitch, gazePitch, HEAD_PITCH_SLEW);

        // ③ 身体：移动时归行进方向（视线不抢）；否则只在头到限位后才慢慢跟上
        double newBodyYaw = bodyYaw;
        double newBodyPitch = bodyPitch;
        if (moving) {
            newBodyYaw = stepToward(bodyYaw, moveYaw, BODY_YAW_STEP);
        } else if (Math.abs(wrap(headWorldYaw - bodyYaw)) > HEAD_YAW_LIMIT) {
            newBodyYaw = stepToward(bodyYaw, headWorldYaw, BODY_YAW_STEP);
        }
        if (!moving && Math.abs(headWorldPitch - bodyPitch) > HEAD_PITCH_LIMIT) {
            newBodyPitch = stepTowardPitch(bodyPitch, headWorldPitch, BODY_PITCH_STEP);
        }

        // ④ 派生：头相对身体 + 瞳孔吃"头实际没覆盖的部分"
        double headYawRel = clamp(wrap(headWorldYaw - newBodyYaw), -HEAD_YAW_LIMIT, HEAD_YAW_LIMIT);
        double headPitchRel = clamp(headWorldPitch - newBodyPitch, -HEAD_PITCH_LIMIT, HEAD_PITCH_LIMIT);
        double actualHeadYaw = newBodyYaw + headYawRel;
        double actualHeadPitch = newBodyPitch + headPitchRel;
        double residualYaw = wrap(gazeYaw - actualHeadYaw);
        double residualPitch = gazePitch - actualHeadPitch;
        double pupilX = clamp(residualYaw / EYE_ONLY_LIMIT * PUPIL_MAX_PX, -PUPIL_MAX_PX, PUPIL_MAX_PX);
        double pupilY = clamp(residualPitch / EYE_ONLY_LIMIT * PUPIL_MAX_PY, -PUPIL_MAX_PY, PUPIL_MAX_PY);

        return new Pose(newBodyYaw, newBodyPitch, headYawRel, headPitchRel, pupilX, pupilY, source);
    }

    // ------------------------------------------------------------- 纯函数（单测直接打这些）

    /** 角度归一化到 {@code (-180, 180]}——所有差值必须先过它，否则会绕远路。 */
    public static double wrap(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** 从 {@code from} 朝 {@code to} 走最多 {@code maxStep}（走最短弧）。 */
    public static double stepToward(double from, double to, double maxStep) {
        double d = wrap(to - from);
        if (Math.abs(d) <= maxStep) {
            return wrap(from + d);
        }
        return wrap(from + Math.signum(d) * maxStep);
    }

    /** 俯仰专用（不环绕，范围 -90..90）。 */
    public static double stepTowardPitch(double from, double to, double maxStep) {
        double d = clamp(to, -90, 90) - from;
        if (Math.abs(d) <= maxStep) {
            return clamp(from + d, -90, 90);
        }
        return clamp(from + Math.signum(d) * maxStep, -90, 90);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 瞳孔水平平移：把"视线领先头"的角度映射成像素（夹边）。 */
    public static double pupilX(double residualYawDeg) {
        return clamp(residualYawDeg / EYE_ONLY_LIMIT * PUPIL_MAX_PX, -PUPIL_MAX_PX, PUPIL_MAX_PX);
    }

    /** 瞳孔垂直平移。 */
    public static double pupilY(double residualPitchDeg) {
        return clamp(residualPitchDeg / EYE_ONLY_LIMIT * PUPIL_MAX_PY, -PUPIL_MAX_PY, PUPIL_MAX_PY);
    }
}
