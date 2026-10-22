package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import snowleopard.core.result.Result;

/**
 * sense 状态机契约测试（M0，2026-10-23 05:30 (UTC+8)）——裁决 05:10 语义的可执行规格。
 */
class SenseMachineTest {

    @Test
    void openCloseCycle() {
        SenseMachine m = new SenseMachine();
        assertEquals(SenseState.OFF, m.state());
        assertFalse(m.toolsEnabled(), "OFF 态控制工具必须禁用");

        Result r = m.open();
        assertTrue(r instanceof Result.Ok, String.valueOf(r));
        assertEquals(SenseState.AWAITING_PICK, m.state());
        assertFalse(m.toolsEnabled(), "AWAITING 态控制工具仍禁用（选取完成才启用）");

        Result c = m.close();
        assertTrue(c instanceof Result.Ok, String.valueOf(c));
        assertEquals(SenseState.OFF, m.state());
    }

    @Test
    void pickOnlyInAwaiting() {
        SenseMachine m = new SenseMachine();
        Result early = m.pick("e1");
        assertTrue(early instanceof Result.Error, "未开启就 pick 必须显式拒绝: " + early);

        m.open();
        Result p = m.pick("e1");
        assertTrue(p instanceof Result.Ok, String.valueOf(p));
        assertEquals(SenseState.BOUND, m.state());
        assertTrue(m.toolsEnabled(), "BOUND 态控制工具可用");
        assertEquals("e1", m.boundEntity());

        Result again = m.pick("e2");
        assertTrue(again instanceof Result.Error, "已绑定再 pick 必须拒绝（防 1:1 被破）: " + again);
    }

    @Test
    void deathAutoCloses() {
        SenseMachine m = new SenseMachine();
        m.open();
        m.pick("e1");

        Result d = m.death("e1");
        assertTrue(d instanceof Result.Ok, String.valueOf(d));
        assertEquals(SenseState.OFF, m.state(), "裁决：实体死亡→感官自动关闭");
        assertFalse(m.toolsEnabled(), "死亡后控制工具必须禁用");

        m.open();
        m.pick("e2");
        Result unrelated = m.death("other");
        assertTrue(unrelated instanceof Result.Warn, "无关死亡=状态不动、WARN 说明: " + unrelated);
        assertEquals(SenseState.BOUND, m.state());
    }

    @Test
    void rebindViaReopen() {
        SenseMachine m = new SenseMachine();
        m.open();
        m.pick("e1");
        Result reopen = m.open();
        assertTrue(reopen instanceof Result.Warn, "BOUND 上 open=重新选取，应 WARN 提示解绑: " + reopen);
        assertEquals(SenseState.AWAITING_PICK, m.state());
        assertFalse(m.toolsEnabled());
        m.pick("e2");
        assertEquals("e2", m.boundEntity(), "重新选取后绑定新实体");
    }

    @Test
    void idempotentWarnings() {
        SenseMachine m = new SenseMachine();
        assertTrue(m.close() instanceof Result.Warn, "重复 close=WARN 不是错误");
        m.open();
        assertTrue(m.open() instanceof Result.Warn, "重复 open=WARN");
        assertTrue(m.death("nope") instanceof Result.Warn);
    }

    @Test
    void pickNeedsRef() {
        SenseMachine m = new SenseMachine();
        m.open();
        assertTrue(m.pick(null) instanceof Result.Error);
        assertTrue(m.pick("  ") instanceof Result.Error);
    }
}
