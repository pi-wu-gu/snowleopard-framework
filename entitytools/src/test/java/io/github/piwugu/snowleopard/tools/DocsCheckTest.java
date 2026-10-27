package io.github.piwugu.snowleopard.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import snowleopard.core.registry.ToolRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档漂移检查（2026-10-24 06:35 (UTC+8) 新增，照抄核心 DocsCheck 的思路）。
 *
 * <p>为什么框架也要有：框架侧此前**没有任何文档检查器**，于是 2026-10-24 那轮审查里
 * 五条文档漂移全是人工翻出来的（开发流程停在 v1.7.0 而仓库已 v1.9.2；接入文档写 32 工具
 * 却只列 18 个；AI 执行端设计写的落盘文件名与形态和代码不一样……）。
 * 判据与核心一致：**能对着运行时判的，一律对着运行时判**；只做可静态判定的一致性，不做宽泛扫。
 */
class DocsCheckTest {

    private static final String[] DOCS = {
            "README.md",
            "docs/开发流程-2026-10-23.md",
            "docs/工具组接入文档-2026-10-23.md",
            "docs/AI执行端设计-2026-10-24.md",
            "docs/开发说明-2026-10-23.md",
            "docs/客户端验收通道-2026-10-23.md",
            "docs/工具清单-2026-10-27.md",
    };

    /** 工具清单（新增/改名工具必须同步改表）。 */
    private static final String INVENTORY = "docs/工具清单-2026-10-27.md";

