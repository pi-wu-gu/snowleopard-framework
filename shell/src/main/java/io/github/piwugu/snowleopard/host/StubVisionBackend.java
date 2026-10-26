package io.github.piwugu.snowleopard.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.IntSupplier;

import io.github.piwugu.snowleopard.SnowLeopardMod;
import io.github.piwugu.snowleopard.tools.PngWriter;
import io.github.piwugu.snowleopard.tools.VisionBackend;
import io.github.piwugu.snowleopard.tools.VisionFiles;

/**
 * 视觉测试桩后端（P3，2026-10-27 06:20 (UTC+8)）。
 *
 * <p><b>它存在的唯一理由</b>：让"受理 → 回执 → 注入 → 冲突"整条链路**不依赖客户端渲染**就能验收。
 * 真渲染（P4）是最不确定的一块，不该拿它当整条线的入场券。
 *
 * <p><b>必须能被识别为假</b>：产出图的边角是品红框 + 对角线，且 {@code source=stub}、
 * 注入文案带"测试桩"——否则它会变成最隐蔽的"假画面"（模型以为看到的是真实世界）。
 * 另外只许客户端验收模式使用，生产配置按不可用处理。
 */
public final class StubVisionBackend implements VisionBackend {

    private final Function<String, Path> dirOf;
    private final IntSupplier maxPerSession;
    private final IntSupplier keepImages;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snowleopard-vision-stub");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param dirOf         模型 → 视觉产物目录（壳侧已算好，家目录内不跨 ID）
     * @param maxPerSession 每会话截图预算
     * @param keepImages    只保留最近 N 张
     */
    public StubVisionBackend(Function<String, Path> dirOf, IntSupplier maxPerSession,
                             IntSupplier keepImages) {
        this.dirOf = dirOf;
        this.maxPerSession = maxPerSession;
        this.keepImages = keepImages;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean inFlight(String modelId) {
        return VisionPending.inFlight(modelId);
    }

    @Override
    public String request(Shot shot) {
        String reqId = VisionPending.open(shot.modelId(), "stub", shot.width(), shot.height(),
                maxPerSession.getAsInt());
        pool.submit(() -> {
            try {
                Thread.sleep(150);      // 模拟一次离屏渲染的耗时（真后端是异步的，这里不能同步返回图）
                Path dir = dirOf.apply(shot.modelId());
                if (dir == null) {
                    VisionPending.failed(reqId, "没有可写的视觉目录");
                    return;
                }
                Files.createDirectories(dir);
                Path file = dir.resolve("cap-" + reqId + ".png");
                Files.write(file, stubPng(shot.width(), shot.height()));
                int removed = VisionFiles.prune(dir, keepImages.getAsInt());
                VisionPending.ready(reqId, file.toString());
                SnowLeopardMod.LOG.info("[雪豹框架][视觉] 测试桩出图 {}（{}x{}，清理旧图 {} 张）",
                        file.getFileName(), shot.width(), shot.height(), removed);
            } catch (Exception e) {
                VisionPending.failed(reqId, "测试桩渲染失败: " + e.getClass().getSimpleName()
                        + " " + e.getMessage());
                SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 测试桩出图失败: {}", e.getMessage());
            }
        });
        return reqId;
    }

    @Override
    public void show(String modelId, Path file) {
        VisionPending.replay(modelId, file.toString());
    }

    /**
     * 生成"一眼能看出是假的"图：品红边框 + 45° 斜纹 + 中央色块。
     * **绝不能画得像真实场景**——那正是把测试桩变成"假画面"的方式。
     */
    static byte[] stubPng(int w, int h) {
        int ww = Math.max(16, w);
        int hh = Math.max(9, h);
        int[] px = new int[ww * hh];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < ww; x++) {
                boolean border = x < 2 || y < 2 || x >= ww - 2 || y >= hh - 2;
                boolean stripe = ((x + y) / 6) % 2 == 0;
                int c;
                if (border) {
                    c = 0xFF00FF;                       // 品红边框：最醒目的"这不是画面"
                } else if (Math.abs(x - ww / 2) < ww / 8 && Math.abs(y - hh / 2) < hh / 6) {
                    c = 0x202020;                       // 中央深块：给"有个东西"的锚点
                } else {
                    c = stripe ? 0x606060 : 0x404040;   // 斜纹灰底
                }
                px[y * ww + x] = c;
            }
        }
        return PngWriter.rgb(ww, hh, px);
    }
}
