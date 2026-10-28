package io.github.piwugu.snowleopard;

/**
 * 眼部表现的两个纯量（2026-10-28）：瞳孔默认下移、眼皮最小高度。
 *
 * <p>为什么单独放这里而不是写在 {@code SnowLeopardModel} 里：模型类是**客户端类**
 * （依赖 {@code ModelPart}/{@code ModelLayerLocation}），而真机断言跑在**服务端**
 * （{@code ClientSmokeTest} 挂在 PlayerJoin 上）。把规则写成不依赖客户端的纯函数，
 * 断言才能直接打它——否则这条规则永远只能靠肉眼，改坏了也无人报警。
 *
 * <p>坐标提醒：MC 模型 **y 向下为正**，所以"下移"是 {@code +0.2}，不是 {@code -0.2}。
 */
public final class AvatarEyes {

    private AvatarEyes() {
    }

    /** 瞳孔默认下移量（px）：bbmodel 里瞳孔与眼白同心，静态像"瞪视"，下移一点才像正常视线。 */
    public static final float PUPIL_REST_DOWN = 0.2F;

    /** 眼皮满高（px）：与眼白同高，眨眼时展开到它才算真的闭眼。 */
    public static final float LID_FULL_HEIGHT = 2.0F;

    /** 眼皮最小高度（px）：**常态也保留一条窄边**，不许缩到 0（用户指定 0.5）。 */
    public static final float LID_MIN_HEIGHT = 0.5F;

    /** 眼皮高度（px）→ {@code yScale}。下限钳在 {@link #LID_MIN_HEIGHT}，传 0 也只会得到 0.5px。 */
    public static float lidScale(float heightPx) {
        return clamp(heightPx, LID_MIN_HEIGHT, LID_FULL_HEIGHT) / LID_FULL_HEIGHT;
    }

    /** 眼皮实际高度（px）：{@code yScale} × 满高。 */
    public static float lidHeight(float scale) {
        return scale * LID_FULL_HEIGHT;
    }

    /**
     * 眼皮中心随缩放的上移量（px）：缩放绕 part pivot（盒子中心）进行，展开时会**从中间
     * 往两头长**，看着像眼睛在抽搐。加上这个偏移才能**固定上缘**（= 真实的眼皮动作）。
     */
    public static float lidYOffset(float scale) {
        return scale - 1.0F;
    }

    /** 瞳孔基准 y + 默认下移量（+y 才是下）。 */
    public static float pupilRestY(float baseY) {
        return baseY + PUPIL_REST_DOWN;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
