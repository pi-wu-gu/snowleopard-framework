package io.github.piwugu.snowleopard.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 受限核心界面网络包（P1，分析 v3 §3.2）。
 *
 * <p>{@code OpenScreen}（服务端→客户端）：右键核心时发，带已有家列表 + nonce。
 * {@code Action}（客户端→服务端）：界面提交 CREATE / BIND，带 homeId + nonce。
 * {@code Result}（服务端→客户端）：操作结果（成功/失败 + 消息）。
 *
 * <p><b>nonce</b> = 手持物品的 {@code home} 值（未绑定=""）。防界面过期：
 * 打开界面后物品被绑定 → nonce 不匹配 → 服务端拒绝。
 */
public final class CorePayloads {

    private CorePayloads() {
    }

    // ---------- 服务端 → 客户端：打开界面 ----------

    /** 家列表 + 哪些家已有载体 + nonce（防界面过期）。 */
    public record OpenScreen(List<String> homes, List<String> withAvatar, String nonce)
            implements CustomPacketPayload {

        public static final Type<OpenScreen> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("snowleopard", "core_open_screen"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, OpenScreen p) -> {
                    buf.writeVarInt(p.homes.size());
                    for (String h : p.homes) {
                        buf.writeUtf(h);
                    }
                    buf.writeVarInt(p.withAvatar.size());
                    for (String h : p.withAvatar) {
                        buf.writeUtf(h);
                    }
                    buf.writeUtf(p.nonce == null ? "" : p.nonce);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<String> homes = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        homes.add(buf.readUtf());
                    }
                    int m = buf.readVarInt();
                    List<String> withAvatar = new java.util.ArrayList<>(m);
                    for (int i = 0; i < m; i++) {
                        withAvatar.add(buf.readUtf());
                    }
                    return new OpenScreen(homes, withAvatar, buf.readUtf());
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------- 客户端 → 服务端：操作请求 ----------

    /** action: "CREATE" 或 "BIND"。 */
    public record Action(String action, String homeId, String nonce) implements CustomPacketPayload {

        public static final Type<Action> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("snowleopard", "core_action"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, Action p) -> {
                    buf.writeUtf(p.action);
                    buf.writeUtf(p.homeId);
                    buf.writeUtf(p.nonce == null ? "" : p.nonce);
                },
                buf -> new Action(buf.readUtf(), buf.readUtf(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------- 服务端 → 客户端：操作结果 ----------

    public record Result(boolean success, String message) implements CustomPacketPayload {

        public static final Type<Result> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("snowleopard", "core_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, Result p) -> {
                    buf.writeBoolean(p.success);
                    buf.writeUtf(p.message == null ? "" : p.message);
                },
                buf -> new Result(buf.readBoolean(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------- 注册（两侧都要） ----------

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(OpenScreen.TYPE, OpenScreen.CODEC, CorePayloads::onOpenScreen);
        r.playToServer(Action.TYPE, Action.CODEC, CorePayloads::onAction);
        r.playToClient(Result.TYPE, Result.CODEC, CorePayloads::onResult);
    }

    private static void onOpenScreen(OpenScreen pkt, IPayloadContext ctx) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return;
        }
        io.github.piwugu.snowleopard.SnowLeopardMod.LOG.info(
                "[雪豹框架][核心] 客户端收到 OpenScreen（{} 个家）", pkt.homes().size());
        ctx.enqueueWork(() -> io.github.piwugu.snowleopard.client.CoreScreen.open(pkt));
    }

    private static void onAction(Action pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> io.github.piwugu.snowleopard.home.CoreActionHandler.handle(pkt, ctx));
    }

    private static void onResult(Result pkt, IPayloadContext ctx) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return;
        }
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                (pkt.success() ? "§a" : "§c") + "[受限核心] " + pkt.message()),
                        true);
            }
        });
    }
}
