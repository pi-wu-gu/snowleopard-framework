package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import snowleopard.core.result.Result;

/**
 * 移动判定口径（M4，2026-10-23 14:10 (UTC+8)）——Friday Mover 实证规则的可执行规格。
 */
class MoveMachineTest {

    @Test
    void gotoDoneNeedsTwoTickSticky() {
        MoveMachine m = new MoveMachine();
        assertTrue(m.startGoto(10, 64, 10, 100) instanceof Result.Ok);
        assertEquals(MoveMachine.Outcome.PROGRESS, m.tick(0.5, true), "第 1 次贴住不算");
        assertEquals(MoveMachine.Outcome.DONE, m.tick(0.0, true), "连续 2 tick 贴住=Done");
    }

    @Test
    void blockedNeeds12TickNoProgress() {
        MoveMachine m = new MoveMachine();
        m.startGoto(10, 64, 10, 1000);
        for (int i = 0; i < 11; i++) {
            assertEquals(MoveMachine.Outcome.PROGRESS, m.tick(0.01, false), "第 " + i + " tick");
        }
        assertEquals(MoveMachine.Outcome.BLOCKED, m.tick(0.0, false), "水平位移<0.02 连续 12 tick=阻塞");
    }

    @Test
    void progressResetsStuck() {
        MoveMachine m = new MoveMachine();
        m.startGoto(10, 64, 10, 1000);
        for (int i = 0; i < 30; i++) {
            m.tick(i % 2 == 0 ? 0.0 : 0.3, false);   // 有进展穿插
        }
        assertEquals(MoveMachine.Outcome.PROGRESS, m.outcome(), "有进展就不该判阻塞");
    }

    @Test
    void timeoutReports() {
        MoveMachine m = new MoveMachine();
        m.startGoto(10, 64, 10, 5);
        for (int i = 0; i < 4; i++) {
            m.tick(0.5, false);
        }
        assertEquals(MoveMachine.Outcome.TIMEOUT, m.tick(0.5, false), "maxTicks 报因");
    }

    @Test
    void timeDriverCompletes() {
        MoveMachine m = new MoveMachine();
        assertTrue(m.startMove("forward", 3.0, null, 0.0) instanceof Result.Ok);
        m.tick(0.3, false);
        m.tick(0.3, false);
        assertEquals(MoveMachine.Outcome.DONE, m.tick(0.3, false), "时长耗尽=完成");
    }

    @Test
    void distanceDriverCompletes() {
        MoveMachine m = new MoveMachine();
        assertTrue(m.startMove("back", null, 1.0, 0.0) instanceof Result.Ok);
        m.tick(0.5, false);
        assertEquals(MoveMachine.Outcome.DONE, m.tick(0.6, false), "距离走完=完成");
    }

    @Test
    void directionFrozenAtStart() {
        MoveMachine m = new MoveMachine();
        m.startMove("forward", 10.0, null, 90.0);   // yaw=90 朝 -x
        // 之后转身不再影响方向向量（这里用 describe/行为间接验证：重复 start 被拒即视为冻结生效）
        assertTrue(m.startMove("back", 5.0, null, 0.0) instanceof Result.Warn, "进行中改向=WARN 不重置");
    }

    @Test
    void driversExclusive() {
        MoveMachine m = new MoveMachine();
        assertTrue(m.startMove("forward", 5.0, 5.0, 0.0) instanceof Result.Error, "时长+距离同时给=拒");
        assertTrue(m.startMove("forward", null, null, 0.0) instanceof Result.Error, "都不给=拒");
        assertTrue(m.startMove("up", 5.0, null, 0.0) instanceof Result.Error, "非法方向=拒");
    }
}
