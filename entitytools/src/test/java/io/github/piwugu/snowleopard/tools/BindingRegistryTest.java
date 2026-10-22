package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import snowleopard.core.result.Result;

/**
 * 绑定登记 1:1 契约测试（M1，2026-10-23 05:40 (UTC+8)）。
 */
class BindingRegistryTest {

    @Test
    void bindOneToOne() {
        BindingRegistry r = new BindingRegistry();
        assertTrue(r.bind("m1", "e1") instanceof Result.Ok);
        // 同模型再绑 → 拒
        assertTrue(r.bind("m1", "e2") instanceof Result.Error, "1:1：一模型一实体");
        // 他人实体被占 → 拒
        assertTrue(r.bind("m2", "e1") instanceof Result.Error, "1:1：一实体至一模型");
        assertTrue(r.bind("m2", "e2") instanceof Result.Ok);
        assertEquals("e1", r.entityOf("m1"));
        assertEquals("m2", r.modelOf("e2"));
    }

    @Test
    void unbindAndDrop() {
        BindingRegistry r = new BindingRegistry();
        r.bind("m1", "e1");
        assertTrue(r.unbind("m1") instanceof Result.Ok);
        assertEquals(null, r.entityOf("m1"));
        assertEquals(null, r.modelOf("e1"));
        assertTrue(r.unbind("m1") instanceof Result.Warn, "无绑定解绑=WARN 幂等");

        r.bind("m2", "e2");
        assertTrue(r.dropEntity("e2") instanceof Result.Ok, "死亡清理");
        assertEquals(null, r.modelOf("e2"));
        assertTrue(r.dropEntity("e2") instanceof Result.Warn);
    }

    @Test
    void bindNeedsBothKeys() {
        BindingRegistry r = new BindingRegistry();
        assertTrue(r.bind(null, "e1") instanceof Result.Error);
        assertTrue(r.bind("m1", "  ") instanceof Result.Error);
    }
}
