package io.github.piwugu.snowleopard.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import snowleopard.core.SnowLeopard;
import snowleopard.core.json.Json;
import snowleopard.core.registry.ToolRegistry;
import snowleopard.core.result.Result;
import snowleopard.core.wake.WakeStore;

import io.github.piwugu.snowleopard.AvatarWake;
import io.github.piwugu.snowleopard.ChatRuntime;
import io.github.piwugu.snowleopard.EntityToolsRuntime;
import io.github.piwugu.snowleopard.ModelRegistry;
import io.github.piwugu.snowleopard.PlayerTestArea;
import io.github.piwugu.snowleopard.SnowLeopardMod;
import io.github.piwugu.snowleopard.WakeBridge;
import io.github.piwugu.snowleopard.tools.chat.ChatInbox;

/**
 * AI 执行端（host 层，H2，2026-10-24 02:45 (UTC+8)，设计见
 * {@code docs/AI执行端设计-2026-10-24.md}）。
 *
 * <p><b>这是真的执行</b>：轮询 {@code WakeStore.pending()} → 为目标模型开真正的 LLM 会话 →
 * 工具逐个 {@code dispatch}（权限照旧 core 裁决，caller_id 不可伪造，host 不开后门）→
 * 轮末 {@code ack / consumeTurnLine / complete}。没有接入方"假装执行"的路径——因为执行端就在这里。
 *
 * <p><b>触发只由唤醒驱动</b>：每 20 tick poll 一次，无请求不开会话（空闲空转纯烧钱）。
 * 多请求在 host 层 FIFO 顺序开会话（执行端自己的队列，可见且诚实——与"core 不排队"不冲突）。
 *
 * <p><b>线程模型</b>：LLM 调用在单工作线程上阻塞数秒，**绝不占用服务器线程**；
 * 但工具 dispatch 需要服务器线程 → {@code server.execute + latch} 等结果（与账本同一条铁律）。
 * 同一时刻只有一个会话（单工作线程天然保证；{@code RuntimeView} 锁是第二道防线）。
 */
public final class AiHost {

