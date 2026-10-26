package io.github.piwugu.snowleopard.host;

/**
 * 视觉能力分级——**单点计算，唯一来源**（2026-10-27 06:00 (UTC+8)）。
 *
 * <p>为什么必须单点：能力级会出现在四个地方（系统提示词、工具结果、{@code view/status}、
 * 降级警示）。各算一遍必漂移，而漂移的表现是"提示词说能看、工具说不能看"——
 * 模型会挑一个信，然后基于错的那半做决定。所以只在这里算，别处一律引用。
 *
 * <table>
 *   <caption>四级</caption>
 *   <tr><th>级</th><th>条件</th><th>模型能拿到</th></tr>
 *   <tr><td>L0</td><td>视觉模型可用 ∧ 可渲染</td><td>真图进上下文</td></tr>
 *   <tr><td>L1</td><td>视觉模型不可用 ∧ 可渲染</td><td>图落盘 + 文字描述</td></tr>
 *   <tr><td>L2</td><td>视觉模型可用 ∧ 不可渲染</td><td>只有射线描述</td></tr>
 *   <tr><td>L3</td><td>两者都不可用</td><td>只有射线描述</td></tr>
 * </table>
 *
 * @param stub 渲染后端是测试桩（画面是假的）——能力级仍是 L0，但**必须**被标注出来
 */
public record VisionCapability(Level level, String provider, boolean vlm, boolean render,
                               boolean stub, String reason) {

    public enum Level {
        L0, L1, L2, L3
    }

    /**
     * @param renderAvailable 渲染后端是否真的能出图（P1 阶段只有 stub；{@code client} 未接入按 false）
     * @param stub            {@code backend=stub}
     * @param renderReason    不可渲染的具体原因（会原样给模型看，必须是人话）
     */
    public static VisionCapability compute(LlmClient client, boolean renderAvailable, boolean stub,
                                           String renderReason) {
        boolean vlm = client != null && client.canVision();
        String provider = client == null ? "" : client.visionProviderName();
        if (vlm && renderAvailable) {
            return new VisionCapability(Level.L0, provider, true, true, stub,
                    stub ? "渲染后端是测试桩：画面不是真实世界" : "");
        }
        if (!vlm && renderAvailable) {
            return new VisionCapability(Level.L1, provider, false, true, stub,
                    "视觉模型不可用（没有声明 multimodal 的 provider）");
        }
        if (vlm && !renderAvailable) {
            return new VisionCapability(Level.L2, provider, true, false, false,
                    renderReason == null ? "渲染后端未接入" : renderReason);
        }
        return new VisionCapability(Level.L3, provider, false, false, false,
                "视觉模型与渲染后端都不可用");
    }

    /** **只有 L0 才能真正看图**（L1 的图落盘了但模型看不了）。 */
    public boolean canSeeImages() {
        return vlm && render;
    }

    /** 系统提示词里的一行——只陈述能力与禁令，**不点名尚未注册的工具**（否则模型会去调不存在的东西）。 */
    public String promptLine() {
        if (canSeeImages()) {
            return "- 视觉：" + level + "，可查看截图（provider=" + provider + "）"
                    + (stub ? "。**注意：当前是测试桩，画面不是真实世界，只用于验证链路**" : "") + "。\n";
        }
        return "- 视觉：" + level + "，**你没有查看图像的能力**（" + reason + "）。\n"
                + "  因此**禁止**使用「我看到 / 图上显示 / 画面里」这类表述；需要空间信息时用 world_query / sense / "
                + "view 自行推断，并**明确说明这是推断**。\n";
    }

    /** 工具结果与 status 用的紧凑串（模型可读、日志可 grep）。 */
    public String compact() {
        return "level=" + level + " provider=" + (provider.isEmpty() ? "-" : provider)
                + " vlm=" + vlm + " render=" + render + (stub ? " stub=true" : "")
                + (reason.isEmpty() ? "" : " reason=" + reason);
    }

    /** 能力变化时注入的一行警示（只在与上次不同的时候发，不重复刷）。 */
    public String changeLine(String prevLevel) {
        String head = "注意：视觉能力在本会话中从 " + prevLevel + " 变为 " + level
                + "（" + (reason.isEmpty() ? "恢复" : reason) + "）——";
        return canSeeImages()
                ? head + "我现在可以查看截图了。"
                : head + "我现在**看不了图**，之前的画面只有文字描述，不要声称看到画面。";
    }
}
