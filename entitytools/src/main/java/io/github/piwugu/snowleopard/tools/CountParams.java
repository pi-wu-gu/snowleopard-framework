package io.github.piwugu.snowleopard.tools;

import java.util.Map;

/**
 * count 宽容解析（v1.10.0，2026-10-24 19:20 (UTC+8)）——drop/move_item/craft 三处共用的
 * 参数解析单点，<b>纯 Java 零 MC 依赖</b>（headless 可测；住 mc 包会让单测类加载拖出
 * NoClassDefFoundError）。
 *
 * <p><b>为什么必须宽容</b>：schema 里所有参数都是 string 型，模型常发 {@code "count":"5"}——
 * 只认 Number 会静默落到缺省值。craft 在 v1.9.4 先修（12:10 真机断言抓到：count=2 实合 1 次），
 * drop/move_item 漏修，17:52 真机再实锤：玩家要 8 木头+5 门，模型整组丢出 53+18。
 * <b>同一职责多实现必漂移</b>——现在收口成这一个函数，谁再手写 count 解析谁错。
 *
 * <p>Number（模型发数字 JSON）与可解析字符串都收；解析不动/零/负回落 {@code dflt}
 * （由调用方的夹取与校验兜底）。
 */
public final class CountParams {

    private CountParams() {
    }

    /** 见类注。key 缺失/为 null/解析不动/非正数 → {@code dflt}。 */
    public static int parse(Map<String, Object> params, String key, int dflt) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            int i = n.intValue();
            return i > 0 ? i : dflt;
        }
        if (v != null) {
            try {
                int i = Integer.parseInt(String.valueOf(v).trim());
                return i > 0 ? i : dflt;
            } catch (NumberFormatException ignored) {
                // 保持 dflt
            }
        }
        return dflt;
    }
}
