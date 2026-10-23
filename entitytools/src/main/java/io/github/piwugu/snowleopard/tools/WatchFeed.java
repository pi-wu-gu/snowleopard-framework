package io.github.piwugu.snowleopard.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import snowleopard.core.result.Result;

/**
 * 动态感知流（M6，2026-10-23 15:10 (UTC+8)）——纯逻辑，headless 可测。
 *
 * <p>契约（可行性分析 §六 + 裁决 §1.4）：
 * <ul>
 *   <li><b>事件集+轨迹枚举写死</b>——{@link Kind} 不可扩（模型按枚举消费，不猜）；</li>
 *   <li><b>每条动态携带 controller</b>——绑定实体=控制模型编号，真人=空串；</li>
 *   <li><b>保留窗口必须设</b>——条数上限（{@link #MAX_EVENTS_PER_ENTITY}）+ 时间上限
 *       （{@link #MAX_AGE_TICKS}）双淘汰，窗口外不涨内存；</li>
 *   <li><b>轨迹限频</b>——{@link #SAMPLE_INTERVAL_TICKS} 内只采一次（防采样风暴）。</li>
 * </ul>
 *
 * <p>动态=实时态势，<b>不进记忆</b>（分析定稿）——本类不依赖任何记忆组件。
 */
public final class WatchFeed {

    // ── 保留窗口（契约冻结：改=迁移）──
    public static final int MAX_EVENTS_PER_ENTITY = 64;
    public static final long MAX_AGE_TICKS = 1200;          // 60 秒（20 tick/s）
    public static final int SAMPLE_INTERVAL_TICKS = 10;     // 轨迹限频
    public static final int DEFAULT_QUERY_LIMIT = 20;

    /** 事件类型枚举（写死——"动态"=事件集+轨迹枚举写死）。 */
    public enum Kind {
        /** 轨迹采样（限频）。 */
        TRAJECTORY,
        /** 受击（data: attacker, damage）。 */
        ATTACKED,
        /** 死亡。 */
        DIED
    }

    /** 一条动态：tick=事件时刻（游戏刻），at=记录时刻（墙钟，ISO），controller=控制模型编号（真人=空）。 */
    public record Event(long tick, Kind kind, String entityRef, String controller,
                        Map<String, Object> data, java.time.Instant at) {

        /** 对外形态（有序：时间戳在前——记录一律带时间戳）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tick", tick);
            m.put("at", at.toString());
            m.put("kind", kind.name());
            m.put("entityRef", entityRef);
            m.put("controller", controller == null ? "" : controller);
            if (data != null && !data.isEmpty()) {
                m.put("data", new LinkedHashMap<>(data));
            }
            return m;
        }
    }

    /** 监视集：entityRef → controller（""=真人/无主）。 */
    private final Map<String, String> watching = new LinkedHashMap<>();

    /** 环形缓冲：entityRef → 事件队列（tick 递增）。 */
    private final Map<String, Deque<Event>> feeds = new LinkedHashMap<>();

    /** 轨迹限频基线：entityRef → 上次采样 tick。 */
    private final Map<String, Long> lastSampleTick = new HashMap<>();

    /** 开始记录（已在监视=WARN 幂等，但归属升级仍生效：绑定后 controller=模型编号）。 */
    public synchronized Result watch(String entityRef, String controller) {
        if (entityRef == null || entityRef.isBlank()) {
            return Result.error("watch 需要 entityRef（候选编号）");
        }
        if (watching.containsKey(entityRef)) {
            if (controller != null && !controller.isEmpty()) {
                watching.put(entityRef, controller);   // 归属升级（显式 watch → 绑定入册）
            }
            return Result.warn(List.of(entryOf(entityRef)),
                    "实体已在监视中（幂等，不重复登记）");
        }
        watching.put(entityRef, controller == null ? "" : controller);
        feeds.computeIfAbsent(entityRef, k -> new ArrayDeque<>());
        return Result.ok(List.of(entryOf(entityRef)));
    }

    /** 解锁（停止记录；历史保留在窗口内）。未监视=WARN 幂等。 */
    public synchronized Result unwatch(String entityRef) {
        if (entityRef == null || !watching.containsKey(entityRef)) {
            return Result.warn(List.of(), "实体不在监视集中（幂等）");
        }
        watching.remove(entityRef);
        lastSampleTick.remove(entityRef);
        return Result.ok(List.of(entryOf(entityRef)));
    }

    public synchronized boolean isWatching(String entityRef) {
        return watching.containsKey(entityRef);
    }

    /** 监视列表（每项带归属 controller）。 */
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ref : watching.keySet()) {
            out.add(entryOf(ref));
        }
        return out;
    }

    /** 轨迹限频判定：interval 内已采过则 false（调用方跳过采样）。 */
    public synchronized boolean shouldSample(String entityRef, long tick) {
        Long last = lastSampleTick.get(entityRef);
        return last == null || tick - last >= SAMPLE_INTERVAL_TICKS;
    }

    /** 记录一条动态（仅记监视中的实体；controller 以 {@link #watch} 登记为准——单源防漂移）。 */
    public synchronized void record(Kind kind, String entityRef,
                                    Map<String, Object> data, long tick) {
        if (entityRef == null || !watching.containsKey(entityRef)) {
            return;
        }
        if (kind == Kind.TRAJECTORY) {
            lastSampleTick.put(entityRef, tick);
        }
        Deque<Event> q = feeds.computeIfAbsent(entityRef, k -> new ArrayDeque<>());
        q.addLast(new Event(tick, kind, entityRef, watching.get(entityRef), data,
                java.time.Instant.now()));
        while (q.size() > MAX_EVENTS_PER_ENTITY) {
            q.pollFirst();       // 条数上限淘汰
        }
        trim(tick);
    }

    /**
     * 查询动态。entityRef=null 查全部（混合场景归属逐一可见）；
     * sinceTick 只取其后（含）；limit≤0=不限（缺省 20 的裁剪在调用方 {@code watch_events} 层）。
     */
    public synchronized List<Event> events(String entityRef, int limit, long sinceTick) {
        List<Event> all = new ArrayList<>();
        if (entityRef != null) {
            if (feeds.containsKey(entityRef)) {
                for (Event e : feeds.get(entityRef)) {
                    if (e.tick() >= sinceTick) {
                        all.add(e);
                    }
                }
            }
        } else {
            for (Deque<Event> q : feeds.values()) {
                for (Event e : q) {
                    if (e.tick() >= sinceTick) {
                        all.add(e);
                    }
                }
            }
            all.sort(java.util.Comparator.comparingLong(Event::tick));
        }
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));   // 留最近的
    }

    /** 保留窗口时间淘汰（条数淘汰在 record 内同步做）。 */
    public synchronized void trim(long nowTick) {
        for (Deque<Event> q : feeds.values()) {
            while (!q.isEmpty() && nowTick - q.peekFirst().tick() > MAX_AGE_TICKS) {
                q.pollFirst();
            }
        }
    }

    /** 快照：{watching, totalEvents}（自测对账用）。 */
    public synchronized Map<String, Object> snapshot() {
        int total = 0;
        for (Deque<Event> q : feeds.values()) {
            total += q.size();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("watching", watching.size());
        m.put("totalEvents", total);
        return m;
    }

    private Map<String, Object> entryOf(String entityRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityRef", entityRef);
        m.put("controller", watching.getOrDefault(entityRef, ""));
        Deque<Event> q = feeds.get(entityRef);
        m.put("events", q == null ? 0 : q.size());
        m.put("lastTick", q == null || q.isEmpty() ? -1 : q.peekLast().tick());
        return m;
    }
}
