package io.github.piwugu.snowleopard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import snowleopard.core.SnowLeopard;
import snowleopard.core.names.Names;

import io.github.piwugu.snowleopard.tools.EntityToolsProvider;

/**
 * 雪豹框架（Snow Leopard Framework）—— 把雪豹决策核心嵌进 NeoForge 的<b>薄壳</b>。
 *
 * <p><b>架构边界（双件套，2026-10-23）</b>：决策、工具、记忆、权限、确认全在
 * {@code snowleopard-core}（零 MC）；本壳只做三件事——
 * ① 装配门面（{@link SnowLeopard#bootstrap}，S1–S6）；
 * ② SPI 工具组发现（{@code loadDiscovered}——NeoForge 的 ModClassLoader 可见全部模组类，
 * 这正是"装载器拼限定名"的类加载器交接点）；
 * ③ 启动报告入日志。<b>壳里出现决策代码即架构漂移</b>。
 *
 * <p><b>参考边界</b>（2026-10-23 04:08 用户裁决）：公开仓库（aiplayer 等）仅作局部参考、
 * 架构不照搬；架构以雪豹决策核心自身设计为准（工具组支持可行性分析 §四 + 门面 S1–S6）。
 *
 * <p>双端加载（BOTH）：无界面的决策依赖，客户端与专用服务器同一装配路径。
 * 构造期装配是安全的——本壳不依赖资源包/渲染/语言（与 friday 的客户端宿主不同）。
 */
@Mod(SnowLeopardMod.MODID)
public final class SnowLeopardMod {

    public static final String MODID = "snowleopard";

    public static final Logger LOG = LogUtils.getLogger();

    private static volatile SnowLeopard core;

    public SnowLeopardMod(ModContainer container) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path storage = gameDir.resolve("snowleopard");
            Path permFile = gameDir.resolve("config").resolve("snowleopard-permissions.json");

            // S3：权限政策必须显式——有权限文件走文件，没有就 allowAll 并点名（不静默继承）
            SnowLeopard.Options opts;
            if (Files.exists(permFile)) {
                opts = SnowLeopard.Options.fromFiles(permFile, null);
                LOG.info("[雪豹框架] 权限文件: {}", permFile);
            } else {
                opts = SnowLeopard.Options.allowAll();
                LOG.warn("[雪豹框架] 未找到权限文件 {}——以 allowAll 显式装配；生产环境请配置权限文件", permFile);
            }

            // 运行态权威（裁决：双保险 = 进程内表 + shared/run/<id>.lock）——壳与核心共用同一实例，
            // "会话是否在跑"与"能否被唤起"同源，避免两处各自推断（M5）
            snowleopard.core.wake.LockFileRuntime runtime =
                    new snowleopard.core.wake.LockFileRuntime(storage.resolve("shared"));
            opts = opts.withRuntime(runtime);
            WakeBridge.install(runtime);

            core = SnowLeopard.bootstrap(storage, opts);

            List<String> loaded = core.loader().loadDiscovered(
                    Names.SourceType.MOD, SnowLeopardMod.class.getClassLoader());

            // entitytools 骨架显式装配（M0：壳=装配点；不走 ServiceLoader，装配路径一眼可见）
            List<String> wired = new java.util.ArrayList<>();
            for (snowleopard.core.spi.ToolGroupProvider p : EntityToolsProvider.skeletons()) {
                String full = core.loader().load(p, Names.SourceType.MOD);
                if (full != null) {
                    wired.add(full);
                }
            }

            core.startupReport().forEach(line -> LOG.info("[雪豹框架] {}", line));
            LOG.info("[雪豹框架] SPI 发现 {} 个: {}；entitytools 骨架 {} 个: {}",
                    loaded.size(), loaded, wired.size(), wired);

            // 死亡流：绑定实体死亡 → 感官自动关闭（裁决 05:10）
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(EntityToolsRuntime::onLivingDeath);
            // 攻击源动态：受伤 → ATTACKED 记录 + 攻击者自动入册（M6）
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(EntityToolsRuntime::onLivingHurt);
            // 每 tick：视角锁定/挖掘/移动推进/动态自动记录（M2–M6 的执行面）
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(EntityToolsRuntime::onServerTick);
            // 每 10 秒：汇总行 → 聊天栏（M11；推成功才标已提示）
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ChatPusher::onServerTick);
            // 客户端验收模式（-Dsnowleopard.clienttest=true）：只跑"绑定中立生物 + 推送一行"。
            // 全量自测收尾会 server.halt(false)，在集成服务器上等于把客户端关掉，所以必须分道
            if (Boolean.getBoolean("snowleopard.clienttest")) {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientSmokeTest::onPlayerJoin);
                LOG.info("[雪豹框架] 客户端验收模式：跳过全量自测（不建塔、不 halt）");
                String auto = System.getProperty("snowleopard.autoconnect", "");
                LOG.info("[雪豹框架] 自动连接目标: {}", auto.isBlank() ? "（未设置）" : auto);
            } else {
                // M1 真机场景自测：M1–M3 同步段 + M4 tick 驱动段（开发流程验收）
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SelfTestScenario::onServerStarted);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SelfTestScenario::onServerTick);
                SelfTest.run(core);
            }
        } catch (Throwable t) {
            // 装配失败只该让"工具组能力不可用"，不该让整个模组加载失败
            LOG.error("[雪豹框架] 核心装配失败——决策依赖不可用", t);
        }
    }

    /**
     * 其他模组的入口：取核心门面（root 级）。装配失败时返回 null，调用方须判空。
     * caller 级能力经 {@code core().forCaller(id)} 进入各模型的家。
     */
    public static SnowLeopard core() {
        return core;
    }
}
