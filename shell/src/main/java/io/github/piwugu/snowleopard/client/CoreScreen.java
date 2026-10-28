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
 * 受限核心界面（P1/P5，2026-10-28 语义修正）。
 *
 * <p><b>只有一种状态</b>：核心用完即消耗，不存在"已绑定的钥匙核心"。
 * 界面列出全部家 ID，每行按状态给动作：
 * <ul>
 *   <li>该 ID <b>还没有载体</b> → 「生成载体（-1 核心）」；</li>
 *   <li>该 ID <b>已有载体</b> → 「传送到身边」+「释放（退核心）」。</li>
 * </ul>
 *
 * <p>绑定语义（用户 2026-10-28 明确）：**绑定 = 生成载体并消耗核心**，一个 ID 已存在载体则失败；
 * 不是"把核心绑到某只实体上"。传送 = 选 ID，有主载体则叫过来，没有则失败。
 *
 * <p>界面是表单、不是授权——所有校验在服务端。
 */
public class CoreScreen extends Screen {

    private final List<String> homes;
    private final List<String> withAvatar;
    private final String nonce;
    private EditBox input;

    public CoreScreen(List<String> homes, List<String> withAvatar, String nonce) {
        super(Component.literal("受限核心"));
        this.homes = homes == null ? List.of() : homes;
        this.withAvatar = withAvatar == null ? List.of() : withAvatar;
        this.nonce = nonce == null ? "" : nonce;
    }

    /** 由 CorePayloads.onOpenScreen 调用。 */
    public static void open(CorePayloads.OpenScreen pkt) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new CoreScreen(pkt.homes(), pkt.withAvatar(), pkt.nonce()));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = cx - 150;
        int y = 56;

        for (String home : homes) {
            if (y > this.height - 96) {
                break;
            }
            final String id = home;
            boolean hasAvatar = withAvatar.contains(home);
            if (hasAvatar) {
                this.addRenderableWidget(Button.builder(
                                Component.literal("§6传送"),
                                b -> sendAction("TELEPORT", id))
                        .bounds(cx + 60, y, 56, 20)
                        .build());
                this.addRenderableWidget(Button.builder(
                                Component.literal("§c释放"),
                                b -> sendAction("UNBIND", id))
                        .bounds(cx + 120, y, 56, 20)
                        .build());
            } else {
                this.addRenderableWidget(Button.builder(
                                Component.literal("生成载体（-1 核心）"),
                                b -> sendAction("BIND", id))
                        .bounds(cx + 60, y, 116, 20)
                        .build());
            }
            y += 24;
        }
        if (homes.isEmpty()) {
            y += 16;
        }

        y = Math.max(y + 12, this.height - 76);
        input = new EditBox(this.font, left, y, 200, 20, Component.literal("新家 ID"));
        input.setMaxLength(32);
        input.setHint(Component.literal("输入新家 ID（2-32 字符，字母/数字/下划线/连字符）"));
        this.addRenderableWidget(input);
        this.addRenderableWidget(Button.builder(
                        Component.literal("创建空家"),
                        b -> sendAction("CREATE", input.getValue().trim()))
                .bounds(left + 210, y, 90, 20)
                .build());

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
        int left = cx - 150;
        g.drawCenteredString(this.font, "受限核心", cx, 18, 0xFFFFFF);
        g.drawCenteredString(this.font,
                "已有载体的家：传送 / 释放　·　没有载体的家：生成载体（消耗 1 核心，一 ID 一个载体）",
                cx, 34, 0xAAAAAA);

        if (homes.isEmpty()) {
            g.drawCenteredString(this.font, "（还没有家，先在下方创建一个）", cx, 66, 0x888888);
            return;
        }
        int y = 56;
        for (String home : homes) {
            if (y > this.height - 96) {
                break;
            }
            boolean hasAvatar = withAvatar.contains(home);
            String status = hasAvatar ? "§a有载体" : "§7无载体";
            g.drawString(this.font, home, left, y + 6, 0xFFFFFF);
            g.drawString(this.font, status, left + 110, y + 6, 0xCCCCCC);
            y += 24;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
