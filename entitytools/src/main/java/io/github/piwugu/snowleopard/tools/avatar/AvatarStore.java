package io.github.piwugu.snowleopard.tools.avatar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import snowleopard.core.json.Json;

/**
 * 载体登记表与唤醒排程（M13-0/M13-2，2026-10-24 01:15 (UTC+8)，分析见
 * {@code docs/实体载体与唤醒源可行性分析-2026-10-23.md}）。
 *
 * <p><b>这个文件是排程的唯一权威</b>：实体未加载时读不到 NBT，但定时仍要按时判断（§2.3 裁决）。
 * 实体 NBT 里的 modelId 只是镜像，加载时以本表为准修正并 WARN——两个都"看起来对"时，
 * 选覆盖面大的那个，并把漂移暴露出来。
 *
 * <p><b>主载体唯一</b>（裁决 5）：同一模型可有多个载体，但只有主载体接唤醒；
 * 重新绑定 = 主载体换人（旧主自动降级），不搞"多个都唤醒"。
 *
 * <p><b>禁重名</b>（裁决 4）：名称是提及唤醒的关键词，重名会让"谁被唤醒了"无法回答。
 * 两个都无名不算冲突（此时身份退化为编号）。
 */
public final class AvatarStore {

    /** 定时间隔下限（秒）——低于它就是轮询，等于无意义地烧预算。 */
    public static final long MIN_INTERVAL_SEC = 60;

    /**
     * 一条载体登记。
     *
     * @param uuid        载体实体 UUID
     * @param modelId     绑定的模型
     * @param name        显示名（可空；空 = 身份用编号，且不参与提及唤醒）
     * @param primary     是否主载体（每模型至多一个）
     * @param intervalSec 定时间隔（0 = 未设定时）
     * @param nextFireAt  下次定时唤醒的 epoch 毫秒（0 = 未设定时）
     */
    public record Entry(String uuid, String modelId, String name, boolean primary,
                        long intervalSec, long nextFireAt) {

        public Entry {
            if (uuid == null || uuid.isBlank()) {
                throw new IllegalArgumentException("uuid 不能为空");
            }
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId 不能为空");
            }
            if (name == null) {
                name = "";
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", uuid);
            m.put("modelId", modelId);
            m.put("name", name);
            m.put("primary", primary);
            m.put("intervalSec", intervalSec);
            m.put("nextFireAt", nextFireAt);
            return m;
        }
    }

    private final Path file;
    private final Object lock = new Object();
    private List<Entry> entries = new ArrayList<>();

