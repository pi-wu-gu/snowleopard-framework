package io.github.piwugu.snowleopard.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视觉申请的待投递区（2026-10-27 06:15 (UTC+8)）。
 *
 * <p><b>它不是队列</b>——这一点很关键。核心的既有裁决是"唤醒失败不排队（排队=把失败变成稍后执行）"，
 * 所以这里存的**不是"要执行的活"，而是"要投递的结果"**：
 * <ul>
 *   <li>会话正在跑、图回来了 → 标 {@code DEFERRED}，**不重试唤醒**，下次会话开头捎带并注明延迟；</li>
 *   <li>一次积压超过 1 张 → 只注入最新的，其余标 {@code DROPPED} 并如实说明。</li>
 * </ul>
 *
 * <p>会话结束仍在 {@code REQUESTED} 的，转为 {@code DEFERRED}（图到了也还在，
 * 因为文件名带 reqId，宿主下次按编号找回）。
 */
public final class VisionPending {

    public enum State {
        /** 已受理、渲染中 */
        REQUESTED,
        /** 图已落盘 */
        READY,
        /** 渲染/受理失败 */
        FAILED,
        /** 图到了但当时不能投递（会话在跑），等下次捎带 */
        DEFERRED,
        /** 积压过多被丢弃（会如实告诉模型"有 N 张较早的已丢弃"） */
        DROPPED
    }

    public record Rec(String reqId, String modelId, State state, String file, String reason,
                      String source, int width, int height, long bornMs) {
    }

    private static final Map<String, Rec> ALL = new LinkedHashMap<>();
    /** 每模型本会话已用截图次数（会话开始重置）。 */
    private static final Map<String, Integer> USED = new LinkedHashMap<>();

    private VisionPending() {
    }

    // ------------------------------------------------------------------ 受理

    /** 会话开始：重置截图预算。 */
    public static synchronized void beginSession(String model) {
        USED.put(model, 0);
    }

    /** 该模型本会话已拍几张。 */
    public static synchronized int used(String model) {
        return USED.getOrDefault(model, 0);
    }

    public static synchronized boolean inFlight(String model) {
        for (Rec r : ALL.values()) {
            if (r.modelId().equals(model) && r.state() == State.REQUESTED) {
                return true;
            }
        }
        return false;
    }

    /** 受理一次申请；预算用完 / 已有在飞 → 抛（由工具层转成 Error/Warn，不许静默）。 */
    public static synchronized String open(String model, String source, int w, int h, int maxPerSession) {
        int used = USED.getOrDefault(model, 0);
        if (maxPerSession > 0 && used >= maxPerSession) {
            throw new IllegalStateException("本会话截图预算已用完（" + used + "/" + maxPerSession
                    + "）——改用 vision/scan，或用 vision/show 重看旧图");
        }
        if (inFlight(model)) {
            throw new IllegalStateException("上一张仍在渲染中，本次未受理（不排队）");
        }
        String reqId = "cap-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toString((int) (Math.random() * 1296), 36);
        ALL.put(reqId, new Rec(reqId, model, State.REQUESTED, null, "", source, w, h,
                System.currentTimeMillis()));
        USED.put(model, used + 1);
        return reqId;
    }

    /** reqId → 模型（回执只带 reqId；查不到 = 过期回执，调用方直接丢弃）。 */
    public static synchronized String modelOf(String reqId) {
        Rec r = ALL.get(reqId);
        return r == null ? null : r.modelId();
    }

