package io.github.piwugu.snowleopard.tools;

/**
 * 视觉渲染后端契约（2026-10-27 06:10 (UTC+8)）。
 *
 * <p><b>为什么是接口 + 壳注入</b>：工具层只负责"受理申请"，真正的画面要么来自客户端离屏渲染、
 * 要么来自测试桩。**entitytools 不许反依赖 shell**，所以实现由壳经
 * {@code EntityToolsModule.Ctx.visionBackend()} 注入（同 {@link PickupCarrier} 先例）。
 *
 * <p><b>为什么必须异步</b>：渲染要在客户端渲染线程上跑一次 GPU 帧 + 一个网络往返。
 * 工具调用此刻卡在 {@code server.execute + latch} 上——**在服务端主线程等客户端是明确禁止的**
 * （会卡 tick）。所以这里只有"受理"，结果由实现方写盘后回调宿主注入。
 *
 * @see PickupCarrier
 */
public interface VisionBackend {

    /**
     * 一次截图申请。
     *
     * @param modelId 申请者（载体绑定模型的编号）
     * @param x,y,z   载体眼位（服务端权威坐标；实现方不要自己去猜）
     * @param yaw,pitch 视线朝向（度；**服务端下发的值**，不信任客户端同步值）
     */
    record Shot(String modelId, int width, int height,
                double x, double y, double z, float yaw, float pitch) {
    }

    /** 没有后端（未接入 / 不可用）。 */
    VisionBackend NONE = new VisionBackend() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean inFlight(String modelId) {
            return false;
        }

        @Override
        public String request(Shot shot) {
            throw new IllegalStateException("视觉渲染后端未接入");
        }

        @Override
        public void show(String modelId, java.nio.file.Path file) {
            throw new IllegalStateException("视觉渲染后端未接入");
        }
    };

    /** 后端是否可用（不可用时 {@code vision/capture} 必须当场报错并指路 {@code vision/scan}）。 */
    boolean available();

    /** 该模型是否有在飞申请（连发要拦，不能排成一队）。 */
    boolean inFlight(String modelId);

    /**
     * 异步受理：**立即返回申请编号**，图好了由实现方写盘并通知宿主注入。
     *
     * @throws IllegalStateException 受理就失败（没有可用客户端 / 预算用完）——必须抛，不许静默吞
     */
    String request(Shot shot);

    /**
     * 把**已落盘**的图重新排队注入（对应 {@code vision/show}）。
     * 不重新渲染、不耗 GPU——所以它比再拍一张便宜得多。
     */
    void show(String modelId, java.nio.file.Path file);
}
