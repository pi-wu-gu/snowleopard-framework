package io.github.piwugu.snowleopard.tools;

import snowleopard.core.result.Result;

/**
 * 感官状态机实现（M0 冻结，2026-10-23 05:30 (UTC+8)）。
 *
 * <p>纯 Java、可 headless 测试——MC 侧（实体休眠/候选采集/死亡事件）是本机的适配层，
 * 状态语义只住这里（同一职责单实现，防漂移）。所有非法转换返回 {@link Result} 而非静默。
 */
public final class SenseMachine {

    private SenseState state = SenseState.OFF;
    private String boundEntity;

    public SenseState state() {
        return state;
    }

    /** 已绑定实体引用（非 BOUND 态为 null）。 */
    public String boundEntity() {
        return boundEntity;
    }

    /** 控制类工具是否可用：仅 BOUND。 */
    public boolean toolsEnabled() {
        return state == SenseState.BOUND;
    }

    /** 开启感官：OFF→AWAITING；BOUND→AWAITING（重新选取，原实体解绑）；AWAITING→WARN。 */
    public synchronized Result open() {
        switch (state) {
            case OFF -> {
                state = SenseState.AWAITING_PICK;
                return Result.ok(java.util.Map.of("state", state.name(), "phase", "等待选取"));
            }
            case AWAITING_PICK -> {
                return Result.warn(java.util.Map.of("state", state.name()),
                        "感官已处于等待选取状态（重复 open 不重置候选）");
            }
            case BOUND -> {
                String prev = boundEntity;
                boundEntity = null;
                state = SenseState.AWAITING_PICK;
                return Result.warn(data(state.name(), prev),
                        "重新选取：原绑定实体已解绑并将进入休眠");
            }
            default -> throw new IllegalStateException("未知状态: " + state);
        }
    }

    /** 选取绑定（1:1）：仅 AWAITING 态合法。未选取候选的休眠由 MC 层执行。 */
    public synchronized Result pick(String entityRef) {
        if (entityRef == null || entityRef.isBlank()) {
            return Result.error("pick 需要实体引用（候选三件套里的编号）");
        }
        if (state == SenseState.BOUND) {
            return Result.error("已绑定 " + boundEntity + "——重新选取请先 open（会解绑原实体）");
        }
        if (state != SenseState.AWAITING_PICK) {
            return Result.error("感官未开启——先 open 进入等待选取（当前: " + state + "）");
        }
        boundEntity = entityRef;
        state = SenseState.BOUND;
        return Result.ok(java.util.Map.of("state", state.name(), "bound", entityRef));
    }

    /** 显式关闭：任意态→OFF（BOUND=解绑）。OFF 上重复 close=WARN。 */
    public synchronized Result close() {
        if (state == SenseState.OFF) {
            return Result.warn(java.util.Map.of("state", state.name()), "感官已是关闭状态");
        }
        String prev = boundEntity;
        boundEntity = null;
        state = SenseState.OFF;
        return Result.ok(data(state.name(), prev));
    }

    /**
     * 实体死亡（裁决 05:10：死亡→感官自动关闭）。BOUND 且命中绑定实体→OFF；
     * 其余情形=候选消亡或无关实体，WARN 说明、状态不动。
     */
    public synchronized Result death(String entityRef) {
        if (state == SenseState.BOUND && entityRef != null && entityRef.equals(boundEntity)) {
            boundEntity = null;
            state = SenseState.OFF;
            return Result.ok(java.util.Map.of("state", state.name(), "reason", "绑定实体死亡，感官自动关闭"));
        }
        return Result.warn(java.util.Map.of("state", state.name()),
                "死亡事件与当前绑定无关（候选消亡或无关实体）——状态不变");
    }

    /** 数据载荷：unbound 仅在真有解绑时出现（Map.of 不收 null——M0 测试逮过的坑）。 */
    private static java.util.Map<String, Object> data(String stateName, String unbound) {
        java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("state", stateName);
        if (unbound != null) {
            d.put("unbound", unbound);
        }
        return d;
    }
}
