package io.github.piwugu.snowleopard.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.network.PacketDistributor;

import io.github.piwugu.snowleopard.network.CorePayloads;

/**
 * 受限核心界面（P1/P5，分析 v3 §3.2/§3.6）——本仓第一套 GUI。
 *
 * <p><b>未绑定核心</b>（homeId=""）→ 家管理界面：已有家列表 + "创建新家"输入框。
 * <p><b>已绑定核心</b>（homeId=某 ID）→ 配置界面：家信息 + 传送按钮。
 *
 * <p>提交时发 {@code CorePayloads.Action}，服务端做四道校验。
 * 界面是表单、不是授权——所有校验在服务端。
 */
public class CoreScreen extends Screen {

    private final List<String> homes;
    private final String nonce;
    private final String homeId;   // 空=未绑定；非空=已绑定
    private EditBox input;

    public CoreScreen(List<String> homes, String nonce, String homeId) {
        super(Component.literal("受限核心"));
        this.homes = homes;
        this.nonce = nonce;
        this.homeId = homeId == null ? "" : homeId;
    }

    /** 由 CorePayloads.onOpenScreen 调用。 */
    public static void open(CorePayloads.OpenScreen pkt) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new CoreScreen(pkt.homes(), pkt.nonce(), pkt.homeId()));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        if (!homeId.isEmpty()) {
            initBound(cx);
        } else {
            initUnbound(cx);
        }

        // 关闭
        this.addRenderableWidget(Button.builder(
                        Component.literal("关闭"),
                        b -> this.onClose())
                .bounds(cx - 30, this.height - 30, 60, 20)
                .build());
    }

    /** 未绑定：家管理（创建/绑定）。 */
    private void initUnbound(int cx) {
        int y = 60;
        for (String home : homes) {
            if (y > this.height - 80) {
                break;
            }
            this.addRenderableWidget(Button.builder(
                            Component.literal("绑定 " + home),
                            b -> sendAction("BIND", home))
                    .bounds(cx + 60, y, 80, 20)
                    .build());
            y += 24;
        }
        y += 12;
        input = new EditBox(this.font, cx - 100, y, 160, 20, Component.literal("新家 ID"));
        input.setMaxLength(32);
        input.setHint(Component.literal("输入新家 ID（2-32 字符）"));
        this.addRenderableWidget(input);
        this.addRenderableWidget(Button.builder(
                        Component.literal("创建"),
                        b -> sendAction("CREATE", input.getValue().trim()))
                .bounds(cx + 70, y, 60, 20)
                .build());
    }

    /** 已绑定：配置 + 传送 + 解绑。 */
    private void initBound(int cx) {
        int y = 70;
        // 传送按钮（P5）
        this.addRenderableWidget(Button.builder(
                        Component.literal("§6传送载体到身边"),
                        b -> sendAction("TELEPORT", homeId))
                .bounds(cx - 60, y, 120, 24)
                .build());
        y += 32;
        // 解绑按钮（丢失钥匙恢复路径）
        this.addRenderableWidget(Button.builder(
                        Component.literal("§c解绑（归还未绑定核心）"),
                        b -> sendAction("UNBIND", homeId))
                .bounds(cx - 60, y, 120, 24)
                .build());
    }

    private void sendAction(String action, String homeId) {
        if (homeId == null || homeId.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new CorePayloads.Action(action, homeId, nonce));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        if (!homeId.isEmpty()) {
            g.drawCenteredString(this.font, "受限核心 — " + homeId, cx, 20, 0xFFFFFF);
            g.drawCenteredString(this.font, "这枚核心是 " + homeId + " 的钥匙", cx, 36, 0xAAAAAA);
            g.drawCenteredString(this.font, "传送：消耗载体经验，按距离计费", cx, 52, 0x888888);
        } else {
            g.drawCenteredString(this.font, "受限核心 — 家管理", cx, 20, 0xFFFFFF);
            g.drawCenteredString(this.font, "绑定：消耗 1 核心，获得该家钥匙 | 创建：免费，建空家", cx, 36, 0xAAAAAA);
            if (!homes.isEmpty()) {
                g.drawString(this.font, "已有家：", cx - 100, 50, 0xCCCCCC);
            } else {
                g.drawCenteredString(this.font, "（暂无家，先创建一个）", cx, 50, 0x888888);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