    public AvatarStore(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("需要登记表路径（共享面，排程的权威）");
        }
        this.file = file;
        load();
    }

    public Path file() {
        return file;
    }

    /** 登记/换绑：同模型旧主自动降级；重名 → {@link IllegalArgumentException}。 */
    public Entry bind(String uuid, String modelId, String name) {
        synchronized (lock) {
            if (nameTaken(name, uuid)) {
                throw new IllegalArgumentException("载体名称重名：" + name + "（提及唤醒无法区分谁被点了名）");
            }
            entries.removeIf(e -> e.uuid().equals(uuid));
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.modelId().equals(modelId) && e.primary()) {
                    entries.set(i, new Entry(e.uuid(), e.modelId(), e.name(), false,
                            e.intervalSec(), e.nextFireAt()));
                }
            }
            Entry e = new Entry(uuid, modelId, name == null ? "" : name.strip(), true, 0, 0);
            entries.add(e);
            save();
            return e;
        }
    }

    /** 解除某模型的全部绑定（模型主动解绑 / 载体死亡）。 */
    public int unbindByModel(String modelId) {
        synchronized (lock) {
            int before = entries.size();
            entries.removeIf(e -> e.modelId().equals(modelId));
            int removed = before - entries.size();
            if (removed > 0) {
                save();
            }
            return removed;
        }
    }

    /** 载体消亡（死亡/被杀）→ 只解绑那一个。 */
    public void removeByUuid(String uuid) {
        synchronized (lock) {
            if (entries.removeIf(e -> e.uuid().equals(uuid))) {
                save();
            }
        }
    }

    public Entry byUuid(String uuid) {
        synchronized (lock) {
            return entries.stream().filter(e -> e.uuid().equals(uuid)).findFirst().orElse(null);
        }
    }

    /** 全部登记项（运维查看用：载体死亡解绑后要靠命令重生，得先能看见"现在有哪些"）。 */
    public List<Entry> all() {
        synchronized (lock) {
            return List.copyOf(entries);
        }
    }

    public List<Entry> byModel(String modelId) {
        synchronized (lock) {
            return entries.stream().filter(e -> e.modelId().equals(modelId)).toList();
        }
    }

    public Entry primaryOf(String modelId) {
        synchronized (lock) {
            return entries.stream().filter(e -> e.modelId().equals(modelId) && e.primary())
                    .findFirst().orElse(null);
        }
    }

    /**
     * 设定定时间隔（秒）。
     *
     * @return 更新后的条目（{@code nextFireAt = now + interval}）
     */
    public Entry setSchedule(String modelId, long intervalSec, long nowMs) {
        synchronized (lock) {
            if (intervalSec < MIN_INTERVAL_SEC) {
                throw new IllegalArgumentException("定时间隔下限 " + MIN_INTERVAL_SEC + " 秒（"
                        + intervalSec + " 是轮询，不是定时）");
            }
            Entry p = primaryOf(modelId);
            if (p == null) {
                throw new IllegalStateException("没有主载体——先用 avatar_bind 登记载体");
            }
            Entry updated = new Entry(p.uuid(), p.modelId(), p.name(), true,
                    intervalSec, nowMs + intervalSec * 1000L);
            entries.removeIf(e -> e.uuid().equals(p.uuid()));
            entries.add(updated);
            save();
            return updated;
        }
    }

    /**
     * 到期的主载体（{@code intervalSec > 0 && nextFireAt <= now}）。
     */
    public List<Entry> due(long nowMs) {
        synchronized (lock) {
            return entries.stream()
                    .filter(e -> e.primary() && e.intervalSec() > 0 && e.nextFireAt() <= nowMs)
                    .toList();
        }
    }

    /** 处理完一条定时（无论唤醒成功还是跳过）：nextFireAt = now + interval，**绝不堆积补发**。 */
    public void advance(String uuid, long nowMs) {
        synchronized (lock) {
            Entry e = byUuid(uuid);
            if (e == null || e.intervalSec() <= 0) {
                return;
            }
            entries.removeIf(x -> x.uuid().equals(uuid));
            entries.add(new Entry(e.uuid(), e.modelId(), e.name(), e.primary(),
                    e.intervalSec(), nowMs + e.intervalSec() * 1000L));
            save();
        }
    }

    /** 直接改下次触发时间（运维/测试用；生产路径走 {@link #setSchedule}）。 */
    public void setNextFireAt(String uuid, long nextFireAt) {
        synchronized (lock) {
            Entry e = byUuid(uuid);
            if (e == null) {
                return;
            }
            entries.removeIf(x -> x.uuid().equals(uuid));
            entries.add(new Entry(e.uuid(), e.modelId(), e.name(), e.primary(),
                    e.intervalSec(), nextFireAt));
            save();
        }
    }

    /** 名称是否已被其它载体占用（≥2 码点才参与；大小写不敏感）。 */
    public boolean nameTaken(String name, String excludeUuid) {
        if (name == null) {
            return false;
        }
        String n = name.strip();
        if (n.isEmpty() || n.codePointCount(0, n.length()) < 2) {
            return false;
        }
        synchronized (lock) {
            return entries.stream().anyMatch(e -> !e.uuid().equals(excludeUuid)
                    && e.name().toLowerCase(Locale.ROOT).equals(n.toLowerCase(Locale.ROOT)));
        }
    }

    // ------------------------------------------------------------- 持久化

    private void load() {
        if (!Files.isRegularFile(file)) {
            entries = new ArrayList<>();
            return;
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            List<Entry> out = new ArrayList<>();
            if (m.get("entries") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> e) {
                        out.add(new Entry(
                                str(e.get("uuid")), str(e.get("modelId")), str(e.get("name")),
                                Boolean.TRUE.equals(e.get("primary")),
                                lng(e.get("intervalSec")), lng(e.get("nextFireAt"))));
                    }
                }
            }
            entries = out;
        } catch (IOException e) {
            throw new UncheckedIOException("读载体登记表失败: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    private void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entries", entries.stream().map(Entry::toMap).toList());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, Json.dump(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写载体登记表失败: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
