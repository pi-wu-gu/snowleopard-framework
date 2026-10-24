package io.github.piwugu.snowleopard.tools;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * count 宽容解析（v1.10.0，2026-10-24 19:20 (UTC+8)）——drop/move_item/craft 三处共用的
 * 参数解析单点。真机实锤：模型按 schema 发字符串 {@code "count":"5"}，只认 Number 的旧写法
 * 静默整组丢（53 木头+18 门）。
 */
class CountParamsTest {

    private static Map<String, Object> with(Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put("count", v);
        return m;
    }

    @Test
    void numberJsonAccepted() {
        assertEquals(5, CountParams.parse(with(5), "count", 99));
        assertEquals(1, CountParams.parse(with(1), "count", 99));
    }

    @Test
    void numericStringAccepted() {
        assertEquals(5, CountParams.parse(with("5"), "count", 99));
        assertEquals(5, CountParams.parse(with(" 5 "), "count", 99), "带空白也收（模型输出常带）");
    }

    @Test
    void garbageAndNonPositiveFallBack() {
        assertEquals(99, CountParams.parse(with("abc"), "count", 99));
        assertEquals(99, CountParams.parse(with(""), "count", 99));
        assertEquals(99, CountParams.parse(with(0), "count", 99), "零回落缺省（旧 drop 会假成功 dropped=0）");
        assertEquals(99, CountParams.parse(with(-3), "count", 99));
    }

    @Test
    void missingKeyFallsBack() {
        assertEquals(7, CountParams.parse(new HashMap<>(), "count", 7));
        assertEquals(7, CountParams.parse(with(null), "count", 7));
    }
}
