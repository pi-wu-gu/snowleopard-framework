# 雪豹框架（Snow Leopard Framework）

> **说明：本项目几乎完全由 AI 完成**——设计分析、架构决策、全部代码与文档均由 AI 在与用户的  
> 对话中产出，用户负责提出需求、裁决设计分歧与验收。文档与记录一律带时间戳  
> （`YYYY-MM-DD HH:MM (UTC+8)`）。

**雪豹决策核心的 NeoForge 薄壳**（双件套的模组侧）：把 `snowleopard-core` 嵌进 MC，  
让其他模组的工具组经 SPI 自动接入。决策、工具、记忆、权限全在核心（零 MC）；  
本壳只有装配门面、SPI 发现（类加载器交接）与启动报告——**壳里没有业务逻辑**。

- 目标：MC **1.21.1** / NeoForge 21.1.259 / Java 21 · 双端（BOTH）· 无界面
- 核心依赖：`snowleopard.core:snowleopard-core`（jarJar 内嵌，玩家只放一个模组 jar）
- 架构基准：`docs/工具组支持可行性分析-2026-10-22.md` §四（双件套）+ 核心 `SnowLeopard` 门面 S1–S6  
  （**公开仓库仅作局部参考，架构不照搬**——2026-10-23 用户裁决）

## 构建

```bash
# 1. 先产核心到本地 maven（在雪豹决策核心仓执行）
./build.sh publish ../../project/snowleopard-framework/libs/m2

# 2. 构建模组（在本仓执行；两模块：shell + entitytools）
./gradlew build              # 产物 shell/build/libs/snowleopard-neoforge-1.21.1-<ver>.jar（内嵌核心与 entitytools）
./gradlew :entitytools:test  # 契约/状态机 headless 测试
./gradlew runClient          # 开发期跑客户端
```

## 模块

- `shell/` —— NeoForge 薄壳：装配门面、SPI 发现、启动报告、自测钩子（无业务逻辑）
- `entitytools/` —— 实体控制工具组（MC 能力面）：契约冻结件（sense 状态机/三件套/组名）+
  逐里程碑工具实现；经 SPI 装载，jarJar 与核心同嵌套域

## 文档

- `docs/开发流程-2026-10-23.md` —— M0–M7 里程碑（实体控制工具组）
- `docs/工具组接入文档-2026-10-23.md` —— **工具清单/参数口径/红线/审批链（接入者主入口）**
- `docs/实体控制工具组可行性分析-2026-10-23.md` —— [史料] 含裁决版规格基准（设计期记录）
- `docs/开发说明-2026-10-23.md` —— 架构边界、装配路径、版本链、参考边界裁决

