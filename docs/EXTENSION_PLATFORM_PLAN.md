# LxChat 扩展平台架构设计文档

> 状态：设计定稿 · 待执行 Phase 1
> 覆盖：会员体系、第三方插件/技能生态、省 token、UI 元数据化、防反编译、上架合规
> 关键对标：DeepSeek Harness (dsh, 199k⭐) · cc-haha (14.2k⭐) · Operit (7.3k⭐)

---

## 目录

1. [背景与完整上下文](#1-背景与完整上下文)
2. [现有架构深度分析（代码级）](#2-现有架构深度分析代码级)
3. [问题诊断（代码级证据）](#3-问题诊断代码级证据)
4. [决策演进（从 PluginHost 出口到 execute 入口）](#4-决策演进)
5. [目标架构设计（代码级方案）](#5-目标架构设计代码级方案)
6. [第三方生态对齐（关键标准）](#6-第三方生态对齐关键标准)
7. [省 token 方案（量化）](#7-省-token-方案量化)
8. [支付与防反编译](#8-支付与防反编译)
9. [分阶段实现任务](#9-分阶段实现任务)
10. [风险与合规](#10-风险与合规)

---

## 1. 背景与完整上下文

### 1.1 产品现状

LxChat（包名 `com.lxseek.chat`）是一个 Android 聊天/自动化应用：

- **技术栈**：Kotlin + Jetpack Compose + Room + 手动 DI（无 Hilt/Koin）
- **SDK**：minSdk 24 / targetSdk 36，play/fdroid 双 flavor
- **R8**：`isMinifyEnabled = true`，`shrinkResources = true`
- **CI 约束**：只能通过 GitHub Actions 编译验证，本地不编译

### 1.2 本次演进要解决的问题

1. **会员/付费**：易支付 + 兑换码（国内），不侵入现有免费功能
2. **第三方生态**：兼容 DeepSeek Harness / Operit / cc-haha / MCP 的插件与技能
3. **省 token**：工具/技能注入是 base token 大头，是成本生命线
4. **UI 元数据化**：现有设置页硬编码，承载不了动态插件 UI
5. **防反编译**：支付验证逻辑加固
6. **上架合规**：免费核心 + 付费增强，不锁基础功能

### 1.3 Phase 1 已完成的工作

已新增 `plugin` 包（5 个文件）+ 修改 3 个文件，建立了统一的 Plugin 抽象：

| 文件 | 职责 |
|------|------|
| `plugin/Plugin.kt` | `PluginCategory` + `PluginManifest` + `Plugin` 接口 |
| `plugin/PluginContext.kt` | 插件运行时最小依赖（appContext/scope/settings） |
| `plugin/PluginHost.kt` | 注册表 + 启用态 + `toolProviders()` 聚合 |
| `plugin/McpPlugin.kt` | 包装 `McpToolProvider` |
| `plugin/NativeToolsPlugin.kt` | 包装原生工具集 |

同时 `ChatViewModelFactory` 删除 8 个零散 provider 参数，改为单个 `pluginHost`；`ChatViewModel` 改用 `pluginHost.toolProviders()`。

---

## 2. 现有架构深度分析（代码级）

### 2.1 分层结构

```
┌─────────────────────────────────────────────────────────┐
│ UI 层    ChatApp · SettingsScreen · 50+ SettingsXxxPage  │
├─────────────────────────────────────────────────────────┤
│ 视图层   ChatViewModel ── GenerationManager               │
│                        └─ GenerationToolExecutor          │
├─────────────────────────────────────────────────────────┤
│ 工具层   ToolProvider · ToolDescriptor · ToolTierPolicy   │
│          ├─ 原生: Shell/Memory/WebSearch/Rag/ImageGen     │
│          └─ 插件: automation/androidControl/git/im/       │
│                  reminder/subAgent/device + MCP           │
├─────────────────────────────────────────────────────────┤
│ 插件层   Plugin · PluginHost（Phase 1 新增）              │
├─────────────────────────────────────────────────────────┤
│ DI 层    AppContainer（手动 lazy 单例）                    │
└─────────────────────────────────────────────────────────┘
```

### 2.2 工具系统（核心契约）

`tool/ToolProvider.kt` 定义了工具契约，其中 **`ToolDescriptor` 是工具元数据的单一事实来源**：

```kotlin
data class ToolDescriptor(
    val definition: ToolDefinition,        // 模型可见的完整定义（name+description+parameters）
    val riskLevel: RiskLevel = RiskLevel.ReadOnly,
    val tier: ToolTier = ToolTier.Dangerous,   // Core/Extended/Dangerous
    val requiresApproval: Boolean = false,
)
```

`ToolProvider` 接口关键方法：

```kotlin
interface ToolProvider {
    fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor>  // 元数据（默认桥接旧方法）
    fun definitions(ctx: GenerationContext): List<ToolDefinition>       // 注入 prompt 的定义
    suspend fun execute(name: String, arguments: String, ctx): String   // 一次式执行
    fun executeEvents(...): Flow<ToolExecutionEvent>                    // 流式执行
    fun handles(name: String): Boolean                                  // 路由
    fun riskLevel(name: String): RiskLevel
    fun requiresApprovalByDefault(name: String): Boolean
}
```

### 2.3 分层注入（省 token 已做一半）

`tool/ToolTierPolicy.kt` 已有工具分层与注入过滤：

```kotlin
enum class ToolTier { Core, Extended, Dangerous }

object ToolTierPolicy {
    fun allowedTiers(ctx: GenerationContext): Set<ToolTier> =
        when (ctx.toolTier) {
            "core"     -> setOf(Core)
            "extended" -> setOf(Core, Extended)
            "all"      -> setOf(Core, Extended, Dangerous)
            else -> when (ctx.agentMode) { /* Plan→Core+Extended，Agent/Auto→全量 */ }
        }
    // tierOf(name) 的 fallback 是 Dangerous（未在 LEGACY_TIERS 表中登记的默认最安全）
}
```

**关键事实**：`GenerationContext.toolTier` 默认值是 `"all"`（见 `viewmodel/GenerationContracts.kt:77`），意味着**默认全量注入所有工具**，包括 Dangerous。这是省 token 的第一优化点。

### 2.4 生成管线：唯一工具执行入口

`viewmodel/GenerationToolExecutor.kt` 是**所有工具执行的汇聚点**（前台 + 后台 + Cron + Loop + IM 都经过它）：

```kotlin
// 注入：definitions 是 prompt 注入的入口
override fun definitions(context) = descriptors(context).map { it.definition }

// 注入过滤（现有）
private fun descriptors(context): List<ToolDescriptor> {
    val descs = providers.flatMap { it.toolDescriptors(context) }
    return descs.filterByAgentMode(context).filterByTier(context)
}

// 执行（审批链路已存在）
suspend fun execute(call: AuthorizedToolCall, onEvent): AuthorizedToolResult {
    val provider = providers.firstOrNull { it.handles(call.name) } ?: return ...
    val desc = ToolTierPolicy.descriptorMap(providers, call.context)[call.name]
    val riskLevel = desc?.riskLevel ?: RiskLevel.ReadOnly
    val requiresApproval = desc?.requiresApproval ?: false
    if (needsOuterApproval(call.name, riskLevel, requiresApproval, call.context.agentMode)) {
        when (val approval = onToolApproval(approvalRequest)) {
            is Denied -> return call.result("denied by user")   // 审批拒绝点
        }
    }
    provider.executeEvents(...)                                  // ← 真正的执行
}
```

`createDefault` 的 provider 拼接顺序（`baseProviders + planProviders + additionalProviders + traceProvider`）：
- `baseProviders` = Memory / WebSearch / Rag / ImageGen / Shell（固定 5 个）
- `additionalProviders` = 调用方传入（前台 = `pluginHost.toolProviders()`，后台 = 6 个零散 provider）

### 2.5 插件系统（Phase 1）

`plugin/Plugin.kt` 的 Plugin 接口（当前只有工具维度）：

```kotlin
interface Plugin {
    val manifest: PluginManifest
    fun toolProviders(context: PluginContext): List<ToolProvider> = emptyList()
    fun onEnable(context: PluginContext) {}
    fun onDisable() {}
}

data class PluginManifest(
    val id: String, val name: String, val version: String,
    val category: PluginCategory,          // Integrated/Mcp/External
    val description: String? = null, val author: String? = null,
    val requiresMembership: Boolean = false,  // ★ 会员标记已声明，但未被消费
    val builtIn: Boolean = true,
    val preferenceKey: String? = null,
)
```

`plugin/PluginHost.kt` 的核心：

```kotlin
class PluginHost(private val context: PluginContext) {
    private val registered = mutableMapOf<String, Plugin>()
    private val enabledProviders = mutableMapOf<String, List<ToolProvider>>()
    val plugins: StateFlow<List<PluginInfo>> = ...   // 设置页快照

    fun register(plugin: Plugin, initiallyEnabled: Boolean = true) { ... }
    fun setEnabled(id: String, on: Boolean) { ... }
    fun toolProviders(): List<ToolProvider> = enabledProviders.values.flatten()  // ★ 无门禁
}
```

### 2.6 DI 层与两处消费点

`di/AppContainer.kt` 中，工具被**两处消费**（这是问题根源）：

```kotlin
// 消费点 A（前台）：注册进 PluginHost
val pluginHost by lazy {
    PluginHost(pluginContext).also { host ->
        host.register(McpPlugin(mcpToolProvider))
        host.register(NativeToolsPlugin(listOf(
            automationToolProvider, androidControlToolProvider, gitToolProvider,
            imToolProvider, reminderToolProvider, subAgentToolProvider, deviceToolProvider,
        )))
    }
}

// 消费点 B（后台）：TaskExecutionEngine 直接持有零散 provider
val taskExecutionEngine by lazy {
    TaskExecutionEngine(
        ...,
        mcpToolProvider = mcpToolProvider,
        androidControlToolProvider = androidControlToolProvider,
        gitToolProvider = gitToolProvider,
        imToolProvider = imToolProvider,
        reminderToolProvider = reminderToolProvider,
        deviceToolProvider = deviceToolProvider,
    )
}
```

### 2.7 UI 层：设置页硬编码

`ui/settings/SettingsScreen.kt` 三处硬编码：

```kotlin
// ① 入口列表写死
private val settingsGroups = listOf(
    SettingsGroupData(... items = listOf(
        SettingsCategory("provider", ...),
        SettingsCategory("mcp", ...),
        SettingsCategory("adb_shell", ...),     // 每加一个功能手动加一行
    )),
)

// ② 路由 when 写死
when (category) {
    "provider" -> SettingsProviderPage(...)
    "mcp" -> SettingsMcpPage(...)
    "adb_shell" -> SettingsAdbPage(...)          // 每加一个页面手动加一个分支
}
// ③ 每个设置页一个独立文件（50+ SettingsXxxPage.kt）
```

而 `SettingsMcpPage.kt` 已有一个**数据驱动雏形**（`servers.forEach { add { ... } }`），证明方向可行。

---

## 3. 问题诊断（代码级证据）

| # | 问题 | 证据 | 影响 |
|---|------|------|------|
| 1 | 后台绕过 PluginHost | `AppContainer` 给 `TaskExecutionEngine` 直接传 6 个 provider | 会员门禁挡不住后台 |
| 2 | 门禁注释放错位置 | `Plugin.kt:37-38` / `PluginHost.kt:13` / `AppContainer.kt:224-225` 都说"在 PluginHost 出口拦截" | 覆盖不了后台 |
| 3 | 后台工具范围特殊 | `TaskExecutionEngine` 的 `additionalToolProviders` **不含** `automationToolProvider` / `subAgentToolProvider`（防递归） | 不能强制统一走 PluginHost |
| 4 | Plugin 无 UI/Skill 维度 | `Plugin` 只有 `toolProviders` | 承载不了 Skill / 插件界面 |
| 5 | 默认全量注入 | `GenerationContext.toolTier = "all"` | 浪费 token |
| 6 | `tierOf` fallback 是 Dangerous | 新增 provider 未登记则默认为 Dangerous | 收紧 tier 会误伤未迁移的工具 |
| 7 | 会员零实现 | 只有 `requiresMembership` 字段，无任何消费逻辑 | — |

---

## 4. 决策演进

这里记录为什么最终落点从「PluginHost 出口」演变为「execute 入口 + 三层门禁」。

### 阶段 1：门禁放 PluginHost 出口（Phase 1 注释的原始设想）

```kotlin
// PluginHost.toolProviders() 出口按 requiresMembership 过滤
fun toolProviders(): List<ToolProvider> = enabledProviders.filterKeys { ... }
```

**被推翻的原因**：`TaskExecutionEngine` 不经过 `pluginHost.toolProviders()`，门禁只能挡前台、漏后台。

### 阶段 2：让 TaskExecutionEngine 也走 PluginHost

**被推翻的原因**：后台的 `additionalToolProviders` **故意不含** `automationToolProvider` / `subAgentToolProvider`（后台不能递归创建自动化/子代理）。若强行统一走 `pluginHost.toolProviders()`，会把这两个危险工具引入后台，反而制造新 bug。**前台与后台的工具范围本来就该不同，不该强行统一。**

### 阶段 3：门禁放 execute 执行入口（最终方向）

`GenerationToolExecutor.execute()` 是所有入口的汇聚点（前台/后台/Cron/Loop/IM 都经过它），且已有 `needsOuterApproval` + `onToolApproval` 审批链路。会员检查放在这里 = **执行层强制拦截**，天然全覆盖。

### 阶段 4：结合省 token，拆成「披露 + 执行」两层

- **披露层**（`descriptors()` 注入过滤）：非会员工具不进 prompt → 省钱 + 好 UX（模型不徒劳调用）
- **执行层**（`execute()` 硬检查）：防破解兜底（列表被绕过也拦得住）

### 最终结论：三层门禁

| 层 | 位置 | 作用 |
|----|------|------|
| 披露层 | `descriptors()` 加 `filterByMembership` | 省 token + UX |
| 执行层 | `execute()` 加 `membershipCheck` | 防破解 |
| 插件层 | `PluginHost` 启停 + builtIn 分发 | 用户控制 + 懒加载 |

---

## 5. 目标架构设计（代码级方案）

### 5.1 ToolDescriptor 扩展（向后兼容）

```kotlin
data class ToolDescriptor(
    val definition: ToolDefinition,
    val riskLevel: RiskLevel = RiskLevel.ReadOnly,
    val tier: ToolTier = ToolTier.Dangerous,
    val requiresApproval: Boolean = false,
    val requiresMembership: Boolean = false,   // ★ 新增：会员门禁标记
    val summary: String? = null,               // ★ 新增：渐进式披露的目录项（一句话）
)
```

所有字段带默认值，现有 provider 零改动即可编译。

### 5.2 披露层：注入过滤

`GenerationToolExecutor.descriptors()` 加一行过滤：

```kotlin
private fun descriptors(context: GenerationContext): List<ToolDescriptor> {
    val descs = providers.flatMap { it.toolDescriptors(context) }
    return descs
        .filterByAgentMode(context)
        .filterByTier(context)
        .filterByMembership(context)      // ★ 新增
}

private fun List<ToolDescriptor>.filterByMembership(context): List<ToolDescriptor> =
    filter { !it.requiresMembership || context.hasMembership }
```

### 5.3 执行层：硬检查兜底

`GenerationToolExecutor.execute()` 在审批判定后、真正执行前插入：

```kotlin
// 位于 needsOuterApproval 分支之后、provider.executeEvents 之前
val desc = ToolTierPolicy.descriptorMap(providers, call.context)[call.name]
if (desc?.requiresMembership == true && !membershipCheck(call.name)) {
    return call.result(
        ToolExecutionResult(text = "Tool '${call.name}' requires membership", isError = true))
}
```

其中 `membershipCheck: (String) -> Boolean` 作为 `GenerationToolExecutor` 的构造参数注入（默认 `{ true }` 不拦截），经 `GenerationManager` 透传，最终来源是 `MembershipProvider`（Phase 3 实现）。

### 5.4 插件级标记 → 工具级下沉

`PluginManifest.requiresMembership` 保留，但传播方式从「PluginHost 出口过滤」改为「装饰器下沉到 ToolDescriptor」：

```kotlin
class MembershipToolProvider(private val delegate: ToolProvider) : ToolProvider by delegate {
    override fun toolDescriptors(ctx) =
        delegate.toolDescriptors(ctx).map { it.copy(requiresMembership = true) }
}
```

- 插件级（现在）：`requiresMembership=true` → 装饰器统一标记整个插件的工具
- 工具级（未来）：provider 直接在 `toolDescriptors` 里对单个工具标 `requiresMembership=true`

### 5.5 完整数据流（目标态）

```
创建  AppContainer lazy 单例创建 ToolProvider
      ↓
注册  pluginHost.register(NativeToolsPlugin(...)) / register(McpPlugin(...))
      ↓
前台  ChatViewModel → pluginHost.toolProviders() ┐
后台  TaskExecutionEngine → 6 个 provider         ├→ GenerationManager
      ↓                                           │    └─ GenerationToolExecutor
注入  definitions(ctx) → descriptors(ctx)        │
        → filterByAgentMode → filterByTier        │
        → filterByMembership ★（披露层省token）    │
      → map{definition} → prompt                  │
执行  execute(call)                               │
        → descriptorMap 查 desc                   │
        → needsOuterApproval（已有审批）           │
        → membershipCheck ★（执行层防破解）         │
        → provider.executeEvents（真正执行）        │
```

> 关键不变式：**前后台工具范围不同是合理的**，门禁不靠「统一走 PluginHost」，而靠「execute / descriptors 两个汇聚点都挂门禁」。

---

## 6. 第三方生态对齐（关键标准）

### 6.1 SKILL.md 是事实标准（直接对齐，不自己造格式）

cc-haha 的 `skills.md` + Operit 的 `SCRIPT_DEV_SKILL.md` + Claude Skills 都采用 `SKILL.md`（Markdown + YAML frontmatter）：

```markdown
---
name: 技能名
description: 一句话描述        # 渐进式披露的目录项，省 token 关键
when_to_use: 何时用
context: inline | fork        # fork = 子 Agent 隔离，独立 token 预算
allowed-tools: "Bash, Read"   # Skill 自限工具范围（门禁补充形态）
paths: "src/**/*.ts"          # 条件激活，匹配文件才暴露（省 token）
model: sonnet                 # 便宜模型跑简单技能（省成本）
---
# 正文：Markdown 提示词，只在需要时才加载全文
```

四个关键字段：

| 字段 | 用途 |
|------|------|
| `description` | 渐进式披露的目录项，模型只见这句 |
| `paths` | 条件激活 = 动态工具选择的标准实现（不自己造 cluster） |
| `allowed-tools` | 门禁的第三种形态（Skill 自限），与会员门禁正交 |
| `context: fork` | 长任务隔离子 Agent，省主对话 token |

### 6.2 Plugin 是多能力容器

cc-haha 的 `builtinPlugins.ts` 显示一个 Plugin 可提供 `skills + hooks + mcpServers`；LxChat 当前 `Plugin` 只有 `toolProviders`，需预留演进。命名格式借鉴 `{name}@builtin` vs `{name}@{marketplace}`。

### 6.3 轻量默认、重量按需（Operit 哲学）

| 轻量（默认） | 重量（按需） |
|------|------|
| Skill / 纯 markdown（无 UI、无代码执行） | ToolPkg（manifest + UI + Hook + WASM） |

Operit 明确：默认写普通脚本，只有真正需要配置界面/生命周期/Hook 才升级 ToolPkg。

### 6.4 UI 三级路线

```
轻量 Skill（无 UI）→ 默认
    ↓ 需要配置
Schema 表单（字段声明，settingsSchema()）→ 覆盖 90%
    ↓ 需要复杂 UI
Compose DSL（对标 Operit compose_dsl runtime）→ 最后才上
```

---

## 7. 省 token 方案（量化）

### 7.1 现状成本

`ToolDefinition` = `type + function(name + description + parameters(properties[{type,description}] + required))`。
每工具 200-500 token；现有约 40-60 工具，全量注入 = **6000-18000 token base**，每轮 agentic loop 重复。

### 7.2 三级注入

| 级别 | 改动 | 风险 | 省 token |
|------|------|------|---------|
| 1. tier 收紧 `all→extended` | 改 `GenerationContext.toolTier` 默认值 1 行 | 🟢 零 | 30-50% |
| 2. description 精简 | 改 `definitions()` 文案 | 🟢 零 | 每工具 100 token |
| 3. 渐进式披露（summary 目录 + paths 条件激活 + 按需加载） | 加字段 + 过滤器 | 🟡 小 | 60-80% |

三级叠加：base token 从 6000-18000 降到 1000-4000，省 **70-80%**。

### 7.3 前提：能量度

cc-haha 的 `cost-tracker.ts` 追踪 `inputTokens/outputTokens/cacheReadInputTokens/cacheCreationInputTokens/costUSD`。**省 token 第一步是补齐用量度量**，否则无法验证收益。

### 7.4 缓存友好

工具注入顺序约定 `core → extended → 会员/动态`，保证 prompt prefix caching 命中率（`ToolDescriptor` 的 `definition.function.name` 保序，`LinkedHashMap` 已保序）。

---

## 8. 支付与防反编译

### 8.1 支付方案（易支付 + 兑换码，去谷歌支付）

| 方式 | 验证 | 防反编译重点 |
|------|------|-------------|
| 易支付 | 回调验证 | 回调 URL + 签名 |
| 兑换码 | **本地离线校验** | 校验逻辑 native 化，本机 native 层最难破解 |

兑换码的离线校验是防反编译主战场——把 `isValidRedemptionCode()` 逻辑放进 native（复用已有 `NativeLogBridge` 的 JNI 基础设施）。

### 8.2 混淆要点

- 已开 R8 + shrinkResources
- 保留：`Plugin` 接口、`ToolProvider` 接口（反射/序列化）、Room Entity、Compose 函数、Manifest 引用的类
- 混淆：`PluginHost` 内部实现、门禁逻辑、支付校验逻辑
- 敏感逻辑 native 化：兑换码校验、易支付回调签名

---

## 9. 分阶段实现任务

### Phase 1「地基」— 门禁 + 省 token + 去硬编码

| # | 任务 | 目标文件 | 维度 |
|---|------|---------|------|
| 1 | `ToolDescriptor` 加 `requiresMembership` + `summary` | `tool/ToolProvider.kt` | 门禁/省token |
| 2 | `descriptors()` 加 `filterByMembership` | `viewmodel/GenerationToolExecutor.kt` | 门禁披露层 |
| 3 | `execute()` 加 `membershipCheck` + 注入参数 | `GenerationToolExecutor.kt` + `GenerationManager.kt` | 门禁执行层 |
| 4 | `toolTier` 默认 `"all"→"extended"` + 精简高频 description | `GenerationContracts.kt` + 各 provider | 省token 1/2级 |
| 5 | token 用量度量（cost-tracker 等价物） | 新增 | 省token 前提 |
| 6 | `Plugin.settingsSchema()` + 通用 SchemaForm | `plugin/` + `ui/settings/` | UI 去硬编码 |

**验收**：门禁覆盖前台/后台/Cron/Loop/IM；base token 降 30-50%；新插件零手写设置页；token 可度量。

### Phase 2「生态」— Skill + 渐进式披露

| # | 任务 | 维度 |
|---|------|------|
| 7 | `Skill` 抽象（对齐 SKILL.md） | 生态 |
| 8 | 渐进式披露（summary 目录 + 按需加载正文） | 省token |
| 9 | `paths` 条件激活 | 省token |
| 10 | `Plugin` 演进为多能力容器（skills + tools） | 生态 |
| 11 | 内置 3-5 个技能 | 生态 |

### Phase 3「变现」— 易支付 + 兑换码 + 加固

| # | 任务 | 维度 |
|---|------|------|
| 12 | `MembershipProvider`（本地离线 + 云端增强） | 门禁源 |
| 13 | 易支付回调 + 兑换码离线校验 | 支付 |
| 14 | 兑换码校验 native 化（复用 NativeLogBridge JNI） | 防反编译 |
| 15 | R8 规则 + 字符串加密 | 防反编译 |
| 16 | 审核合规（免费核心 + 付费增强） | 合规 |

### Phase 4「扩展」— 多生态适配 + 市场 + 富 UI

| # | 任务 | 维度 |
|---|------|------|
| 17 | 技能市场 UI | 生态 |
| 18 | dsh-plugin / Operit ToolPkg / SKILL.md 适配器 | 生态 |
| 19 | 动态加载 + 签名校验 | 生态 |
| 20 | Compose DSL UI runtime | UI |
| 21 | ABI split 只留 arm64-v8a + 按需下载 | APK |

---

## 10. 风险与合规

| 关注点 | 对策 |
|--------|------|
| 后台工具范围特殊 | 门禁放 descriptors/execute 两点，不靠统一 PluginHost |
| `tierOf` fallback Dangerous | 收紧 tier 前先确认新增 provider 已声明 tier，避免误伤 |
| 核心功能锁付费墙 | 会员只锁「增强」，不锁基础聊天/自动化 |
| 隐藏功能 | 付费入口 UI 可见，不用隐蔽 scheme |
| 国内支付 | 易支付 + 兑换码（离线兑换） |
| 第三方插件数据访问 | 隐私政策声明，用户主动连接才生效 |
| LGPL（Operit）污染 | 只借鉴思路，不复制代码 |
| APK 大小 | R8 + ABI split + 插件按需下载 |

---

## 附：设计原则（贯穿全文）

1. **单一出口**：所有工具执行收敛到 `GenerationToolExecutor.execute`
2. **披露与执行分离**：披露层管「省钱+UX」，执行层管「防破解」
3. **元数据驱动 > 硬编码**：后端 `ToolDescriptor`，前端 `SchemaForm`
4. **渐进式 > 全量**：token 是生命线，按需注入是常态
5. **借用不复制**：LGPL 只借思想，不引代码
6. **轻量默认、重量按需**：Skill 默认纯 markdown，ToolPkg 按需