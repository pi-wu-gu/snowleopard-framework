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
import io.github.piwugu.snowleopard.BuiltinRuntime;
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
    /**
     * 单轮（一次会话）最多跑几个回合。2026-10-27 17:10 (UTC+8)：12 → **32**。
     *
     * <p>12 轮到不了"走到玩家身边 + 看一圈 + 报告"这种复合任务就烧完了（真机 16:41 那次
     * goto↔move_status 往返直接耗尽预算，模型被迫收束）。
     */
    private static volatile int maxRounds = 32;

    /** 当前正在跑的模型（右键/提及在运行中要"注入"而不是"再排一个会话"）。 */
    private static final java.util.Set<String> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 运行中收到的即时消息：不排队成新会话，直接塞进正在跑的这轮。 */
    private static final Map<String, java.util.Queue<String>> INTERJECTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int historyWindow = 40;
    /** 视觉能力（**单点计算**，见 {@link VisionCapability}）——每次会话开头重算。 */
    private static volatile VisionCapability capability =
            VisionCapability.compute(null, false, false, "AI 执行端未启用");
    /** 各模型上次见到的能力级：用来判断"能力变了要告诉模型"，只发一次。 */
    private static final java.util.Map<String, String> LAST_LEVEL =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 视觉渲染后端（P3 = stub；P4 换客户端离屏渲染）。缺省不可用，绝不假装能拍。 */
    private static volatile io.github.piwugu.snowleopard.tools.VisionBackend visionBackend =
            io.github.piwugu.snowleopard.tools.VisionBackend.NONE;
    /** 视觉配置快照（宽度/高度/预算/保留张数）。 */
    private static volatile LlmConfig.VisionSettings visionCfg = LlmConfig.VisionSettings.OFF;
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
     * 自检用：当前工作集下**会声明给模型**的工具暴露名（不外发，只给断言看）。
     *
     * <p>这条是"逐级取用真的生效"的唯一对外缝：默认应只有目录组三件，
     * `tool_help`/`tool_scope` 取用某个组之后才多出那组的工具——没有这条缝，
     * "工作集写好了但声明没跟着变"只能等模型碰巧去调才暴露。
     */
    public static List<String> declaredForTest(SnowLeopard core, String model) {
        if (core == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> s : toolSchemas(core, model)) {
            if (s.get("function") instanceof Map<?, ?> fn) {
                out.add(String.valueOf(fn.get("name")));
            }
        }
        return out;
    }

    /**
     * 视觉工具组要不要注册（装配期调用，早于 {@link #init}）。
     *
     * <p>只读配置，不初始化任何东西：`vision.enabled=false` 或没有配置文件 → 不注册，
     * 工具面回落 44。配置坏了也按关闭处理——**不能因为一个坏 json 让整个 mod 起不来**，
     * 真正的报错由 {@link #init} 如实打出来。
     */
    public static boolean visionEnabled(Path gameDir) {
        Path f = gameDir.resolve("config").resolve("snowleopard-llm.json");
        if (!java.nio.file.Files.isRegularFile(f)) {
            return false;
        }
        try {
            return LlmConfig.load(f).vision().renderAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 视觉能力紧凑串（{@code view/status} 用）。
     *
     * <p>**永远给得出话**：没启用也要说明为什么（"视觉未启用（vision.enabled=false）"），
     * 返回空串会让模型以为"这个字段没意义"而不是"我看不了"。
     */
    public static String visionStatusLine() {
        return capability.compact();
    }

    /** 工具层取后端（壳注入，见 {@code Ctx.visionBackend()}）。 */
    public static io.github.piwugu.snowleopard.tools.VisionBackend visionBackend() {
        return visionBackend;
    }

    /** 截图宽（服务端权威，客户端不许读自己的本地配置）。 */
    public static int visionWidth() {
        return visionCfg.width();
    }

    /** 截图高。 */
    public static int visionHeight() {
        return visionCfg.height();
    }

    /** 自测用：直接排一张"已就绪"的图（等价于一次极快的 capture），不经过后端。 */
    public static void visionReplayForTest(String model, String file) {
        VisionPending.replay(model, file);
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
            visionCfg = cfg.vision();
            // 后端只认 stub（P3）/ client（P4 未实现）。stub 是**假画面**，只许验收模式用
            if ("stub".equals(cfg.vision().backend()) && Boolean.getBoolean("snowleopard.clienttest")) {
                visionBackend = new StubVisionBackend(
                        m -> core == null ? null : core.ids().home(m).resolve("vision"),
                        cfg.vision()::maxCaptures, cfg.vision()::keepImages);
                SnowLeopardMod.LOG.warn("[雪豹框架][host] 视觉后端=测试桩（画面是假的，仅验收用）");
            } else if ("client".equals(cfg.vision().backend())) {
                visionBackend = new ClientVisionBackend(
                        m -> core == null ? null : core.ids().home(m).resolve("vision"),
                        cfg.vision()::maxCaptures, cfg.vision()::keepImages);
                SnowLeopardMod.LOG.info("[雪豹框架][host] 视觉后端=客户端离屏渲染"
                        + "（需同维度玩家在载体 {} 格内）", (int) ClientVisionBackend.MAX_RENDER_DIST);
            }
            // 能力级必须在后端定下之后再算（否则 L0/L2 会算错——"该 L1 报成 L0"比没能力更坏）
            capability = computeCapability(cfg);
            SnowLeopardMod.LOG.info("[雪豹框架][host] AI 执行端就绪（{} 家提供商，轮次预算 {}）",
                    cfg.providers().size(), cfg.maxRounds());
            // 降级必须可得知：这两行是**真机可 grep 的能力证据**（不许只留在注释里）
            SnowLeopardMod.LOG.info("[雪豹框架][host] 视觉能力 {}（rendered={}）", capability.compact(),
                    cfg.vision().enabled());
            SnowLeopardMod.LOG.info("[雪豹框架][host] 路由 chat={} compress={} vision={}",
                    client.plan(false), rolePlan(cfg, LlmConfig.ROLE_COMPRESS), client.plan(true));
        } catch (Exception e) {
            SnowLeopardMod.LOG.error("[雪豹框架][host] 初始化失败——AI 执行端停用: {}", e.getMessage(), e);
        }
    }

    /**
     * 算能力级（**唯一计算点**）。
     *
     * <p>渲染后端的真实可用性：
     * <ul>
     *   <li>{@code none} → 不可渲染；</li>
     *   <li>{@code stub} → 可渲染但**是假画面**，且只许客户端验收模式用（生产配置一律按不可用处理）；</li>
     *   <li>{@code client} → P4 才接入，现在按不可用处理并如实说明（**不许假装能用**）。</li>
     * </ul>
     */
    private static VisionCapability computeCapability(LlmConfig cfg) {
        String backend = cfg.vision().backend();
        boolean enabled = cfg.vision().enabled();
        // 能不能渲染以**后端真实可用性**为准，不看配置字面——配置写了 client 但没实现，
        // 就不能报 L0（那会让模型以为拍得到，然后等一张永远不来的图）
        boolean render = visionBackend != null && visionBackend.available();
        boolean stub = render && "stub".equals(backend);
        String reason = !enabled
                ? "视觉未启用（vision.enabled=false）"
                : "client".equals(backend) ? "渲染后端（客户端离屏渲染）尚未接入"
                : "none".equals(backend) ? "未配置渲染后端" : "渲染后端不可用";
        return VisionCapability.compute(client, render, stub, reason);
    }

    /** 某个角色的首选提供商（只用于日志，不触发请求）。 */
    private static String rolePlan(LlmConfig cfg, String role) {
        LlmConfig.Provider p = cfg.providerFor(role);
        return p == null ? "（未配置→按 providers 顺序）" : p.name() + "/" + p.model();
    }

    /** 启动时的预热：把 LLM 配置里的 Compressor 挂上（在 bootstrap 前调用才生效）。 */
    public static snowleopard.core.memory.Compressor compressorOrNull(Path gameDir) {
        Path f = gameDir.resolve("config").resolve("snowleopard-llm.json");
        if (!java.nio.file.Files.isRegularFile(f)) {
            return null;
        }
        try {
            LlmClient c = new LlmClient(LlmConfig.load(f));
            // role=compress：**必须待在文本模型上**。原来走 chat("", …) → start=0 → 永远用
            // providers[0]，一旦把视觉模型放首位，记忆压缩就被带偏了（2026-10-27 05:50 (UTC+8)）
            return (purpose, sourceText) -> c.complete(LlmConfig.ROLE_COMPRESS,
                    "你是记忆索引器。把源文本压缩为极简索引——每条一行，只写做了什么，" +
                    "不写细节、不写过程、不写时间戳（时间戳由外层格式自带）。" +
                    "细节已保存在原始数据中，无需在此重复。不要评论、不要客套。" +
                    "\n输出格式：每行一条，格式为「标识: 一句话概括」。" +
                    "\n示例：「round_042836: 自检三项全通过，破坏地板并补回」。",
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
        // 截图超时清扫：图永远不回来也要让模型知道失败，不能一直"稍等"
        int swept = VisionPending.sweep(visionCfg.timeoutMs());
        if (swept > 0) {
            SnowLeopardMod.LOG.warn("[雪豹框架][视觉] {} 张截图渲染超时（{}ms）", swept, visionCfg.timeoutMs());
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
        RUNNING.add(model);
        MemoryRecorder.Scope rec = MemoryRecorder.begin(core, model);
        VisionPending.beginSession(model);      // 截图预算按会话重置
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
            RUNNING.remove(model);
            // 会话结束还有在渲染的 → 转 DEFERRED（**不重试唤醒**，下次会话开头捎带）
            VisionPending.deferStillPending(model);
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
        // 降级/恢复必须说出来——**只在与该模型上次见到的级别不同时发一次**，
        // 否则每轮都刷，模型会把警示当噪音（这是"降级可得知"的第四个可见面）
        VisionCapability cap = capability;
        String prevLevel = LAST_LEVEL.put(model, cap.level().name());
        if (prevLevel != null && !prevLevel.equals(cap.level().name())) {
            String warn = cap.changeLine(prevLevel);
            user.append("\n\n").append(warn);
            rec.note(warn);
            SnowLeopardMod.LOG.info("[雪豹框架][host] 视觉能力变化 {} {} → {}（已告知模型）",
                    model, prevLevel, cap.level());
        }

        // **工作集跨会话保留**（2026-10-27 16:30 (UTC+8) 改）：
        // 原先每次会话 clear，本意是不拖长尾，实测却变成"每轮都要重新取用"——
        // 三次真机会话里两次模型直接调未声明工具（靠 history 里的名字 + 执行面全量派发兜住）。
        // 一旦将来收紧"未声明即拒绝"，这些调用会全失败。所以改成按 caller 持久，
        // 只提供显式 tool_scope remove（core 的 clear() 仍保留给测试与会话重置用）。

        List<Map<String, Object>> messages = new ArrayList<>();
        String baseSystem = systemPrompt(core, model);
        messages.add(Map.of("role", "system", "content", baseSystem));
        messages.addAll(store.window(model, historyWindow));
        Map<String, Object> userMsg = Map.of("role", "user", "content", user.toString());
        messages.add(userMsg);
        store.append(model, userMsg);
        rec.user(user.toString());
        // 上次会话遗留的截图（DEFERRED）在这里捎带——**单一注入点**，不另开一处拼
        injectVision(model, messages);

        // ② 工具循环
        // **每轮重算声明**（2026-10-27）：tool_help 会在上一轮把新组加入工作集，
        // 只在会话开始时算一次 = 解锁无效（"查了却调不了"，且不报错）
        String finalText = "";
        int dsmlRetries = 0;
        for (int round = 1; round <= maxRounds; round++) {
            drainInterjects(model, messages);
            beginRound(core, model);
            refreshSessionLine(core, model, messages, baseSystem, round);
            List<Map<String, Object>> tools = toolSchemas(core, model);
            if (round == 1) {
                SnowLeopardMod.LOG.info("[雪豹框架][host] 本轮声明 {} 个工具（工作集 {}，共 {} 组可用）",
                        tools.size(), core.exposure().signature(model), core.registry().groups().size());
            }
            // 带图请求只在多模态提供商之间转移（能力守卫在 LlmClient 里，这里只负责判定）
            Map<String, Object> assistant = client.chat(model, messages, tools, hasImagePart(messages));
            recordUsage(core, model, client);
            store.append(model, assistant);
            messages.add(assistant);
            rec.model(LlmClient.textOf(assistant.get("content")));
            Object calls = assistant.get("tool_calls");
            if (!(calls instanceof List<?> callList) || callList.isEmpty()) {
                String text = LlmClient.textOf(assistant.get("content"));
                // **伪造工具调用的检测**（2026-10-27 16:30 (UTC+8)）：模型在工具不可用/失败时
                // 会把调用写成正文（`<|CALL_START|>function chat_send {...}`）。宿主若当正常回答，
                // 玩家什么也收不到、日志里却"跑完了"——这是"假成功"家族里最难查的一种。
                if (looksLikeFakeToolCall(text)) {
                    if (dsmlRetries < 1) {
                        dsmlRetries++;
                        SnowLeopardMod.LOG.warn("[雪豹框架][host] 模型把工具调用写成了正文（未执行），已提示纠正: {}",
                                brief(text));
                        messages.add(Map.of("role", "user", "content",
                                "（系统）你上一条输出是**工具调用的文本**，宿主没有执行它。"
                                        + "要调用工具就发真正的工具调用；要说话就直接给纯文本。"));
                        continue;
                    }
                    SnowLeopardMod.LOG.warn("[雪豹框架][host] 纠正后仍出现伪造调用文本，放弃本轮: {}", brief(text));
                    rec.note("模型输出伪造的工具调用文本（未执行）");
                    text = "（模型输出为工具调用文本，未执行）";
                }
                finalText = text;
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
            // 工具跑完、下一轮 chat 之前：把**已就绪**的截图注进去（轮内注入，零等待）。
            // 这一步不做任何阻塞——图没好就留到下一轮/下次会话，绝不在这等渲染。
            injectVision(model, messages);
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
        String out = switch (r) {
            case Result.Ok o -> "Ok: " + o.data();
            case Result.Warn w -> "Warn: " + w.data() + "（" + w.desc() + "）";
            case Result.Severe s -> "Severe: " + s.data() + "（" + s.desc() + "）";
            case Result.Error e -> "Error: " + e.desc();
        };
        // **未取用却调用了**：照常执行（不假装能力不存在），但提示一句——
        // 否则下一轮它又不在清单里，模型会以为自己记错了（"能执行"与"可见"是两件事）
        String groupFull = groupFullOf(core, entry.group());
        if (groupFull != null) {
            if (!core.exposure().isActive(model, groupFull)) {
                out += "\n（该工具所属组 " + groupFull + " 还没加入工具表——需要的话 tool_scope add "
                        + entry.group() + "）";
            } else if (core.registry().isSolo(groupFull)
                    && core.exposure().markSoloNotified(model, groupFull + "." + tool.name())) {
                out += "\n（无组工具：它不隶属任何组，签名已直接给出，无需 tool_help）";
            }
        }
        return out;
    }

    /**
     * 这段文本像不像"把工具调用写成了正文"（DSML 标记）。
     *
     * <p>判据只认标记，不猜语义：出现 `<|CALL_START|>` / `<|CALL_END|>` 一律按假调用处理。
     * 判据太宽会把正常讨论误杀（模型可能正经引用这个标记），所以这里只盯"调用开始/结束"两个锚点。
     */
    public static boolean looksLikeFakeToolCall(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("<|CALL_START|>") || text.contains("<|CALL_END|>");
    }

    /** 日志用短摘要（避免把一个几百字的伪造调用整段刷进日志）。 */
    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').strip();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }

    // ------------------------------------------------------------- 即时消息与会话进度

    /** 模型是否正在跑会话（右键/提及据此决定"注入"还是"排队"）。 */
    public static boolean isRunning(String model) {
        return model != null && RUNNING.contains(model);
    }

    /**
     * 把一条即时消息塞进正在跑的会话（**不新开会话**）。
     *
     * <p>2026-10-27 17:10 (UTC+8)：真人右键时若模型正在跑，core 的唤醒闸会直接拒（"目标在运行"），
     * 玩家看到的是"唤醒被拒绝"——但玩家要的是"它现在知道我在叫它"。所以改成注入：
     * 消息排进队列，模型本轮跑完工具后就能看到，不必等这次会话结束。
     *
     * @return true = 已注入（有会话在跑）；false = 没有在跑的会话（调用方走正常唤醒）
     */
    public static boolean interject(String model, String text) {
        if (!isRunning(model) || text == null || text.isBlank()) {
            return false;
        }
        INTERJECTS.computeIfAbsent(model, k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(text);
        SnowLeopardMod.LOG.info("[雪豹框架][host] 会话进行中收到即时消息（下一轮可见）: {}", brief(text));
        return true;
    }

    private static void drainInterjects(String model, List<Map<String, Object>> messages) {
        java.util.Queue<String> q = INTERJECTS.get(model);
        if (q == null) {
            return;
        }
        String line;
        while ((line = q.poll()) != null) {
            Map<String, Object> msg = Map.of("role", "user", "content", line);
            messages.add(msg);
            store.append(model, msg);
        }
    }

    /**
     * 每轮刷新 system 里的【会话进度】——模型据此判断"还够不够把这件事做完"。
     *
     * <p>2026-10-27 17:10 (UTC+8)：此前模型**完全不知道**剩余轮次与 token（只有主动调
     * {@code session_usage} 才看得到，而它通常不会调）。现在每轮把 core 的
     * {@code SessionGate.injectionLine()} 拼进 system，轮次上限用宿主自己的 {@code maxRounds}。
     */
    private static void refreshSessionLine(SnowLeopard core, String model,
                                           List<Map<String, Object>> messages, String baseSystem,
                                           int round) {
        SnowLeopard.CallerScope sc = BuiltinRuntime.scope(model);
        String line;
        if (sc == null) {
            line = baseSystem + "\n【会话进度】第 " + round + "/" + maxRounds + " 轮\n";
        } else {
            var u = sc.session().usage();
            // **不用 injectionLine()**：core 的预算是 unlimited，它会印出"轮次 3（无上限）"，
            // 与宿主自己的"最多 32 轮"自相矛盾。轮次上限由宿主定义，这里自己拼。
            line = baseSystem + "\n【会话进度】第 " + round + "/" + maxRounds + " 轮"
                    + " · 当轮消耗 " + u.roundTokens() + " tok"
                    + " · 上句消耗 " + u.lastUtteranceTokens() + " tok"
                    + " · 本会话累计 " + u.totalTokens() + " tok\n";
        }
        messages.set(0, Map.of("role", "system", "content", line));
        if (round == 1) {
            SnowLeopardMod.LOG.info("[雪豹框架][host] 会话进度已注入: 最多 {} 轮（token 预算 无上限）", maxRounds);
        }
    }

    private static void beginRound(SnowLeopard core, String model) {
        SnowLeopard.CallerScope sc = BuiltinRuntime.scope(model);
        if (sc != null) {
            sc.session().beginRound();
        }
    }

    /** 把这一轮的 token 用量喂给 core 的 SessionGate（让 session_usage / 进度行都是真的）。 */
    private static void recordUsage(SnowLeopard core, String model, LlmClient client) {
        SnowLeopard.CallerScope sc = BuiltinRuntime.scope(model);
        if (sc == null) {
            return;
        }
        long in = client.lastPromptTokens();
        long out = client.lastCompletionTokens();
        if (in > 0 || out > 0) {
            sc.session().recordUsage(in, out);
        }
    }

    /** 组短名 → 组全名（执行面拿到的只有短名，提示语要能给出可用的名字）。 */
    private static String groupFullOf(SnowLeopard core, String shortG) {
        for (ToolRegistry.Group g : core.registry().groups()) {
            if (shortGroup(g.full()).equals(shortG)) {
                return g.full();
            }
        }
        return null;
    }

    /** 工具名 → (组短名, 工具)。重名工具跳过后者（清单构建时记 WARN）。 */
    private static final Map<String, Entry> TOOLS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 工具名出现次数（暴露名规则的**唯一输入**，只在这里算一次）。 */
    private static final Map<String, Integer> NAME_COUNTS = new java.util.HashMap<>();

    /** usage 里的调用签名前缀（如 {@code sense.status(}）——换成暴露名后模型才不会叫错。 */
    private static final java.util.regex.Pattern CALL_SIG =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*\\(");

    private record Entry(String group, ToolRegistry.Tool tool) {
    }

    private static volatile boolean toolsBuilt;

    /** 组全名（{@code mod:snowleopard:vision}）→ 短名（{@code vision}）。 */
    private static String shortGroup(String full) {
        return full.substring(full.lastIndexOf(':') + 1);
    }

    /**
     * 暴露名规则的**单点实现**（索引构建与清单输出必须同源，否则"清单里有、执行面不认"）。
     *
     * <p>两条规则：① 同名工具一律带组前缀；② **vision 组无条件带前缀**——
     * scan/shot/show 不重名，裸暴露会得到 `shot`（听着像射击），模型联想不到"看图"。
     */
    private static String exposedName(String shortGroup, String toolName) {
        boolean force = "vision".equals(shortGroup);
        Integer c = NAME_COUNTS.get(toolName);
        return force || (c != null && c > 1) ? shortGroup + "_" + toolName : toolName;
    }

    private static void buildToolIndex(SnowLeopard core) {
        if (toolsBuilt) {
            return;
        }
        // 两遍扫描：**重名的工具一律带组前缀暴露**（status 在 sense/entity_self/view/interact 各一——
        // 旧做法"先到先得"会让 view_status/interact_status 对模型不可见，2026-10-24 用户点名修掉）
        NAME_COUNTS.clear();
        for (ToolRegistry.Group g : core.registry().groups()) {
            for (ToolRegistry.Tool t : g.tools()) {
                NAME_COUNTS.merge(t.name(), 1, Integer::sum);
            }
        }
        for (ToolRegistry.Group g : core.registry().groups()) {
            String shortGroup = shortGroup(g.full());
            for (ToolRegistry.Tool t : g.tools()) {
                // 前缀用下划线：OpenAI 兼容端点的 function.name 只允许 [a-zA-Z0-9_-]
                // （点号会被 DeepSeek/MiMo 400 "does not match pattern"——2026-10-24 04:30 踩过）
                String exposed = exposedName(shortGroup, t.name());
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

    /**
     * OpenAI 工具清单：**只声明工作集里的工具**（2026-10-27 起）。
     *
     * <p>与 v1.12.10 的"全量声明 + 排序"不同：排序治的是"排在末尾被无视"，
     * 而工作集外**根本不在清单里**——`tool_help` 查过的组下一轮才出现（解锁可回），
     * 这样既省 token（79 个全量 ≈ 7–9K token/轮），也让"该用哪个"不再靠位置暗示。
     *
     * <p>元工具组（目录）恒在：它是拿回别的组唯一入口，裁掉就是死锁（见 core 的 ToolExposure）。
     */
    private static List<Map<String, Object>> toolSchemas(SnowLeopard core, String model) {
        buildToolIndex(core);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolRegistry.Group g : core.registry().groups()) {
            if (!core.exposure().isActive(model, g.full())) {
                continue;
            }
            String shortG = shortGroup(g.full());
            for (ToolRegistry.Tool t : g.tools()) {
                if (!core.exposure().allows(model, g.full(), t.name())) {
                    continue;   // 组内筛选：只取了一个工具，其余不给签名
                }
                String exposed = exposedName(shortG, t.name());
                Entry e = TOOLS.get(exposed);
                if (e != null) {
                    out.add(toSchema(exposed, e));
                }
            }
        }
        return out;
    }

    /** 单个工具 → OpenAI function schema（**name 一律用暴露名**）。 */
    private static Map<String, Object> toSchema(String exposed, Entry e) {
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
                .replaceFirst(exposed + "(");
        return Map.of("type", "function", "function", Map.of(
                "name", exposed,
                "description", e.tool().summary() == null ? usage
                        : e.tool().summary() + "（" + usage + "）",
                "parameters", parameters));
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

    private static String systemPrompt(SnowLeopard core, String model) {
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
        // 视觉能力（L0–L3 单点计算的唯一来源）——**会话开头就定调**，
        // 免得模型先入为主地以为"我能看到"（假成功家族）
        b.append(capability.promptLine());
        // **工具清单由 core 生成**（2026-10-27）：分组用法 + 按来源分节的组清单 + 无组工具。
        // 组内工具一个都不列——这是"逐级查询"的前提；文案住 core，多宿主共用，避免各写一版漂移。
        b.append(core.registry().injectionBlock());
        return b.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 把待投递的视觉结果注进当前 {@code messages}（**不写 history、不进记忆**）。
     *
     * <p>两条纪律：
     * <ul>
     *   <li>**不阻塞**：只取已经就绪的，没好的留到下一轮/下次会话——绝不等渲染；</li>
     *   <li>**不落盘历史**：base64 进 `history.jsonl` 会爆（那个文件 append-only 只增不减），
     *       也不进 `MemoryRecorder`（400 字符截断会把 data URI 变成垃圾）。</li>
     * </ul>
     */
    private static void injectVision(String model, List<Map<String, Object>> messages) {
        List<VisionPending.Rec> recs = VisionPending.drain(model);
        if (recs.isEmpty()) {
            return;
        }
        boolean canSee = capability.canSeeImages();
        for (VisionPending.Rec r : recs) {
            if (r.state() == VisionPending.State.FAILED
                    || r.state() == VisionPending.State.DROPPED) {
                messages.add(Map.of("role", "user", "content", "（系统注入）"
                        + (r.state() == VisionPending.State.FAILED
                        ? "截图 #" + r.reqId() + " 失败：" + r.reason()
                        : r.reason())));
                continue;
            }
            messages.add(visionMessage(r, canSee));
            SnowLeopardMod.LOG.info("[雪豹框架][视觉] 注入 {} → {}（canSee={}）",
                    r.reqId(), r.file(), canSee);
            // **图不进 history，但要留一行文本凭据**（2026-10-27 16:55 (UTC+8) 真机实证）：
            // 只留 `note=已排队` 的话，模型复盘时无法自证"我确实收过图"，
            // 于是会把自己上一轮**看过并答对**的画面判成"我编的"并撤回。
            // 这里只写一行字（不含 base64，history 不会爆），复盘时一眼能查到。
            String shotName = r.file() == null ? r.reqId()
                    : java.nio.file.Path.of(r.file()).getFileName().toString();
            store.append(model, Map.of("role", "user", "content",
                    "（历史记录）截图 " + shotName + " 的画面已注入给你；"
                            + "图像数据不落盘（history 只留这一行），要再看用 vision_show。"));

        }
    }

    private static Map<String, Object> visionMessage(VisionPending.Rec r, boolean canSee) {
        String name = r.file() == null ? "?"
                : java.nio.file.Path.of(r.file()).getFileName().toString();
        String head = "（系统注入）你申请的截图已就绪：" + name;
        if (r.source() != null && r.source().contains("stub")) {
            head += "（**测试桩，不是真实世界**）";
        }
        if (!canSee) {
            // 没有视觉能力就必须**明说**：给它 base64 等于让它读到一堆乱码，比不给更坏
            return Map.of("role", "user", "content", head
                    + "。但当前模型没有查看图像的能力，你看不到画面内容——"
                    + "需要空间信息请用 vision/scan，并说明这是推断。");
        }
        try {
            byte[] png = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(r.file()));
            String b64 = java.util.Base64.getEncoder().encodeToString(png);
            return Map.of("role", "user", "content", List.of(
                    // **必须说清"下面就是图本身"**：只说"截图已就绪：xxx.png"，模型会以为那是个路径，
                    // 于是再去申请一张（2026-10-27 实测：step-1o-turbo-vision 会照做 vision_shot）
                    Map.of("type", "text", "text", head
                            + "。**下面这张图就是画面本身，你可以直接看到内容**；看完直接作答，不要再申请同一张。"),
                    Map.of("type", "image_url",
                            "image_url", Map.of("url", "data:image/png;base64," + b64))));
        } catch (Exception e) {
            return Map.of("role", "user", "content", head + "，但读取失败：" + e.getMessage());
        }
    }

    /**
     * 消息里是否含图片 part（{@code content} 为数组且其中一项带 {@code image_url}）。
     * 这决定本轮 chat 走不走视觉提供商——**判定与路由必须同源**，否则会出现
     * "带着图去问文本模型"（P0 风险 A）。
     */
    private static boolean hasImagePart(List<Map<String, Object>> messages) {
        for (Map<String, Object> m : messages) {
            if (m.get("content") instanceof List<?> parts) {
                for (Object p : parts) {
                    if (p instanceof Map<?, ?> pm && pm.containsKey("image_url")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
