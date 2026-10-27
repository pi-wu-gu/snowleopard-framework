package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 视线渐进的规格（2026-10-27 18:30 (UTC+8)）。
 *
 * <p>这里锁的是**观感契约**，不是"代码没崩"：瞳孔必须先动、头后追、到头回中；
 * 身体只在头到限位后才动；移动时身体归行进方向、凝视抢不走。这些都是真机肉眼才看得出的行为，
 * 放到单测里才有回归保护。
 */
class AvatarGazeTest {

    private static final double EPS = 1e-6;

    // ------------------------------------------------------------- 纯函数

    @Test
    void wrapUsesShortestArc() {
        // 182 与 -178 是同一个方向，最短弧取 -178（不是 2——那样会在 ±180 附近绕远路）
        assertEquals(-178.0, AvatarGaze.wrap(182.0), EPS);
        assertEquals(178.0, AvatarGaze.wrap(-182.0), EPS);
        assertEquals(179.0, AvatarGaze.wrap(179.0), EPS);
        assertEquals(-179.0, AvatarGaze.wrap(-179.0), EPS);
        assertEquals(0.0, AvatarGaze.wrap(360.0), EPS);
    }

    @Test
    void stepTowardNeverJumpsAndStopsOnTarget() {
        // 179 → -179 实际只差 2°：一步（3°）就能到，落到目标本身
        assertEquals(-179.0, AvatarGaze.stepToward(179.0, -179.0, 3.0), EPS);
        // 差 2° 但限速 1° 时，只走 1°（181 → 归一化回 -179? 不：180 归一仍 180）
        assertEquals(180.0, AvatarGaze.stepToward(179.0, -179.0, 1.0), EPS);
        // 一步走不完就限速
        assertEquals(12.0, AvatarGaze.stepToward(0.0, 90.0, 12.0), EPS);
        // 够近就精确落点（不留 +0.0001 的抖动）
        assertEquals(40.0, AvatarGaze.stepToward(38.0, 40.0, 12.0), EPS);
    }

    @Test
    void pupilIsLinearInResidualAndClamped() {
        assertEquals(0.0, AvatarGaze.pupilX(0), EPS);
        // **符号是手性**：视线向右（残余角正）→ 模型 -x（MC 左手系里 -x 才是角色右侧）
        assertEquals(-AvatarGaze.PUPIL_MAX_PX, AvatarGaze.pupilX(15), EPS);
        assertEquals(-AvatarGaze.PUPIL_MAX_PX, AvatarGaze.pupilX(200), EPS, "超限必须夹住，否则出眶露缝");
        assertEquals(AvatarGaze.PUPIL_MAX_PX, AvatarGaze.pupilX(-200), EPS);
        assertTrue(Math.abs(AvatarGaze.pupilX(7.5) - (-AvatarGaze.PUPIL_MAX_PX / 2)) < EPS);
    }

    @Test
    void pupilFollowsTargetSideNotMirrored() {
        // 回归锁：曾经瞳孔跑到反侧（用户真机肉眼验出）。目标在 +yaw（右）→ 模型 x 必须为负。
        AvatarGaze g = new AvatarGaze();
        AvatarGaze.Pose right = null;
        for (int i = 0; i < 20; i++) {
            right = g.tick(AvatarGaze.Source.LOCK, 40, 0, 0, 0, false, 0);
        }
        assertTrue(right.pupilX() < 0, "看向右侧时模型 x 应为负（左手系），实际 " + right.pupilX());

        g.reset(0, 0);
        AvatarGaze.Pose left = null;
        for (int i = 0; i < 20; i++) {
            left = g.tick(AvatarGaze.Source.LOCK, -40, 0, 0, 0, false, 0);
        }
        assertTrue(left.pupilX() > 0, "看向左侧时模型 x 应为正，实际 " + left.pupilX());

        // 垂直不反：低头（pitch 正）→ y 增大（MC 的 y 向下为正）
        g.reset(0, 0);
        AvatarGaze.Pose down = null;
        for (int i = 0; i < 20; i++) {
            down = g.tick(AvatarGaze.Source.LOCK, 0, 40, 0, 0, false, 0);
        }
        assertTrue(down.pupilY() > 0, "低头时瞳孔应向下（y 增大），实际 " + down.pupilY());
    }

    // ------------------------------------------------------------- 三级渐进（核心观感）

