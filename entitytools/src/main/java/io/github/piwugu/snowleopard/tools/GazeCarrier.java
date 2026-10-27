package io.github.piwugu.snowleopard.tools;

/**
 * 能"转头 + 转眼珠"的载体（2026-10-27 18:35 (UTC+8)）。
 *
 * <p>为什么是接口：视线计算在 entitytools（纯逻辑 + MC 派发），同步字段在 shell 的
 * {@code AvatarEntity}；依赖方向是 **shell → entitytools**，所以 entitytools 不能直接引用实体类。
 * 这与既有的 {@code PickupCarrier} 是同一手法——MC 层只判断"这个实体有没有眼睛可动"。
 *
 * <p>只有载体实现它；被绑定的普通生物（僵尸/猪…）不实现，于是"转头"对它们自动退化为不处理，
 * 不需要在派发处写类型判断。
 */
public interface GazeCarrier {

    /**
     * 写入本 tick 的视线表现量（服务端算，客户端渲染）。
     *
     * @param headYawRel   头相对身体偏航（度，已夹 ±{@code AvatarGaze.HEAD_YAW_LIMIT}）
     * @param headPitchRel 头相对身体俯仰（度）
     * @param pupilX       瞳孔水平平移（模型像素，已夹 ±0.2）
     * @param pupilY       瞳孔垂直平移（像素）
     */
    void applyGaze(float headYawRel, float headPitchRel, float pupilX, float pupilY);

    /** 头相对身体偏航（读回用：扫描射线要按"身体+头"的合成朝向算）。 */
    float headYawRel();

    /** 头相对身体俯仰。 */
    float headPitchRel();

    /** 瞳孔水平平移（像素）。 */
    float pupilX();

    /** 瞳孔垂直平移（像素）。 */
    float pupilY();
}
