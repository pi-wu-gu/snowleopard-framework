package io.github.piwugu.snowleopard.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.IntSupplier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import io.github.piwugu.snowleopard.SnowLeopardMod;
import io.github.piwugu.snowleopard.client.vision.VisionPayloads;
import io.github.piwugu.snowleopard.tools.VisionBackend;
import io.github.piwugu.snowleopard.tools.VisionFiles;

/**
 * 真渲染后端（P4，2026-10-27 12:30 (UTC+8)）：服务端发包 → 客户端离屏抓帧 → 回执落盘。
 *
 * <p><b>谁来回图</b>：同维度、距载体 128 格内（≈8 区块）的**最近玩家**。
 * 这不是随便挑——**客户端只能渲染自己已加载的区块**，载体跑到没人跟着的地方，
 * 客户端根本没有那片地形，画出来是虚空。所以受理阶段就判定，没有合格客户端**当场报错并指路
 * {@code vision/scan}**（服务端射线任何距离都成立），绝不返回"空画面"。
 *
 * <p>只认**第一个到达的回执**：多客户端同时回图时，其余按"过期回执"丢弃（{@code modelOf} 已为空）。
 */
public final class ClientVisionBackend implements VisionBackend {

    /** 载体与玩家的最大容许距离（格）：超过就认为客户端没那片区块。 */
    static final double MAX_RENDER_DIST = 128.0;

    private static Function<String, Path> dirOf = m -> null;
    private static int keepImages = 20;

    private final IntSupplier maxPerSession;

    public ClientVisionBackend(Function<String, Path> dirOf, IntSupplier maxPerSession,
                               IntSupplier keepImages) {
        ClientVisionBackend.dirOf = dirOf;
        ClientVisionBackend.keepImages = keepImages.getAsInt();
        this.maxPerSession = maxPerSession;
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
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("服务器未就绪，暂时拍不了——改用 vision/scan");
        }
        ServerPlayer renderer = pick(server, shot);
        if (renderer == null) {
            throw new IllegalStateException("没有可渲染的客户端（需要同维度、载体 "
                    + (int) MAX_RENDER_DIST + " 格内、区块已加载）——改用 vision/scan（服务端射线任何距离都成立）");
        }
        String reqId = VisionPending.open(shot.modelId(), "pov", shot.width(), shot.height(),
                maxPerSession.getAsInt());
        PacketDistributor.sendToPlayer(renderer, new VisionPayloads.Request(
                reqId, shot.entityId(), shot.width(), shot.height(), shot.yaw(), shot.pitch()));
        SnowLeopardMod.LOG.info("[雪豹框架][视觉] 截图 {} 派给 {}（距载体 {} 格）",
                reqId, renderer.getName().getString(),
                Math.round(Math.sqrt(renderer.distanceToSqr(shot.x(), shot.y(), shot.z()))));
        return reqId;
    }

    @Override
    public void show(String modelId, Path file) {
        VisionPending.replay(modelId, file.toString());
    }

    /** 客户端回执（服务端主线程）。 */
    public static void onReply(VisionPayloads.Reply r) {
        String model = VisionPending.modelOf(r.reqId());
        if (model == null) {
            // 过期/重复回执：只认第一个到达的，其余丢弃（不报错，也不覆盖已就绪的图）
            SnowLeopardMod.LOG.debug("[雪豹框架][视觉] 丢弃过期回执 {}", r.reqId());
            return;
        }
        if (r.error() != null && !r.error().isBlank()) {
            // **失败必须留痕**：客户端回执带了原因却只写进 pending，服务端日志里只剩
            // "超时/失败"，排查时无法区分"没收到包"和"收到了但渲染不了"（v1.12.8 实证）
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 回执 {} 失败：{}", r.reqId(), r.error());
            VisionPending.failed(r.reqId(), r.error());
            return;
        }
        Path dir = dirOf.apply(model);
        if (dir == null) {
            VisionPending.failed(r.reqId(), "服务端没有可写的视觉目录");
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("cap-" + r.reqId() + ".png");
            Files.write(file, r.png());
            VisionFiles.prune(dir, keepImages);
            VisionPending.ready(r.reqId(), file.toString());
            SnowLeopardMod.LOG.info("[雪豹框架][视觉] 回执 {} 落盘 {}（{} B）",
                    r.reqId(), file.getFileName(), r.png() == null ? 0 : r.png().length);
        } catch (Exception e) {
            VisionPending.failed(r.reqId(), "写图失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] 回执落盘失败: {}", e.getMessage());
        }
    }

    /** 同维度 + 128 格内 + 最近的一个玩家。 */
    private static ServerPlayer pick(MinecraftServer server, Shot shot) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String dim = p.level().dimension().location().toString();
            if (shot.dimension() != null && !shot.dimension().isEmpty()
                    && !shot.dimension().equals(dim)) {
                continue;
            }
            double d = Math.sqrt(p.distanceToSqr(shot.x(), shot.y(), shot.z()));
            if (d > MAX_RENDER_DIST || d >= bestD) {
                continue;
            }
            // 区块必须真的加载：否则客户端渲染出来是虚空（"假画面"比没画面坏）
            if (!p.level().isLoaded(net.minecraft.core.BlockPos.containing(shot.x(), shot.y(), shot.z()))) {
                continue;
            }
            bestD = d;
            best = p;
        }
        return best;
    }
}
