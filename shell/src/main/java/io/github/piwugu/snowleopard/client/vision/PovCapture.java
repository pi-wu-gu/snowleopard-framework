package io.github.piwugu.snowleopard.client.vision;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import io.github.piwugu.snowleopard.SnowLeopardMod;
import io.github.piwugu.snowleopard.tools.PngWriter;

/**
 * 客户端侧截图（P4，2026-10-27 12:25 (UTC+8)）。
 *
 * <p><b>做法：临时把相机实体换成载体，抓一帧主缓冲，立刻还原。</b>
 * 这样光照/雾/实体/天气/区块批次**全走原版**，不用手搓 {@code RenderTarget + Camera + renderLevel}
 * 那套矩阵（那套每次大版本都可能坏，而且盲写极易黑屏）。
 *
 * <p><b>代价**：玩家视角会闪**一帧**（这一帧里他看到的是载体所见）。这是刻意接受的：
 * 一帧 ≈ 16ms，肉眼几乎不可见；换来的是渲染管线完全不用自己维护。
 *
 * <p><b>硬限制**：客户端只能渲染自己已加载的区块。载体跑远时这里拿不到实体——
 * 服务端在受理阶段就会拒绝并指路 {@code vision/scan}（那条不依赖客户端）。
 */
@EventBusSubscriber(modid = "snowleopard", value = Dist.CLIENT)
public final class PovCapture {

    /** 待拍（一次只允许一张；服务端已拦连发，这里再兜一次）。 */
    private static VisionPayloads.Request pending;
    private static Entity target;
    private static long armedAt;

    private PovCapture() {
    }

