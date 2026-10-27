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
 * 受限核心界面（P1，分析 v3 §3.2）——本仓第一套 GUI。
 *
 * <p>未绑定核心右键 → 列出已有家 + "创建新家"输入框。
 * 每个家一个"绑定"按钮（消耗 1 核心）；底部输入框 + "创建"按钮（免费）。
 *
 * <p>提交时发 {@code CorePayloads.Action}，服务端做四道校验。
 * 界面是表单、不是授权——所有校验在服务端。
 */
public class CoreScreen extends Screen {

    private final List<String> homes;
    private final String nonce;
    private EditBox input;

    public CoreScreen(List<String> homes, String nonce) {
        super(Component.literal("受限核心"));
        this.homes = homes;
        this.nonce = nonce;
    }

    /** 由 CorePayloads.onOpenScreen 调用。 */
    public static void open(CorePayloads.OpenScreen pkt) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new CoreScreen(pkt.homes(), pkt.nonce()));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 60;

        // 已有家列表（每行一个"绑定"按钮）
        for (String home : homes) {
            if (y > this.height - 80) {
                break;   // 超出屏幕不再画
            }
            this.addRenderableWidget(Button.builder(
                            Component.literal("绑定 " + home),
                            b -> sendAction("BIND", home))
                    .bounds(cx + 60, y, 80, 20)
                    .build());
            y += 24;
        }

        // 创建新家
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

        // 关闭
        this.addRenderableWidget(Button.builder(
                        Component.literal("关闭"),
                        b -> this.onClose())
                .bounds(cx - 30, this.height - 30, 60, 20)
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
        // 标题
        g.drawCenteredString(this.font, "受限核心 — 家管理", cx, 20, 0xFFFFFF);
        // 说明
        g.drawCenteredString(this.font, "绑定：消耗 1 核心，获得该家钥匙 | 创建：免费，建空家", cx, 36, 0xAAAAAA);

        // 家列表标题
        if (!homes.isEmpty()) {
            g.drawString(this.font, "已有家：", cx - 100, 50, 0xCCCCCC);
        } else {
            g.drawCenteredString(this.font, "（暂无家，先创建一个）", cx, 50, 0x888888);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
