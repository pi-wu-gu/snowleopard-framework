package io.github.piwugu.snowleopard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import snowleopard.core.json.Json;

/**
 * **已知模型的单点登记表**（2026-10-24 05:45 (UTC+8)）。
 *
 * <p>它解决的是"模型在哪儿存在"这件事没有单一事实源：此前三个消费方
 * （{@code ChatRuntime.mentionTargets} 提及候选 / {@code ChatPusher} 推送巡检 /
 * {@code record} 里的游标 {@code ensure}）都读 {@code BuiltinRuntime.knownCallers()}——
 * 那其实是 **CallerScope 句柄缓存的 keySet**，句柄只在模型调用过内置六组工具时才被创建。
 * 于是：没调过内置工具的模型不在册 → 玩家 @它不命中提及 → 不触发点名唤醒 →
 * 它也永远不会去调内置工具（**自举死锁**，只有定时/互动唤醒能打破）。
 * 句柄是执行面副产品，被当成了模型注册表——同一职责两份实现的典型漂移源。
 *
 * <p>本表是那件事的**唯一**来源，且**落盘**（{@code shared/models.json}）：
 * 否则每次开服都从空表开始，重启后点名依旧唤不醒，直到某个模型先做点什么。
 *
 * <p>登记点（都是"这个模型确实存在"的时刻，不是"它被摸过一把"）：
 * <ul>
 *   <li>{@code avatar_bind}（有载体了 = 它在世界里有个身体）；</li>
 *   <li>收到以它为目标的唤醒请求（有人叫它 = 它存在）；</li>
 *   <li>自测/验收显式登记（{@code clienttest} / {@code selftest-*}）。</li>
 * </ul>
 * 消费方一律只读 {@link #known()}，不再去别处问。
 */
public final class ModelRegistry {

    private static final Object LOCK = new Object();
    /** 插入序 = 登记序（遍历顺序稳定，断言才不会随机漂移）。 */
    private static final Set<String> MODELS = new LinkedHashSet<>();
    private static volatile Path file;

    private ModelRegistry() {
    }

    /** 装配（核心 bootstrap 之后、聊天/载体之前）：读回上次登记的名单。 */
    public static void init(Path sharedRoot) {
        Path f = sharedRoot.resolve("models.json");
        file = f;
        synchronized (LOCK) {
            MODELS.clear();
            if (Files.isRegularFile(f)) {
                try {
                    Object arr = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8))
                            .get("models");
                    if (arr instanceof List<?> list) {
                        for (Object o : list) {
                            String m = String.valueOf(o);
                            if (!m.isBlank()) {
                                MODELS.add(m);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // 名单读不出来 ≠ 名单是空的：宁可空着让别人重新登记，也不能假装读过
                    SnowLeopardMod.LOG.warn("[雪豹框架][模型表] 名单读不出来（按空表继续，"
                            + "模型被登记时会重写）: {}", e.getMessage());
                }
            }
            if (!MODELS.isEmpty()) {
                SnowLeopardMod.LOG.info("[雪豹框架][模型表] 已载入 {} 个：{}", MODELS.size(), MODELS);
            }
        }
    }

    /**
     * 登记一个模型（幂等）。返回是否真的新增（新增才写盘）。
     *
     * <p>编号为空不登记（宁可漏一个，也不往名单里塞空串——空串进提及候选会命中一切）。
     */
    public static boolean register(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        boolean added;
        synchronized (LOCK) {
            added = MODELS.add(model);
            if (!added) {
                return false;
            }
            persist();
        }
        SnowLeopardMod.LOG.info("[雪豹框架][模型表] 登记 {}（共 {} 个）", model, MODELS.size());
        return true;
    }

    /** 已知模型（快照；插入序）。没装配过返回空集，不返回 null——免得调用方各自判空。 */
    public static Set<String> known() {
        synchronized (LOCK) {
            return new LinkedHashSet<>(MODELS);
        }
    }

    /** 自测卫生：清掉本次自测登记的模型（别人的不动）。 */
    public static void forget(String model) {
        boolean removed;
        synchronized (LOCK) {
            removed = MODELS.remove(model);
            if (removed) {
                persist();
            }
        }
        if (removed) {
            SnowLeopardMod.LOG.info("[雪豹框架][模型表] 注销 {}", model);
        }
    }

    /**
     * 整表重写：先写临时文件再原子改名（多写者整表重写的覆盖竞态，与群组消息同因）。
     * 登记是低频操作，但可能被 LLM 工作线程与服务器线程同时触发，所以不能裸写。
     */
    private static void persist() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("models", new ArrayList<>(MODELS));
            Files.writeString(tmp, Json.dump(out), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][模型表] 名单写盘失败（本次登记只在内存里）: {}", e.getMessage());
        }
    }
}