    /** 收到请求：换相机，等下一帧 AFTER_LEVEL 抓图。 */
    public static void onRequest(VisionPayloads.Request req) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 拒绝 {}：客户端尚未进入世界", req.reqId());
            reply(req, null, "客户端尚未进入世界");
            return;
        }
        if (pending != null) {
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 拒绝 {}：上一张仍在渲染", req.reqId());
            reply(req, null, "上一张仍在渲染");
            return;
        }
        // **实体可能还没到**（v1.12.8 真机实证）：玩家刚 join 时客户端还在收区块/实体包，
        // 这时 getEntity 查不到。立刻拒绝是不对的——服务端明明判定过"距载体 3 格、区块已加载"，
        // 只是客户端的同步晚了几十几百毫秒。所以挂起等它出现，由 onRenderStage 每帧重试。
        Entity e = level.getEntity(req.entityId());
        pending = req;
        target = e;
        armedAt = System.currentTimeMillis();
        if (e != null) {
            mc.setCameraEntity(e);   // 下一帧相机就是载体视角
        } else {
            SnowLeopardMod.LOG.info("[雪豹框架][视觉] 请求 {} 已挂起：实体 {} 尚未同步到客户端，等它出现",
                    req.reqId(), req.entityId());
        }
    }

    /** 每帧末尾：相机确实是载体时抓一帧，然后立刻还原。 */
    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (pending == null) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (System.currentTimeMillis() - armedAt > 3000) {
            // 三秒还没等到相机切换（实体没同步 / 被别的模组抢了相机）——如实失败，别把玩家视角一直挂着
            VisionPayloads.Request req = pending;
            boolean noEntity = target == null;
            pending = null;
            target = null;
            mc.setCameraEntity(mc.player);
            reply(req, null, noEntity
                    ? "载体 3 秒内未出现在本客户端（区块/实体尚未同步，或载体离客户端太远）"
                    : "相机切换超时（可能被其它模组占用）");
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 截图超时 {}：{}", req.reqId(),
                    noEntity ? "实体始终未同步" : "相机未切过来");
            return;
        }
        if (target == null) {
            // 挂起态：每帧重试解析实体（玩家刚 join / 区块还在传时会晚到几百毫秒）
            Entity late = mc.level == null ? null : mc.level.getEntity(pending.entityId());
            if (late == null) {
                return;
            }
            target = late;
            mc.setCameraEntity(late);
            SnowLeopardMod.LOG.info("[雪豹框架][视觉] 实体 {} 已同步，开始截图 {}", late.getId(), pending.reqId());
            return;                  // 下一帧再抓（相机要一帧才生效）
        }
        if (mc.getCameraEntity() != target) {
            return;                  // 本帧相机还没换过来，等下一帧
        }
        VisionPayloads.Request req = pending;
        pending = null;
        NativeImage img = null;
        // **相机跟随视线**（v1.14.0，方案 A）：相机取的是实体 yRot/xRot（= 身体），而头可以领先
        // 身体 65°，不补偿就会出现"模型说在看它、照片里是旁边"的自相矛盾。
        // 这里抓帧前临时把身体角补成"身体+头"，抓完立刻还原（客户端本地字段，不发包）。
        float savedYaw = target.getYRot();
        float savedPitch = target.getXRot();
        if (target instanceof io.github.piwugu.snowleopard.AvatarEntity av) {
            target.setYRot(savedYaw + av.headYawRel());
            target.setXRot(net.minecraft.util.Mth.clamp(savedPitch + av.headPitchRel(), -90.0F, 90.0F));
        }
        try {
            img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            byte[] png = downscale(img, req.width(), req.height());
            reply(req, png, null);
            SnowLeopardMod.LOG.info("[雪豹框架][视觉] 客户端出图 {} → {}x{}（{} B）",
                    req.reqId(), req.width(), req.height(), png.length);
        } catch (Throwable t) {
            reply(req, null, "渲染失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 客户端出图失败: {}", t.toString());
        } finally {
            if (img != null) {
                img.close();
            }
            target.setYRot(savedYaw);        // 先还原朝向，再摘相机
            target.setXRot(savedPitch);
            target = null;
            mc.setCameraEntity(mc.player);   // **立刻还原**：玩家最多闪一帧
        }
    }

    /**
     * 主缓冲 → 目标尺寸（区域平均，等比采样）。
     *
     * <p>我们抓的是整个窗口（可能 854×480），而请求要 384×216——不缩放会让包变大、
     * 也让"请求分辨率"这个配置变成摆设。
     */
    static byte[] downscale(NativeImage src, int dstW, int dstH) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int w = Math.max(1, Math.min(dstW, sw));
        int h = Math.max(1, Math.min(dstH, sh));
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = (int) ((long) y * sh / h);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / h));
            for (int x = 0; x < w; x++) {
                int x0 = (int) ((long) x * sw / w);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / w));
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int sy = y0; sy < y1 && sy < sh; sy++) {
                    for (int sx = x0; sx < x1 && sx < sw; sx++) {
                        int argb = src.getPixelRGBA(sx, sy);   // 注意：NativeImage 是 ABGR 打包
                        r += argb & 0xFF;
                        g += (argb >> 8) & 0xFF;
                        b += (argb >> 16) & 0xFF;
                        n++;
                    }
                }
                if (n == 0) {
                    n = 1;
                }
                px[y * w + x] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
            }
        }
        return PngWriter.rgb(w, h, px);
    }

    private static void reply(VisionPayloads.Request req, byte[] png, String error) {
        try {
            int w = req.width();
            int h = req.height();
            if (png != null && png.length == 0) {
                png = null;
            }
            PacketDistributor.sendToServer(new VisionPayloads.Reply(req.reqId(), w, h,
                    png == null ? new byte[0] : png, error == null ? "" : error));
        } catch (Exception e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 回执发送失败: {}", e.getMessage());
        }
    }

    /** 供自测：当前是否有在飞请求。 */
    public static boolean busy() {
        return pending != null;
    }

    /** 载体眼睛位置（调试用，便于日志核对朝向）。 */
    static Vec3 eyeOf(Entity e) {
        return e.getEyePosition(1.0f);
    }
}