    @Test
    void pupilLeadsHeadWhenTargetAppears() {
        AvatarGaze g = new AvatarGaze();
        // 初始正前（身体 0），目标突然出现在右侧 40°
        AvatarGaze.Pose p = g.tick(AvatarGaze.Source.LOCK, 40, 0, 0, 0, false, 0);
        assertTrue(Math.abs(p.pupilX()) > 0.15, "第一 tick 瞳孔必须已经明显偏了（不能等头）");
        assertTrue(Math.abs(p.headYawRel()) < Math.abs(40), "头此刻还没到目标——否则就没有'先动'");
        assertTrue(p.pupilX() <= AvatarGaze.PUPIL_MAX_PX, "瞳孔不越界");
    }

    @Test
    void headStopsShortAndPupilHoldsOffset() {
        // 新契约（2026-10-27 18:58 真机反馈后定）：头只追到"差 EYE_ONLY_LIMIT"，剩下的给眼睛。
        // 原来头追满、瞳孔回中 → 玩家只看到头转（"只看到转头、看不到瞳孔"）。
        AvatarGaze g = new AvatarGaze();
        AvatarGaze.Pose p = null;
        for (int i = 0; i < 20; i++) {
            p = g.tick(AvatarGaze.Source.LOCK, 40, 0, 0, 0, false, 0);
        }
        assertEquals(40.0 - AvatarGaze.EYE_ONLY_LIMIT, p.headYawRel(), 0.8,
                "头应停在差 15° 处，实际 " + p.headYawRel());
        assertEquals(AvatarGaze.PUPIL_MAX_PX, Math.abs(p.pupilX()), 0.01,
                "稳态瞳孔必须偏到满格（否则肉眼看不见）；符号由方向测试锁");
        assertEquals(0.0, p.bodyYaw(), EPS, "40° 在头限位内，身体不该动");
    }

    @Test
    void bodyTurnsOnlyAfterHeadHitsLimit() {
        AvatarGaze g = new AvatarGaze();
        AvatarGaze.Pose p = null;
        for (int i = 0; i < 3; i++) {
            p = g.tick(AvatarGaze.Source.LOCK, 120, 0, 0, 0, false, 0);
        }
        assertEquals(0.0, p.bodyYaw(), EPS, "头还没到 65°，身体一动都不该动");
        // 有死区时头起步更慢：第 1 tick 视线才 12°（< 15）头完全不动，之后才 8°/tick
        assertTrue(p.headYawRel() < 24, "头应被死区压住、比视线慢，实际 " + p.headYawRel());

        for (int i = 0; i < 40; i++) {
            p = g.tick(AvatarGaze.Source.LOCK, 120, 0, p.bodyYaw(), 0, false, 0);
        }
        // 身体不需要转到"对准目标"：转到"头相对差刚好 65°"就够（65+55=120 才是视线终点）。
        // 若身体一路转到 120，头会白白回中，看起来像"整个身子拧过去看"——不是要的效果。
        assertTrue(p.bodyYaw() >= 30, "头到限位后身体必须跟上，实际 " + p.bodyYaw());
        assertTrue(p.headYawRel() <= AvatarGaze.HEAD_YAW_LIMIT + EPS, "头相对角不越限位");
        // 视线 = 身体 + 头 + 瞳（瞳只剩最后 15°，对应满格像素）
        double eyeDeg = Math.abs(p.pupilX()) / AvatarGaze.PUPIL_MAX_PX * AvatarGaze.EYE_ONLY_LIMIT;
        assertEquals(120.0, p.bodyYaw() + p.headYawRel() + eyeDeg, 2.5,
                "身体+头+眼 的合成视线必须落到目标 120°，实际 "
                        + (p.bodyYaw() + p.headYawRel() + eyeDeg));
    }

    @Test
    void headRelativeNeverExceedsLimit() {
        AvatarGaze g = new AvatarGaze();
        double body = 0;
        for (int i = 0; i < 40; i++) {
            AvatarGaze.Pose p = g.tick(AvatarGaze.Source.LOCK, -150, 10, body, 0, false, 0);
            body = p.bodyYaw();
            assertTrue(Math.abs(p.headYawRel()) <= AvatarGaze.HEAD_YAW_LIMIT + EPS,
                    "第 " + i + " tick 头相对角越限: " + p.headYawRel());
            assertTrue(Math.abs(p.headPitchRel()) <= AvatarGaze.HEAD_PITCH_LIMIT + EPS,
                    "第 " + i + " tick 俯仰越限: " + p.headPitchRel());
        }
    }