    private static volatile LlmClient client;
    private static volatile ConversationStore store;
    private static volatile int maxRounds = 12;
    private static volatile int historyWindow = 40;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final java.util.concurrent.ExecutorService WORKER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "snowleopard-host");
                t.setDaemon(true);
                return t;
            });

    private AiHost() {
    }

    public static boolean ready() {
        return client != null;
    }

    /**
     * 装配。配置文件不存在 = host 停用（如实记日志，不降级成假执行）。
     * **只在客户端验收模式接线**：全量自测模式里自测自己数 pending，host 会把请求消费掉造成假失败
     * （接线与否由调用方用 {@code enabled} 控制；Compressor 走 {@link #compressorOrNull}，在 bootstrap 前注入）。
     */
    public static void init(Path gameDir, SnowLeopard core, boolean enabled) {
        Path f = gameDir.resolve("config").resolve("snowleopard-llm.json");
        if (!enabled) {
            SnowLeopardMod.LOG.info("[雪豹框架][host] 非客户端验收模式——AI 执行端停用（自测要自己数 pending）");
            return;
        }
        if (!java.nio.file.Files.isRegularFile(f)) {
            SnowLeopardMod.LOG.info("[雪豹框架][host] 未配置 LLM（{} 不存在）——AI 执行端停用", f);
            return;
        }
        try {
            LlmConfig cfg = LlmConfig.load(f);
            client = new LlmClient(cfg);
            store = new ConversationStore(core.ids().root());   // root() 就是 ids 根，不要再拼 "ids"
            maxRounds = cfg.maxRounds();
            historyWindow = cfg.historyWindow();
            SnowLeopardMod.LOG.info("[雪豹框架][host] AI 执行端就绪（{} 家提供商，轮次预算 {}）",
                    cfg.providers().size(), cfg.maxRounds());
        } catch (Exception e) {
            SnowLeopardMod.LOG.error("[雪豹框架][host] 初始化失败——AI 执行端停用: {}", e.getMessage(), e);
        }
    }

    /** 启动时的预热：把 LLM 配置里的 Compressor 挂上（在 bootstrap 前调用才生效）。 */
    public static snowleopard.core.memory.Compressor compressorOrNull(Path gameDir) {
        Path f = gameDir.resolve("config").resolve("snowleopard-llm.json");
        if (!java.nio.file.Files.isRegularFile(f)) {
            return null;
        }
        try {
            LlmClient c = new LlmClient(LlmConfig.load(f));
            return (purpose, sourceText) -> c.complete(
                    "你是记忆压缩器。把源文本压缩为要点摘要，保留事实与时间戳，不要评论、不要客套。",
                    "用途: " + purpose + "\n源文本:\n" + sourceText);
        } catch (Exception e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][host] Compressor 注入失败（记忆吸收走直通）: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------- 轮询

    public static void onServerTick(ServerTickEvent.Post event) {
        if (client == null || event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return;   // 上一批还没跑完：下个 poll 再说（FIFO 不丢）
        }
        WORKER.submit(() -> {
            try {
                drain();
            } catch (Exception e) {
                SnowLeopardMod.LOG.error("[雪豹框架][host] 轮询异常: {}", e.getMessage(), e);
            } finally {
                inFlight.set(false);
            }
        });
    }

    private static void drain() {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null) {
            return;
        }
        List<WakeStore.Request> pending = new ArrayList<>(core.wake().pending());
        pending.sort((a, b) -> Long.compare(a.ts(), b.ts()));   // FIFO
        for (WakeStore.Request req : pending) {
            runSession(core, req);
        }
    }

    // ------------------------------------------------------------- 会话

    private static void runSession(SnowLeopard core, WakeStore.Request req) {
        String model = req.to();
        // 登记点之一：有人叫它 = 它存在。登记放在会话之外——会话可能开场就失败，
        // 但"被叫过一次"这件事不因失败而消失（否则下一次点名它还是不在册）
        ModelRegistry.register(model);
        if (!WakeBridge.beginSession(model)) {
            SnowLeopardMod.LOG.warn("[雪豹框架][host] {} 会话开不了（在运行/锁冲突）——请求留在 pending", model);
            return;
        }
        MemoryRecorder.Scope rec = MemoryRecorder.begin(core, model);
        try {
            String outcome = converse(core, model, req, rec);
            core.wake().complete(req.reqId(), true, outcome);
            SnowLeopardMod.LOG.info("[雪豹框架][host] 会话完成 {} ← {}（{}）", model, req.from(), outcome);
            Result mem = rec.finish();
            if (mem != null && !(mem instanceof Result.Ok)) {
                SnowLeopardMod.LOG.warn("[雪豹框架][记忆] {} 本轮压缩未完成（原始记录已在）: {}", model, mem);
            }
        } catch (Exception e) {
            SnowLeopardMod.LOG.error("[雪豹框架][host] 会话失败 {} ← {}: {}",
                    model, req.from(), e.getMessage(), e);
            // 失败也留一条原始记录：排障时要能回看"它当时在干什么"，但只记原因不记堆栈
            rec.note("会话失败: " + e.getMessage());
            rec.finish();
            core.wake().complete(req.reqId(), false, String.valueOf(e.getMessage()));
        } finally {
            WakeBridge.endSession(model);
        }
    }

    /** 一轮完整会话：装配 → 工具循环 → 收尾。返回结果摘要。{@code rec} 负责把这一轮记进记忆。 */
    private static String converse(SnowLeopard core, String model, WakeStore.Request req,
                                   MemoryRecorder.Scope rec) {
        // ① 装配上下文（注入块与提醒行同 M12 来源，host 只是"塞进上下文的角色"）
        ChatInbox.Pulled pulled = ChatRuntime.pull(model, 20);
        String turnLine = WakeBridge.turnLine(model);
        StringBuilder user = new StringBuilder();
        user.append("【唤醒】来源 ").append(req.from()).append("（").append(req.reason()).append("）\n")
                .append(req.prompt() == null ? "" : req.prompt());
        if (pulled != null && pulled.count() > 0) {
            user.append("\n\n").append(pulled.block());
        }
        if (turnLine != null) {
            user.append("\n\n").append(turnLine);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(model)));
        messages.addAll(store.window(model, historyWindow));
        Map<String, Object> userMsg = Map.of("role", "user", "content", user.toString());
        messages.add(userMsg);
        store.append(model, userMsg);
        rec.user(user.toString());

        // ② 工具循环
        List<Map<String, Object>> tools = toolSchemas(core);
        String finalText = "";
        for (int round = 1; round <= maxRounds; round++) {
            Map<String, Object> assistant = client.chat(model, messages, tools);
            store.append(model, assistant);
            messages.add(assistant);
            rec.model(str(assistant.get("content")));
            Object calls = assistant.get("tool_calls");
            if (!(calls instanceof List<?> callList) || callList.isEmpty()) {
                finalText = String.valueOf(assistant.get("content"));
                break;
            }
            for (Object o : callList) {
                String result = executeTool(core, model, o);
                // 记录时按 callObj 取名字/参数——executeTool 内部已解析过一遍，但那里没有返回值，
                // 复制解析成本极低，且能保证"记录的就是实际派发的那个调用"
                Map<?, ?> fn = (Map<?, ?>) ((Map<?, ?>) o).get("function");
                rec.tool(str(fn.get("name")), str(fn.get("arguments")), result);
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", str(((Map<?, ?>) o).get("id")));
                toolMsg.put("content", result == null ? "" : result);
                messages.add(toolMsg);
                store.append(model, toolMsg);
            }
            if (round == maxRounds) {
                finalText = "（轮次预算 " + maxRounds + " 耗尽，强制收束）";
                rec.note(finalText);
            }
        }

        // ③ 收尾：真被模型看到了才记账（推成功才 ack——同一铁律）
        if (pulled != null && pulled.count() > 0) {
            ChatRuntime.ack(model, pulled.lastSeq());
        }
        WakeBridge.consumeTurnLine(model);
        return finalText.isEmpty() ? "无最终文本" : finalText;
    }

    // ------------------------------------------------------------- 工具执行

    /** 在服务器线程上执行工具（LLM 调用在工作线程；dispatch 的事件/账本要服务器线程）。 */
    private static String executeTool(SnowLeopard core, String model, Object callObj) {
        Map<?, ?> call = (Map<?, ?>) callObj;
        String name = str(((Map<?, ?>) call.get("function")).get("name"));
        String argsJson = str(((Map<?, ?>) call.get("function")).get("arguments"));
        Entry entry = TOOLS.get(name);   // 暴露名（重名带组前缀）→ 组短名+真名
        if (entry == null) {
            return "Error: 未知工具 " + name + "（清单里没有）";
        }
        // **暴露名只用于"对外清单的键"，进执行面的必须是注册真名**（2026-10-24 05:20 (UTC+8)）：
        // 重名工具在 LLM 侧叫 sense_status，执行面 switch 的却是 status——把暴露名传下去会落到
        // default 分支报"XX 组无此工具"，四个 status 就成了"清单里看得见、一调用就失败"。
        // 这也是"暴露名与 usage 字符串同形"的反面：两处口径分家，转换只在这一个地方做。
        ToolRegistry.Tool tool = entry.tool();
        Map<String, Object> params;
        try {
            params = argsJson == null || argsJson.isBlank()
                    ? Map.of() : Json.parseObject(argsJson);
        } catch (RuntimeException e) {
            return "Error: 工具参数不是合法 JSON: " + e.getMessage();
        }
        Result r = dispatchOnServer(model, entry.group(), tool.name(), params);
        return switch (r) {
            case Result.Ok o -> "Ok: " + o.data();
            case Result.Warn w -> "Warn: " + w.data() + "（" + w.desc() + "）";
            case Result.Severe s -> "Severe: " + s.data() + "（" + s.desc() + "）";
            case Result.Error e -> "Error: " + e.desc();
        };
    }

    /** 工具名 → (组短名, 工具)。重名工具跳过后者（清单构建时记 WARN）。 */
    private static final Map<String, Entry> TOOLS = new java.util.concurrent.ConcurrentHashMap<>();

    /** usage 里的调用签名前缀（如 {@code sense.status(}）——换成暴露名后模型才不会叫错。 */
    private static final java.util.regex.Pattern CALL_SIG =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*\\(");

    private record Entry(String group, ToolRegistry.Tool tool) {
    }

    private static volatile boolean toolsBuilt;

    private static void buildToolIndex(SnowLeopard core) {
        if (toolsBuilt) {
            return;
        }
        // 两遍扫描：**重名的工具一律带组前缀暴露**（status 在 sense/entity_self/view/interact 各一——
        // 旧做法"先到先得"会让 view_status/interact_status 对模型不可见，2026-10-24 用户点名修掉）
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (ToolRegistry.Group g : core.registry().groups()) {
            for (ToolRegistry.Tool t : g.tools()) {
                counts.merge(t.name(), 1, Integer::sum);
            }
        }
        for (ToolRegistry.Group g : core.registry().groups()) {
            String shortGroup = g.full().substring(g.full().lastIndexOf(':') + 1);
            for (ToolRegistry.Tool t : g.tools()) {
                // 前缀用下划线：OpenAI 兼容端点的 function.name 只允许 [a-zA-Z0-9_-]
                // （点号会被 DeepSeek/MiMo 400 "does not match pattern"——2026-10-24 04:30 踩过）
                String exposed = counts.getOrDefault(t.name(), 0) > 1
                        ? shortGroup + "_" + t.name() : t.name();
                Entry prev = TOOLS.putIfAbsent(exposed, new Entry(shortGroup, t));
                if (prev != null) {
                    SnowLeopardMod.LOG.warn("[雪豹框架][host] 暴露名仍冲突 {}（{} 与 {}），后者不进清单",
                            exposed, prev.group(), shortGroup);
                }
            }
        }
        toolsBuilt = true;
        SnowLeopardMod.LOG.info("[雪豹框架][host] 工具清单 {} 个（重名已带组前缀）", TOOLS.size());
    }

    /**
     * 暴露名 → 注册真名（自检用）。返回 null = 该名字不在清单里。
     *
     * <p>这条是给验收断言用的**唯一对外缝**：LLM 侧叫 {@code sense_status}，执行面只认 {@code status}，
     * 两边由 {@link #executeTool} 在调用时换。没有这条缝，"暴露名被直接发到执行面"这种故障
     * 只能等模型碰巧去调那四个工具才暴露（1.8.2→1.9.2 整整两版没人看见）。
     */
    public static String realNameOf(String exposed) {
        SnowLeopard core = SnowLeopardMod.core();
        if (core == null || exposed == null) {
            return null;
        }
        buildToolIndex(core);
        Entry e = TOOLS.get(exposed);
        return e == null ? null : e.tool().name();
    }

    /** OpenAI 工具清单（由注册表生成；params 的扁平"参数名→说明"形状转成 JSON Schema）。 */
    private static List<Map<String, Object>> toolSchemas(SnowLeopard core) {
        buildToolIndex(core);
        List<Map<String, Object>> out = new ArrayList<>();
        // **function.name 必须用暴露名（含组前缀）**——用原始注册名会让四个 status 同名，
        // DeepSeek/MiMo 直接 400 "Tool names must be unique"（2026-10-24 04:02–04:21 踩过：
        // 故障转移兜到 stepfun，优先级第 1 的 DeepSeek 整整一轮没真正服务过）
        for (Map.Entry<String, Entry> ee : TOOLS.entrySet()) {
            Entry e = ee.getValue();
            Map<String, Object> props = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (Map.Entry<String, Object> pe : e.tool().params().entrySet()) {
                if (pe.getKey().equals("type")) {
                    continue;
                }
                if (pe.getKey().equals("required") && pe.getValue() instanceof List<?> l) {
                    required.addAll(l);
                    continue;
                }
                props.put(pe.getKey(), Map.of("type", "string",
                        "description", String.valueOf(pe.getValue())));
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", props);
            if (!required.isEmpty()) {
                parameters.put("required", required);
            }
            // **描述里的调用签名必须与暴露名同形**（否则模型按 "sense.status()" 学到名字，
            // 调出来的却是 sense_status / status 两种之一，两边都对不上——2026-10-24 05:20 (UTC+8)）：
            // usage 是给人看的 DSML（sense.status()），暴露名是给 API 的键（sense_status），
            // 这里把签名前缀统一换成暴露名，**转换只此一处**，执行面仍只认注册真名。
            String usage = e.tool().usage() == null ? "" : CALL_SIG.matcher(e.tool().usage())
                    .replaceFirst(ee.getKey() + "(");
            out.add(Map.of("type", "function", "function", Map.of(
                    "name", ee.getKey(),
                    "description", e.tool().summary() == null ? usage
                            : e.tool().summary() + "（" + usage + "）",
                    "parameters", parameters)));
        }
        return out;
    }

    private static Result dispatchOnServer(String model, String group, String tool,
                                           Map<String, Object> params) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return Result.error("无服务器上下文");
        }
        if (server.isSameThread()) {
            return EntityToolsRuntime.dispatch(model, group, tool, params);
        }
        AtomicReference<Result> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        server.execute(() -> {
            try {
                ref.set(EntityToolsRuntime.dispatch(model, group, tool, params));
            } catch (RuntimeException e) {
                ref.set(Result.error("工具抛出 " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                return Result.error("工具执行超时（30s）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("被中断");
        }
        Result r = ref.get();
        return r != null ? r : Result.error("工具无结果");
    }

    // ------------------------------------------------------------- 上下文

    private static String systemPrompt(String model) {
        StringBuilder b = new StringBuilder();
        b.append("你是运行在 Minecraft 服务器里的自主模型。你的模型编号是 ").append(model);
        String name = ChatRuntime.displayName(model);
        if (!name.equals(model)) {
            b.append("，对外名称是「").append(name).append("」");
        }
        // **世界事实只读权威，不写死**（2026-10-24 05:35 (UTC+8)）：
        // 这里原来硬编码"玩家测试区 … 地板 y=-64"，而实际记录是 -57、模型脚下是 -63——
        // 三处没有一个是对的。硬编码世界常量等于给模型喂假事实，它照着挖就会挖到虚空。
        String area = PlayerTestArea.describe();
        String areaLine = area == null
                ? "- 玩家测试区尚未建好（问位置时以你自己的坐标为准，别猜）。"
                : "- " + area + "。";
        // 载体也不是"一定有一只豹猫"：没绑定就不许说有（否则模型会去找一只不存在的身体）
        b.append(AvatarWake.hasPrimary(model)
                ? "。你的载体是一只名为「" + name + "」的豹猫外化身。\n"
                : "。你当前没有绑定载体，无法移动与破坏（让操作生效需要先让人帮你绑定载体）。\n");
        b.append("""
                行为准则：
                - 要对人说话时用 chat_send（会带 [雪豹·%s] 前缀显示在聊天栏）；一次说清，不要刷屏。
                - 工具调用返回的 Ok/Warn/Severe/Error 是真实结果，不要编造工具输出。
                - 收到"提醒"行里的未读/提及是给你的信号；处理完本轮就自然结束（不再调用工具即结束）。
                %s
                - 保持简短自然，像世界里的一个角色，不是客服机器人。
                """.formatted(name, areaLine));
        return b.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
