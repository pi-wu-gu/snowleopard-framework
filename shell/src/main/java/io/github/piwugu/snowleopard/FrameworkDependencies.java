package io.github.piwugu.snowleopard;

/**
 * 框架的**硬依赖校验**（2026-10-28）：缺依赖必须拒绝加载，不许"起得来但跑不动"。
 *
 * <p>为什么要自己写：{@code jarJar} 的 {@code metadata.json} 只有
 * identifier / version / path / isObfuscated 四个字段，**没有 mandatory**——
 * FML 不会因为内嵌库缺失或版本不符而拒绝加载，模组照样启动，然后在第一次用到时才炸
 * （2026-10-28 实证：{@code EntityToolsModule.<clinit>} 找不到 {@code com.mojang.authlib.GameProfile}，
 * 整个工具层永久 `Could not initialize class`，但游戏外看一切正常）。
 *
 * <p>判定拆成两个**纯函数**（只读传入值），真机断言才能直接打：
 * {@link #coreIssue(int, String)} 判 core 契约与发布版本，
 * {@link #classIssue(String, ClassLoader)} 判类是否真的可解析。
 */
public final class FrameworkDependencies {

    private FrameworkDependencies() {
    }

    /** 宿主要求的契约版本（与 core 的 Version.CORE_API 对齐；破坏性变更才升）。 */
    public static final int EXPECTED_CORE_API = 1;

    /**
     * core 契约 + 版本判定。<b>返回 null 才是通过</b>，否则返回人类可读的拒绝原因。
     *
     * @param apiFromJar   从**运行中的 core jar** 反射读到的 CORE_API（不要直接引用常量——
     *                     {@code static final int} 会被 javac 内联，读出来永远是编译时的值，等于没检查）
     * @param releaseFromJar core jar 清单里的 Implementation-Version
     */
    public static String coreIssue(int apiFromJar, String releaseFromJar) {
        if (apiFromJar != EXPECTED_CORE_API) {
            return "雪豹核心契约不匹配：需要 core_api=" + EXPECTED_CORE_API
                    + "，实际 " + apiFromJar + "（核心与框架版本不配套，拒绝加载）";
        }
        if (releaseFromJar == null || releaseFromJar.isBlank() || "unknown".equals(releaseFromJar)) {
            return "雪豹核心缺少发布版本（清单里没有 Implementation-Version）——"
                    + "内嵌包可能被裁剪或替换过，拒绝加载";
        }
        return null;
    }

    /**
     * 类是否可解析。**返回 null 才是通过**。
     *
     * <p>用 {@code initialize=false}：只验"看得见"，不触发静态初始化——
     * 否则校验本身就会踩到被检类的 {@code <clinit>}，把"缺依赖"变成"校验时崩"。
     */
    public static String classIssue(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return null;
        } catch (Throwable t) {
            return "缺少依赖类 " + className + "（" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + "）";
        }
    }

    /** 从运行中的 core jar 反射读契约号（避开常量内联）。读不到返回 -1。 */
    public static int liveCoreApi() {
        try {
            Class<?> c = Class.forName("snowleopard.core.Version", false,
                    FrameworkDependencies.class.getClassLoader());
            return c.getField("CORE_API").getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 运行中的 core jar 发布版本；读不到返回 null。 */
    public static String liveCoreRelease() {
        try {
            Class<?> c = Class.forName("snowleopard.core.Version", false,
                    FrameworkDependencies.class.getClassLoader());
            Object v = c.getMethod("release").invoke(null);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }
}
