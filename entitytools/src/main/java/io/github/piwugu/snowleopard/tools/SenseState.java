package io.github.piwugu.snowleopard.tools;

/**
 * 感官开关状态机（M0 冻结，2026-10-23 05:30 (UTC+8)；裁决 05:10）。
 *
 * <pre>
 *   open()      OFF ──► AWAITING_PICK      （进入等待选取）
 *   pick(ref)   AWAITING_PICK ──► BOUND    （选取绑定 1:1；未选取候选休眠——由 MC 层执行）
 *   close()     BOUND|AWAITING_PICK ──► OFF（显式关闭=解绑候选）
 *   death(ref)  BOUND（ref=绑定实体）──► OFF（实体死亡自动关感官——裁决）
 * </pre>
 *
 * <p>控制类工具仅在 {@code BOUND} 态可用——其余状态调用一律显式拒绝（假成功比失败更坏）。
 * 重新 {@code open} 于 BOUND 态 = 重新选取（原实体解绑并休眠）。
 */
public enum SenseState {
    /** 感官关闭：控制类工具禁用。 */
    OFF,
    /** 等待选取：候选三件套已喂给模型，等 pick。 */
    AWAITING_PICK,
    /** 已绑定：控制类工具可用。 */
    BOUND
}