    @Test
    void movingKeepsBodyOnTravelDirectionEvenWhenLocked() {
        AvatarGaze g = new AvatarGaze();
        AvatarGaze.Pose p = null;
        for (int i = 0; i < 10; i++) {
            // 边走边看：行进方向 0°，锁定目标在 90°
            p = g.tick(AvatarGaze.Source.LOCK, 90, 0, 0, 0, true, 0);
        }
        assertEquals(0.0, p.bodyYaw(), EPS, "移动时凝视不得抢身体（否则 goto 侧着滑）");
        assertEquals(AvatarGaze.HEAD_YAW_LIMIT, p.headYawRel(), 0.5, "头到限位就停住，不越界");
    }

    @Test
    void movingTurnsBodyTowardTravelDirection() {
        AvatarGaze g = new AvatarGaze();
        AvatarGaze.Pose p = null;
        double body = 0;
        for (int i = 0; i < 30; i++) {
            p = g.tick(AvatarGaze.Source.MOVE, -90, 0, body, 0, true, -90);
            body = p.bodyYaw();
        }
        assertTrue(p.bodyYaw() < -50, "移动时身体应朝行进方向（-90），实际 " + p.bodyYaw());
        assertTrue(Math.abs(p.headYawRel()) < 2, "移动时头必须正（不留死区，否则像边走边偷看），实际 "
                + p.headYawRel());
        assertTrue(Math.abs(p.pupilX()) < 0.01, "移动收束后瞳孔回中");
    }

    // ------------------------------------------------------------- 目标切换防抖

    @Test
    void lowerPriorityCannotStealDuringDwell() {
        AvatarGaze g = new AvatarGaze();
        g.tick(AvatarGaze.Source.LOCK, 30, 0, 0, 0, false, 0);
        // 立刻降级为 MOVE：驻留期内应继续按 LOCK 走
        AvatarGaze.Pose p = g.tick(AvatarGaze.Source.MOVE, 0, 0, 0, 0, true, 0);
        assertEquals(AvatarGaze.Source.LOCK, p.source(), "降级切换必须等驻留期，否则会来回抽");
    }

    @Test
    void higherPriorityPreemptsImmediately() {
        AvatarGaze g = new AvatarGaze();
        g.tick(AvatarGaze.Source.LOOK, 30, 0, 0, 0, false, 0);
        AvatarGaze.Pose p = g.tick(AvatarGaze.Source.LOCK, 60, 0, 0, 0, false, 0);
        assertEquals(AvatarGaze.Source.LOCK, p.source(), "模型明确锁定必须能立刻抢回");
    }

    @Test
    void dwellExpiryAllowsDowngrade() {
        AvatarGaze g = new AvatarGaze();
        g.tick(AvatarGaze.Source.LOCK, 30, 0, 0, 0, false, 0);
        AvatarGaze.Pose p = null;
        for (int i = 0; i < AvatarGaze.SWITCH_DWELL + 2; i++) {
            p = g.tick(AvatarGaze.Source.MOVE, 0, 0, 0, 0, true, 0);
        }
        assertEquals(AvatarGaze.Source.MOVE, p.source(), "驻留期满后应允许切换");
    }

    // ------------------------------------------------------------- 复位

    @Test
    void resetReturnsToBodyFacing() {
        AvatarGaze g = new AvatarGaze();
        for (int i = 0; i < 5; i++) {
            g.tick(AvatarGaze.Source.LOCK, 90, 20, 0, 0, false, 0);
        }
        g.reset(0, 0);
        AvatarGaze.Pose p = g.tick(AvatarGaze.Source.IDLE, 0, 0, 0, 0, false, 0);
        assertEquals(0.0, p.headYawRel(), EPS, "复位后头必须正（否则解锁会'卡住脸'）");
        assertEquals(0.0, p.pupilX(), EPS);
        assertEquals(0.0, p.headPitchRel(), EPS);
    }

    @Test
    void idleRecentersSmoothlyNotInstantly() {
        AvatarGaze g = new AvatarGaze();
        for (int i = 0; i < 10; i++) {
            g.tick(AvatarGaze.Source.LOOK, 40, 0, 0, 0, false, 0);
        }
        AvatarGaze.Pose p = g.tick(AvatarGaze.Source.IDLE, 0, 0, 0, 0, false, 0);
        assertTrue(p.headYawRel() > 0 && p.headYawRel() < 40,
                "归正要渐变，不能一帧弹回，实际 " + p.headYawRel());
    }
}