    /**
     * 超时清扫：{@code REQUESTED} 超过 {@code timeoutMs} 仍未回执 → 标 {@code FAILED}。
     * 图永远不回来时也要**让模型知道失败了**，不能一直"稍等"。
     */
    public static synchronized int sweep(long timeoutMs) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Map.Entry<String, Rec> e : new ArrayList<>(ALL.entrySet())) {
            Rec r = e.getValue();
            if (r.state() == State.REQUESTED && now - r.bornMs() > timeoutMs) {
                ALL.put(e.getKey(), new Rec(r.reqId(), r.modelId(), State.FAILED, null,
                        "渲染超时（" + timeoutMs + "ms 内客户端未回执）", r.source(),
                        r.width(), r.height(), r.bornMs()));
                n++;
            }
        }
        return n;
    }

    public static synchronized void ready(String reqId, String file) {
        Rec r = ALL.get(reqId);
        if (r != null) {
            ALL.put(reqId, new Rec(r.reqId(), r.modelId(), State.READY, file, "", r.source(),
                    r.width(), r.height(), r.bornMs()));
        }
    }

    public static synchronized void failed(String reqId, String reason) {
        Rec r = ALL.get(reqId);
        if (r != null) {
            ALL.put(reqId, new Rec(r.reqId(), r.modelId(), State.FAILED, null,
                    reason == null ? "未知原因" : reason, r.source(), r.width(), r.height(), r.bornMs()));
        }
    }

    /** {@code vision/show}：把已落盘的图直接排进待投递（不重渲）。 */
    public static synchronized void replay(String model, String file) {
        String reqId = "show-" + Long.toString(System.currentTimeMillis(), 36);
        ALL.put(reqId, new Rec(reqId, model, State.READY, file, "", "replay", 0, 0,
                System.currentTimeMillis()));
    }

    /** 会话结束：还在渲染的转为 DEFERRED（不重试，等下次捎带）。 */
    public static synchronized void deferStillPending(String model) {
        for (Map.Entry<String, Rec> e : new ArrayList<>(ALL.entrySet())) {
            Rec r = e.getValue();
            if (r.modelId().equals(model) && r.state() == State.REQUESTED) {
                ALL.put(e.getKey(), new Rec(r.reqId(), r.modelId(), State.DEFERRED, r.file(),
                        "会话结束时仍在渲染", r.source(), r.width(), r.height(), r.bornMs()));
            } else if (r.modelId().equals(model) && r.state() == State.READY) {
                ALL.put(e.getKey(), new Rec(r.reqId(), r.modelId(), State.DEFERRED, r.file(),
                        "图到了但当时会话已结束", r.source(), r.width(), r.height(), r.bornMs()));
            }
        }
    }

    // ------------------------------------------------------------------ 投递

    /**
     * 取走可投递的记录（投递即删除）：**最新 1 张图** + 最多 2 条失败提示，其余标 DROPPED。
     *
     * <p>只给 1 张是刻意的：一次注入多张图会让上下文里堆满旧画面，模型会拿过期视觉做决策。
     */
    public static synchronized List<Rec> drain(String model) {
        List<Rec> images = new ArrayList<>();
        List<Rec> fails = new ArrayList<>();
        int droppedImages = 0;
        for (Map.Entry<String, Rec> e : new ArrayList<>(ALL.entrySet())) {
            Rec r = e.getValue();
            if (!r.modelId().equals(model)) {
                continue;
            }
            if (r.state() == State.REQUESTED) {
                continue;                       // 还在渲染，等下一轮
            }
            ALL.remove(e.getKey());
            if (r.state() == State.FAILED) {
                if (fails.size() < 2) {
                    fails.add(r);
                }
            } else if (r.state() == State.READY || r.state() == State.DEFERRED) {
                images.add(r);
            }
        }
        images.sort((a, b) -> Long.compare(b.bornMs(), a.bornMs()));
        List<Rec> out = new ArrayList<>(fails);
        if (!images.isEmpty()) {
            out.add(images.get(0));             // 最新一张
            droppedImages = images.size() - 1;
            if (droppedImages > 0) {
                Rec keep = out.get(out.size() - 1);
                out.add(new Rec(keep.reqId(), model, State.DROPPED, null,
                        "另有 " + droppedImages + " 张较早的截图已丢弃（一次只注入最新一张）",
                        keep.source(), keep.width(), keep.height(), keep.bornMs()));
            }
        }
        return out;
    }

    /** 自测/排障用：当前积压数量。 */
    public static synchronized int pendingCount(String model) {
        int n = 0;
        for (Rec r : ALL.values()) {
            if (r.modelId().equals(model) && r.state() != State.DROPPED) {
                n++;
            }
        }
        return n;
    }

    public static synchronized void clear(String model) {
        ALL.entrySet().removeIf(e -> e.getValue().modelId().equals(model));
        USED.remove(model);
    }
}
