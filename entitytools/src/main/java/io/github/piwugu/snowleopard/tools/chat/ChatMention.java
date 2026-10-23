package io.github.piwugu.snowleopard.tools.chat;

import java.util.Locale;

/**
 * 提及匹配（M12-0，2026-10-24 00:50 (UTC+8)）。
 *
 * <p><b>两条通道的区别是代价</b>：被提到**编号或名称** → 给一条"提示"（一行字）；
 * 被提到**名称** → 才可能触发"唤醒"（跑整个回合）。所以这里把两种匹配分开，
 * 让调用方各取所需，而不是一个 {@code matches()} 混着用。
 *
 * <p><b>编号必须按"边界"匹配</b>：{@code m10a} 不能命中 {@code selftest-m10a}。
 * 编号里全是字母数字和 {@code -/_}，纯子串匹配会把"尾部相同"的模型全部误命中
 * （分析 §2.6 的裁决要求）。
 *
 * <p><b>名称按子串匹配</b>（中文没有词边界，只能子串），但名称长度 &lt; 2 个码点
 * **不能作为唤醒词**——"雪" 会在"下雪了"里误触。
 */
public final class ChatMention {

    /** 名称作为唤醒词的最小长度（按码点计，避免把 emoji 算成两个字）。 */
    public static final int MIN_WAKE_NAME = 2;

    private ChatMention() {
    }

    /** 编号是否被提及：大小写不敏感 + 左右不能紧邻标识符字符。 */
    public static boolean mentionsId(String text, String id) {
        if (text == null || text.isEmpty() || id == null || id.isEmpty()) {
            return false;
        }
        String hay = text.toLowerCase(Locale.ROOT);
        String needle = id.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = hay.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !isIdChar(hay.charAt(at - 1));
            int end = at + needle.length();
            boolean rightOk = end >= hay.length() || !isIdChar(hay.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    /** 名称是否被提及：大小写不敏感子串；名称不足 {@link #MIN_WAKE_NAME} 码点时返回 false。 */
    public static boolean mentionsName(String text, String name) {
        if (!isWakeName(name) || text == null || text.isEmpty()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }

    /** 这个名称够不够格当唤醒词（&ge; 2 码点）。 */
    public static boolean isWakeName(String name) {
        return name != null && !name.isBlank() && name.strip().codePointCount(0, name.strip().length()) >= MIN_WAKE_NAME;
    }

    private static boolean isIdChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