    private static Path root() {
        Path p = Path.of("").toAbsolutePath().normalize();
        // :entitytools:test 的工作目录是 <根>/entitytools——往上找到带 settings.gradle 的那一层
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))) {
                return p;
            }
        }
        throw new IllegalStateException("找不到仓库根（工作目录 " + Path.of("").toAbsolutePath() + "）");
    }

    private static String read(String rel) throws IOException {
        Path f = root().resolve(rel);
        assertTrue(Files.isRegularFile(f), "文档存在: " + rel);
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    @Test
    void everyDocExistsAndCarriesTimestamp() throws IOException {
        for (String rel : DOCS) {
            String txt = read(rel);
            assertTrue(txt.contains("(UTC+8)"), rel + " 带时间戳记录（用户全局偏好）");
        }
        String readme = read("README.md");
        assertTrue(readme.contains("几乎完全由 AI 完成"), "README 的 AI 声明在（用户硬要求）");
    }

    /** 工具数对着运行时判：文档写的数字必须等于 ToolSchemas 里真的那些。 */
    @Test
    void toolCountsMatchRuntime() throws IOException {
        int tools = 0;
        tools += ToolSchemas.SENSE_TOOLS.size();
        tools += ToolSchemas.ENTITY_TOOLS.size();
        tools += ToolSchemas.SELF_TOOLS.size();
        tools += ToolSchemas.VIEW_TOOLS.size();
        tools += ToolSchemas.INTERACT_TOOLS.size();
        tools += ToolSchemas.MOVE_TOOLS.size();
        tools += ToolSchemas.ITEM_TOOLS.size();
        tools += ToolSchemas.WORLD_TOOLS.size();
        tools += ToolSchemas.WATCH_TOOLS.size();
        tools += ToolSchemas.CHAT_TOOLS.size();
        tools += ToolSchemas.AVATAR_TOOLS.size();
        tools += ToolSchemas.CRAFT_TOOLS.size();
        tools += ToolSchemas.VISION_TOOLS.size();

        String doc = read("docs/工具组接入文档-2026-10-23.md");
        assertTrue(doc.contains(GroupNames.ALL.size() + " 组 " + tools + " 工具"),
                "接入文档的工具口径 == 运行时（" + GroupNames.ALL.size() + " 组 " + tools + " 工具）");
        // 内置六组的清单必须真的在文档里（此前只列了四组 18 个，协作面两组对外等于没有清单）
        for (String g : List.of("builtin:core:wake", "builtin:core:group")) {
            assertTrue(doc.contains(g), "接入文档列了 " + g + " 的清单");
        }
        // 每组的工具名都进得去清单（不许"标题说 32、表里 18"）
        List<String> missingTools = new ArrayList<>();
        for (String g : List.of("sense", "entity", "entity_self", "view", "interact", "move",
                "item", "world", "watch", "chat", "avatar", "craft")) {
            String head = "### " + g + "（";
            if (!doc.contains(head)) {
                missingTools.add(g);
            }
        }
        assertTrue(missingTools.isEmpty(), "接入文档逐组都有小节：" + missingTools);
    }

    /**
     * 工具清单必须列全（2026-10-27）：口径数字 + 每组全名 + 每个工具名 + 实现路径列。
     *
     * <p>为什么用"文档断言"而不是只靠人工：v1.12.9 的教训是"清单教一套、实现接一套"——
     * 工具定义齐了但没挂进清单，四个版本都没人发现。表与 {@code ToolSchemas} 同源校验，
     * 新增工具忘了改表就是构建红，而不是等模型问"你能看到什么"。
     */
    @Test
    void toolInventoryListsEveryTool() throws IOException {
        String doc = read(INVENTORY);
        List<List<ToolRegistry.Tool>> all = List.of(
                ToolSchemas.SENSE_TOOLS, ToolSchemas.ENTITY_TOOLS, ToolSchemas.SELF_TOOLS,
                ToolSchemas.VIEW_TOOLS, ToolSchemas.INTERACT_TOOLS, ToolSchemas.MOVE_TOOLS,
                ToolSchemas.ITEM_TOOLS, ToolSchemas.WORLD_TOOLS, ToolSchemas.WATCH_TOOLS,
                ToolSchemas.CHAT_TOOLS, ToolSchemas.AVATAR_TOOLS, ToolSchemas.VISION_TOOLS,
                ToolSchemas.CRAFT_TOOLS);
        int tools = all.stream().mapToInt(List::size).sum();
        assertTrue(doc.contains(GroupNames.ALL.size() + " 组 " + tools + " 工具"),
                "清单口径 == 运行时（" + GroupNames.ALL.size() + " 组 " + tools + " 工具）");
        for (String g : GroupNames.ALL) {
            assertTrue(doc.contains("mod:snowleopard:" + g), "清单列了组 mod:snowleopard:" + g);
        }
        List<String> missing = new ArrayList<>();
        for (List<ToolRegistry.Tool> list : all) {
            for (ToolRegistry.Tool t : list) {
                if (!doc.contains(t.name())) {
                    missing.add(t.name());
                }
            }
        }
        assertTrue(missing.isEmpty(), "清单列全每个工具：" + missing);
        assertTrue(doc.contains("EntityToolsModule.java#"), "清单带实现路径列（不是只有名字）");
    }

    /** 版本链：文档写的 core 版本 == gradle.properties 里的（两处不同步就是"内嵌了旧核心"）。 */
    @Test
    void versionChainMatchesGradleProperties() throws IOException {
        String props = Files.readString(root().resolve("gradle.properties"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("snowleopard_core_version=(\\S+)").matcher(props);
        assertTrue(m.find(), "gradle.properties 有 snowleopard_core_version");
        String coreVer = m.group(1);
        Matcher mv = Pattern.compile("mod_version=(\\S+)").matcher(props);
        assertTrue(mv.find(), "gradle.properties 有 mod_version");
        String modVer = mv.group(1);

        String devNotes = read("docs/开发说明-2026-10-23.md");
        assertTrue(devNotes.contains(coreVer),
                "开发说明的版本链写的是当前核心版本 " + coreVer + "（不是历史版本）");

        // 每个发布的版本都要在开发流程里留一条记录（否则流水断了没人知道这版做了什么）
        String flow = read("docs/开发流程-2026-10-23.md");
        assertTrue(flow.contains("v" + modVer),
                "开发流程有 v" + modVer + " 的归档记录（发版即记账）");
    }

    /** 反引号里的类名必须在源码树里真的存在（防文档教已删的类）。 */
    @Test
    void backtickedClassNamesExist() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String rel : DOCS) {
            String txt = read(rel);
            Matcher mt = Pattern.compile("`([A-Z][A-Za-z0-9]{2,})`").matcher(txt);
            while (mt.find()) {
                String cls = mt.group(1);
                if (!classExists(cls)) {
                    missing.add(rel + ":" + cls);
                }
            }
        }
        assertTrue(missing.isEmpty(), "反引号类名都在源码树里：" + missing);
    }

    /** 废弃口径不许再出现在文档里（退役一个登记一个）。 */
    @Test
    void noRetiredClaims() throws IOException {
        String[] retired = {
                "本轮没能在这台机器上自动跑完",   // 客户端验收通道：00:10 已证伪，改为"通道已打通"（史料里也不许原样挂着，
                                                 // 否则被人直接复制出去当现状）
                "rounds.jsonl",                    // AI 执行端：实现是 history.jsonl
                "工具组 15 个",                     // M10 时期的旧计数（现 6+12=18）
        };
        List<String> hits = new ArrayList<>();
        for (String rel : DOCS) {
            String txt = read(rel);
            for (String s : retired) {
                if (txt.contains(s)) {
                    hits.add(rel + ":" + s);
                }
            }
        }
        assertTrue(hits.isEmpty(), "文档不再教已作废的口径：" + hits);
    }

    /**
     * 类名是否存在（按**简单名**判，跨三个来源）。
     *
     * <p>框架文档里反引号包的不只是本仓的类，还有核心（{@code Compressor} / {@code LockFileRuntime}）、
     * MC/NeoForge（{@code Ocelot} / {@code TitleScreen} / {@code ItemEntity}）与 JDK（{@code HttpClient}）。
     * 只扫本仓源码树会把这些都误判成"悬空类名"（宽规则害人的老教训），
     * 所以本仓按文件名、外部按**测试类路径上的 .class** 判——它们本来就在 classpath 上。
     */
    private static boolean classExists(String cls) {
        if (KNOWN.isEmpty()) {
            collectKnown();
        }
        return KNOWN.contains(cls);
    }

    /**
     * 外部类名白名单（MC / NeoForge / JDK 的）。
     *
     * <p>为什么是白名单而不是"扫类路径"：Gradle 测试 worker 的 {@code java.class.path} 指向 Gradle 自己的
     * 启动 jar，MC/NeoForge 根本不在里面，扫出来全是误判。白名单的代价是"新提一个外部类要加一行"，
     * 换来的是**本仓类名永远真判**——外部类改名是别人的事，本仓改名才是我们要拦的漂移。
     */
    private static final java.util.Set<String> EXTERNAL = java.util.Set.of(
            // MC
            "Ocelot", "OcelotModel", "LivingEntity", "ItemEntity", "TitleScreen", "FakePlayer",
            "MinecraftServer", "ServerLevel", "ServerPlayer", "ServerChatEvent", "SimpleContainer",
            "CraftingInput", "CraftingRecipe", "Ingredient", "BlockPos", "ItemStack", "Container",
            "LivingIncomingDamageEvent", "PlayerInteractEvent", "ServerStarted", "MISC",
            "Mob", "AttributeMap", "BatModel",
            // NeoForge
            "DeferredRegister", "BootstrapLauncher", "RegisterCapabilitiesEvent", "NoAi",
            // JDK
            "HttpClient", "Json", "Number");

    private static final java.util.Set<String> KNOWN = new java.util.HashSet<>();

    private static void collectKnown() {
        // ① 本仓源码（还没编译的也算：文档可以提前提新类）
        Path r = root();
        for (String tree : new String[]{"shell/src/main/java", "entitytools/src/main/java",
                "entitytools/src/test/java"}) {
            Path dir = r.resolve(tree);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(p -> {
                            String n = p.getFileName().toString();
                            KNOWN.add(n.substring(0, n.length() - 5));
                        });
            } catch (IOException ignored) {
                // 扫不到就不扫（宁漏误判，不假失败）
            }
        }
        // ② 核心 jar（本仓 libs/m2 下按版本目录 glob——核心的类都在里面）
        Path m2 = r.resolve("libs/m2/snowleopard/core");
        if (Files.isDirectory(m2)) {
            try (var walk = Files.walk(m2)) {
                walk.filter(x -> x.getFileName().toString().endsWith(".jar"))
                        .forEach(x -> collectJar(x));
            } catch (IOException ignored) {
                // 同上
            }
        }
        KNOWN.addAll(EXTERNAL);
    }

    private static void collectJar(Path jarPath) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                if (n.endsWith(".class")) {
                    KNOWN.add(n.substring(n.lastIndexOf('/') + 1, n.length() - 6));
                }
            }
        } catch (IOException ignored) {
            // 同上
        }
    }
}
