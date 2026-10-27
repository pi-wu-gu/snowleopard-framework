package io.github.piwugu.snowleopard.client.vision;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 视觉截图的两个包（2026-10-27 12:20 (UTC+8)）。
 *
 * <p>请求：服务端 → 客户端（带 reqId / 载体实体 id / 目标宽高 / 朝向）。
 * 回执：客户端 → 服务端（带 reqId / 实际宽高 / PNG 字节 / 失败原因）。
 *
 * <p>体积上限是 {@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 1048576}（1 MiB）；
 * 384×216 的真画面 PNG 约 80–200 KB，安全。
 */
public final class VisionPayloads {

    private VisionPayloads() {
    }

    /** 服务端 → 客户端：请把相机临时放到这个实体上拍一张。 */
    public record Request(String reqId, int entityId, int width, int height,
                          float yaw, float pitch) implements CustomPacketPayload {

        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("snowleopard", "vision_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, Request p) -> {
                    buf.writeUtf(p.reqId);
                    buf.writeVarInt(p.entityId);
                    buf.writeVarInt(p.width);
                    buf.writeVarInt(p.height);
                    buf.writeFloat(p.yaw);
                    buf.writeFloat(p.pitch);
                },
                buf -> new Request(buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readFloat(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：拍好了（或失败）。 */
    public record Reply(String reqId, int width, int height,
                        byte[] png, String error) implements CustomPacketPayload {

        public static final Type<Reply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("snowleopard", "vision_reply"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, Reply p) -> {
                    buf.writeUtf(p.reqId);
                    buf.writeVarInt(p.width);
                    buf.writeVarInt(p.height);
                    buf.writeByteArray(p.png == null ? new byte[0] : p.png);
                    buf.writeUtf(p.error == null ? "" : p.error);
                },
                buf -> new Reply(buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                        buf.readByteArray(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** mod 总线注册（**两侧都要注册**，否则通道对不上）。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(Request.TYPE, Request.CODEC, VisionPayloads::onRequest);
        r.playToServer(Reply.TYPE, Reply.CODEC, VisionPayloads::onReply);
    }

    /**
     * 请求包只会在客户端收到。**先判 dist 再调客户端类**——专用服务器上
     * 这个分支永不执行，客户端类不会被解析。
     */
    private static void onRequest(Request req, IPayloadContext ctx) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return;
        }
        ctx.enqueueWork(() -> io.github.piwugu.snowleopard.client.vision.PovCapture.onRequest(req));
    }

    private static void onReply(Reply reply, IPayloadContext ctx) {
        ctx.enqueueWork(() -> io.github.piwugu.snowleopard.host.ClientVisionBackend.onReply(reply));
    }
}
