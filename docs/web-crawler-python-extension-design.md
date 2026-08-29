# 网页爬虫 Python 扩展接入设计

> 任务 #78 · 设计 LxChat 接入 crawl4ai-mcp-server 的方案：LxChat 安装后支持装入 Python，crawl4ai-mcp-server 作为 MCP 子服务接入。
>
> 本文档只做设计，不含 Kotlin 实现代码。

---

## 1. 概述

LxChat 已具备完整的 MCP 客户端、Python 运行时引擎、外部进程管理与插件市场体系。本设计在此基础上接入 [crawl4ai-mcp-server](https://github.com/uysalsadi/crawl4ai-mcp-server)，为用户提供「一键安装 → 自动启动 → 暴露 4 个网页爬取工具」的端到端能力。

**目标能力**：用户在 LxChat 中对模型说「帮我抓取 https://example.com 的内容」，模型即可调用 `scrape` 工具，由本地 Python 进程通过 Playwright 渲染页面并返回 Markdown。

**设计原则**：
- ✅ 复用 LxChat 现有 MCP 协议层（`McpProtocolClient`），仅新增 stdio 传输
- ✅ 复用 LxChat 现有 Python 运行时引擎（`runtime-python`）与进程管理（`RuntimeProcessManager`）
- ✅ 复用 LxChat 现有插件市场与 SKILL 依赖引擎机制（参照 webnovel-writer 先例）
- ❌ 不引入 Docker 依赖（Android 无 Docker）
- ❌ 不在 Python 端加 HTTP 桥接层（多一层进程与端口，徒增复杂度）

---

## 2. 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                       LxChat App (Android)                   │
│                                                              │
│  ┌──────────────┐   ┌──────────────────────────────────────┐ │
│  │  Chat Model  │──▶│        McpToolProvider               │ │
│  │  (LLM)       │   │  (toolDefinitions + execute)         │ │
│  └──────────────┘   └──────────────┬───────────────────────┘ │
│                                     │ tools/call              │
│  ┌──────────────┐   ┌──────────────▼───────────────────────┐ │
│  │ SettingsMcp  │──▶│           McpRegistry                │ │
│  │ Page (UI)    │   │  (per-server runtime + retry)        │ │
│  └──────────────┘   └──────────────┬───────────────────────┘ │
│                                     │ JSON-RPC                │
│  ┌──────────────┐   ┌──────────────▼───────────────────────┐ │
│  │ PluginMarket │──▶│      McpProtocolClient               │ │
│  │ (安装 SKILL) │   │  (initialize/listTools/callTool)     │ │
│  └──────────────┘   └──────────────┬───────────────────────┘ │
│                                     │                         │
│  ┌──────────────┐   ┌──────────────▼───────────────────────┐ │
│  │ RuntimeEngine│──▶│   StdioMcpTransport  ← 新增           │ │
│  │ Manager      │   │   (process stdin/stdout)             │ │
│  └──────────────┘   └──────────────┬───────────────────────┘ │
│         │                           │ stdin/stdout            │
│         │ ensureDependencyRoot      │                         │
│  ┌──────▼───────┐                   │                         │
│  │ runtime-python│                  │                         │
│  │ (Python 3.11) │                  │                         │
│  └──────────────┘                   │                         │
└─────────────────────────────────────┼─────────────────────────┘
                                       │
═══════════════════════════════════════╪═════════════════════════
                          本地进程边界  │
═══════════════════════════════════════╪═════════════════════════
                                       ▼
                        ┌──────────────────────────┐
                        │  python -m crawler_agent  │
                        │       .mcp_server         │
                        │  (stdio MCP server)       │
                        └──────────────┬───────────┘
                                       │
                        ┌──────────────▼───────────┐
                        │   crawl4ai + Playwright   │
                        │   (AsyncWebCrawler)       │
                        └──────────────┬───────────┘
                                       │
                        ┌──────────────▼───────────┐
                        │   Chromium (headless)     │
                        └──────────────┬───────────┘
                                       │
═══════════════════════════════════════╪═════════════════════════
                          网络边界      │
═══════════════════════════════════════╪═════════════════════════
                                       ▼
                              ┌────────────────┐
                              │   Web (HTTP)   │
                              └────────────────┘
```

**数据流**：模型调用工具 → `McpToolProvider.execute` → `McpRegistry.execute` → `McpProtocolClient.callTool` → `StdioMcpTransport.send`（写入 Python 进程 stdin）→ crawl4ai 抓取网页 → 返回 Markdown（从 Python 进程 stdout 读取）→ `McpOutputGuard` 截断防 token 爆炸 → 返回模型。

---

## 3. 现状分析

### 3.1 LxChat 现有 MCP 支持

| 组件 | 路径 | 职责 |
|------|------|------|
| `McpRegistry` | `mcp/McpRegistry.kt` | 进程级 MCP 监管器：多 server 连接、重试（5s→5min 指数退避）、工具注册、elicitation 桥接 |
| `McpProtocolClient` | `mcp/McpProtocolClient.kt` | JSON-RPC 协议客户端：`initialize` / `tools/list` / `tools/call` / `resources/list` / `resources/read`，支持协议版本 2025-11-25 / 2025-06-18 / 2025-03-26 / 2024-11-05 |
| `McpClientTransport` | `mcp/McpClientTransport.kt` | 传输层接口，现有实现：`StreamableHttpMcpTransport`、`LegacySseMcpTransport` |
| `McpServerConfig` | `data/SettingsContracts.kt` | 配置模型：`id` / `name` / `enabled` / `url` / `transport` / `headers` / `env` / `disabledTools` / `oauth` |
| `McpTransportType` | `data/SettingsContracts.kt` | 枚举：`STREAMABLE_HTTP` / `SSE`（**缺 stdio**） |
| `McpToolProvider` | `tool/McpToolProvider.kt` | 将 MCP server 工具暴露给模型，含 `list_mcp_resources` / `read_mcp_resource` 两个宿主级资源工具 |
| `McpPlugin` | `plugin/McpPlugin.kt` | 将 MCP 能力包装为插件（`builtin.mcp`） |
| `SettingsMcpPage` | `ui/settings/SettingsMcpPage.kt` | MCP 设置 UI，含传输类型下拉选择 |
| `McpOutputGuard` | `mcp/McpOutputGuard.kt` | 输出截断守卫，防 runaway token |
| `McpEnvExpansion` | `mcp/McpEnvExpansion.kt` | `${VAR}` / `${VAR:-default}` 环境变量展开 |

**关键发现**：LxChat MCP 客户端协议层完整且成熟，但**传输层仅支持 HTTP/SSE，不支持 stdio**。

### 3.2 LxChat 现有 Python 运行时支持

| 组件 | 路径 | 职责 |
|------|------|------|
| `RuntimeEngineType.PYTHON` | `runtime/RuntimeModels.kt` | Python 引擎类型（id = `python`） |
| `runtime-python` 市场条目 | `plugin/market/BuiltinMarketSources.kt` | Python 3.11 运行时，下载 URL 已配置：`python-3.11.0-android-arm64.zip` |
| `RuntimeProcessManager` | `runtime/RuntimeProcessManager.kt` | 进程管理：`start` / `stop` / `touch` / 空闲 10 分钟自动回收 / 崩溃清理 / `stopHandler` 回调 |
| `RuntimeEngineManager` | `runtime/RuntimeEngineManager.kt` | 引擎编排器：下载 / 安装 / 启动 / 版本校验 / `ensureDependencyRoot` 依赖引擎自动安装 |
| `RuntimeManifest` | `runtime/RuntimeModels.kt` | 引擎 manifest：`startCommand` / `requiresEngine` / `versions` / `minVersion` / `runtimeRequirements` |
| `PYTHON_WEB_NOVEL` SKILL | `runtime/RuntimeToolProvider.kt` | webnovel-writer 先例：依赖 `runtime-python` >= 3.10，通过 `ensureDependencyRoot("runtime-python")` 自动确保 Python 已装 |

**关键发现**：LxChat 已有 Python 3.11 运行时引擎、完善的进程管理、SKILL 依赖 Python 的先例（webnovel-writer），接入 crawl4ai 可完全复用这套机制。

### 3.3 crawl4ai-mcp-server 分析

| 维度 | 详情 |
|------|------|
| **传输协议** | **stdio MCP server**（`python -m crawler_agent.mcp_server`，使用 `mcp.server.stdio`） |
| **工具数量** | 4 个：`scrape` / `crawl` / `crawl_site` / `crawl_sitemap` |
| **Python 版本** | 3.11+ |
| **依赖** | `mcp>=1.1.0,<2.0.0` · `crawl4ai>=0.7.0,<0.8.0` · `pydantic>=2.7,<3.0` · `playwright>=1.44,<2.0` · `openai-agents>=0.1.0` · `httpx>=0.27` |
| **浏览器** | 需 Playwright Chromium（`python -m playwright install chromium`） |
| **安全** | `safety.py` 阻止 localhost / 127.0.0.1 / ::1 / RFC1918 私有 IP / `file://` / `.local` / `.internal` / `.lan` |
| **底层** | Crawl4AI `AsyncWebCrawler` + Playwright 无头 Chromium |
| **持久化** | `output_dir` 参数：不传则返回完整内容；传则落盘并仅返回 manifest 元数据（防 context 爆炸） |
| **入口** | `crawler_agent/mcp_server.py`（518 行），`Server("crawl4ai-mcp")` |

**4 个工具签名**：

```
scrape(url, crawler?, browser?, script?, timeout_sec=45, output_dir?)
  → { url, markdown, links, metadata } | { run_id, file_path, manifest_path, bytes_written }

crawl(seed_url, max_depth=1, max_pages=5, same_domain_only=true,
      include_patterns?, exclude_patterns?, adaptive=false, output_dir?, ...)
  → { start_url, pages[], total_pages } | { run_id, pages_ok, pages_failed, manifest_path }

crawl_site(entry_url, output_dir, max_depth=2, max_pages=200, ...)  // 始终持久化
  → { run_id, output_dir, manifest_path, pages_ok, pages_failed, bytes_written }

crawl_sitemap(sitemap_url, output_dir, max_entries=1000, ...)       // 始终持久化
  → { run_id, output_dir, manifest_path, pages_ok, pages_failed, bytes_written }
```

---

## 4. 核心矛盾与解决方案

### 4.1 矛盾

| LxChat MCP 客户端 | crawl4ai-mcp-server |
|---|---|
| 仅支持 HTTP / SSE 传输 | **stdio** MCP server |

### 4.2 方案比选

| 方案 | 描述 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| **A. 新增 stdio 传输** | LxChat 启动 Python 进程，通过 stdin/stdout 收发 JSON-RPC | 对接 MCP 标准；无额外端口；生命周期易管；与 Claude Code/Cursor 接入方式一致 | 需新增 `StdioMcpTransport` 实现 | ✅ **采用** |
| B. Python 端加 HTTP 桥接 | 用 mcp 的 streamable_http 包装 crawl4ai，LxChat 走 HTTP 连本地端口 | LxChat 不改传输层 | 多一层桥接进程与端口；偏离上游；维护成本高 | ❌ 否决 |
| C. 重写 crawl4ai 为 LxChat 原生工具 | 用 Kotlin 重写爬虫 | 无 Python 依赖 | 放弃 Playwright 渲染能力；工作量巨大；失去上游更新 | ❌ 否决 |

### 4.3 采用方案 A 的可行性依据

1. **协议层已就绪**：`McpProtocolClient` 与传输层解耦（通过 `McpClientTransport` 接口），新增 stdio 实现不影响协议逻辑
2. **进程管理已就绪**：`RuntimeProcessManager` 已支持 `start(command, env, workingDir)` / 空闲回收 / 崩溃清理
3. **Python 运行时已就绪**：`runtime-python`（Python 3.11）市场条目已存在，下载 URL 已配置
4. **依赖引擎自动安装已就绪**：`ensureDependencyRoot("runtime-python")` 已在 webnovel-writer 中验证可行
5. **stdio 是 MCP 标准传输**：与 Claude Code / Cursor / OpenAI Agents SDK 接入方式完全一致，无协议风险

---

## 5. 用户安装流程

### 5.1 流程总览

```
用户在设置中操作              LxChat 内部动作
─────────────────            ─────────────────────────────────
1. 安装 Python 运行时    ──▶  从市场下载 runtime-python (3.11)
   (已有功能, 一键)          解压到 noBackupFiles/runtime-python/

2. 一键安装爬虫扩展      ──▶  从市场下载 crawl4ai-mcp-server 包
   (新增 SKILL 条目)         解压到 noBackupFiles/crawl4ai-mcp/
                            自动 ensureDependencyRoot("runtime-python")
                            pip install -r requirements.txt (到该 Python)
                            python -m playwright install chromium

3. 启用 MCP server       ──▶  McpRegistry 创建 StdioMcpTransport
   (自动/手动开关)           RuntimeProcessManager.start() 启动 Python 进程:
                              python -m crawler_agent.mcp_server
                            McpProtocolClient.initialize() 握手
                            tools/list 发现 4 个工具

4. 模型调用工具          ──▶  McpToolProvider.execute()
   (用户对话中)              McpRegistry.execute()
                            McpProtocolClient.callTool("scrape", {url})
                            StdioMcpTransport 写 stdin / 读 stdout
                            crawl4ai 抓取 → 返回 Markdown
                            McpOutputGuard 截断 → 返回模型
```

### 5.2 详细步骤

#### 步骤 1：安装 Python 运行时（已有功能）

用户路径：`设置 → 运行时引擎 → Python → 安装`

LxChat 已有 `runtime-python` 市场条目（Python 3.11，arm64-v8a），用户点击安装即可。此步骤复用现有 `RuntimeEngineManager.installRuntime()` 链路，无需改动。

> **注**：用户要求 Python 最低版本 3.11。LxChat 现有 `runtime-python` 即为 3.11.0，满足要求。crawl4ai-mcp-server 的 `requirements.txt` 隐式要求 3.11+（`mcp>=1.1.0` 需 3.10+，`crawl4ai>=0.7.0` 需 3.9+，但项目 CLAUDE.md 明确 3.11+）。

#### 步骤 2：一键安装 crawl4ai-mcp-server（新增 SKILL）

用户路径：`设置 → 插件市场 → 爬虫扩展(crawl4ai) → 安装`

**新增市场 SKILL 条目**（在 `BuiltinMarketSources.fetchBuiltinSkillCatalog()` 中）：

```
id          = "runtime-python-crawl4ai"
name        = "crawl4ai 网页爬虫"
kind        = SKILL
runtimeType = "python-crawl4ai"
description = "基于 crawl4ai 的网页爬虫 MCP 服务（需 Python >= 3.11 + Chromium）"
downloadUrl = "https://github.com/ojbkxc/lxchat-runtime/releases/download/crawl4ai-v1.0.0/crawl4ai-mcp-1.0.0-android-arm64.zip"
runtimeRequirements = [ RuntimeRequirement(runtime = "python", minVersion = "3.11") ]
```

**安装时自动执行**（参照 webnovel-writer 先例，在 `RuntimeToolProvider` 的 crawl4ai 工具首次调用时懒执行，或安装后立即执行）：

1. `ensureDependencyRoot("runtime-python")` —— 自动确保 Python 3.11 已装（未装则从市场下载）
2. 解压 crawl4ai-mcp-server 包到 `noBackupFiles/crawl4ai-mcp/`
3. 用已装 Python 执行 `pip install -r requirements.txt`（安装 crawl4ai / mcp / pydantic / playwright / httpx 到该 Python 环境）
4. 用已装 Python 执行 `python -m playwright install chromium`（下载 Chromium 浏览器，约 150MB）

> **注**：步骤 3/4 是耗时操作（网络下载 + 解压），需在 IO 线程执行并向 UI 报告进度。考虑将 crawl4ai + 依赖 + Chromium 预打包成一个 zip（类似 webnovel-writer 的打包方式），用户下载一次即完成，避免在设备上跑 pip。这是推荐方案，见 §10 实现清单。

#### 步骤 3：启用 MCP server（自动）

安装完成后，LxChat 自动在 `McpServerConfig` 中注册一条 stdio 类型的 MCP server 配置：

```
id        = "crawl4ai-mcp"  (固定 id, 便于市场管理与升级)
name      = "crawl4ai 网页爬虫"
enabled   = true
transport = STDIO  ← 新增枚举值
command   = "<pythonBin>"  ← runtime-python 的 python 可执行路径
args      = ["-m", "crawler_agent.mcp_server"]
cwd       = "<crawl4ai-mcp 安装根目录>"
env       = { CRAWL4AI_MCP_LOG = "INFO", PYTHONPATH = "<crawl4ai-mcp 安装根目录>" }
```

`McpRegistry` 监听到新配置后：
1. 创建 `StdioMcpTransport`（持有 `RuntimeProcessManager` 引用）
2. `RuntimeProcessManager.start("crawl4ai-mcp", command, env, cwd)` 启动 Python 进程
3. `McpProtocolClient.initialize()` 完成 JSON-RPC 握手（协议版本协商）
4. `tools/list` 发现 4 个工具，生成 `McpToolDescriptor` 快照
5. `McpToolProvider.definitions()` 将 4 个工具暴露给模型

#### 步骤 4：模型调用工具（对话中）

用户：「帮我抓取 https://example.com 的内容」

模型识别意图 → 调用 `mcp_crawl4aimcp_scrape_<digest>` 工具，参数 `{ "url": "https://example.com" }` → `McpToolProvider.execute` → `McpRegistry.execute` → `McpProtocolClient.callTool("scrape", {url})` → `StdioMcpTransport` 写入 Python 进程 stdin → crawl4ai 用 Playwright 渲染页面 → 返回 Markdown → `McpOutputGuard` 截断 → 返回模型 → 模型总结后回复用户。

---

## 6. 集成点设计

### 6.1 新增 `StdioMcpTransport`

**位置**：`mcp/McpClientTransport.kt`（同文件追加，或新建 `mcp/StdioMcpTransport.kt`）

**职责**：实现 `McpClientTransport` 接口，通过子进程 stdin/stdout 收发 JSON-RPC 消息。

**设计要点**：

```
class StdioMcpTransport(
    command: List<String>,        // ["<pythonBin>", "-m", "crawler_agent.mcp_server"]
    env: Map<String, String>,     // 环境变量
    workingDir: File?,            // crawl4ai-mcp 安装根目录
    processManager: RuntimeProcessManager,  // 复用进程管理
    engineId: String,             // "crawl4ai-mcp"，用于进程跟踪与空闲回收
    protocolVersion: String,
) : McpClientTransport
```

- **进程启动**：委托 `RuntimeProcessManager.start(engineId, command, env, workingDir)`，复用空闲回收 / 崩溃清理 / `stopHandler`
- **stdin 写入**：每条 JSON-RPC envelope 序列化为一行 JSON + `\n`，写入进程 `outputStream`（即进程的 stdin）。需同步锁防并发写
- **stdout 读取**：后台守护线程持续读进程 `inputStream`（即进程的 stdout），按行解析 JSON-RPC envelope，按 `id` 路由到 `CompletableDeferred`（请求-响应配对）；server-initiated 请求（如 elicitation）转交 `serverRequestListener`
- **stderr**：crawl4ai 的日志走 stderr（`_StderrLogger` 已将日志重定向到 stderr），LxChat 可选读 stderr 用于诊断，但不参与协议
- **session 语义**：stdio 是单 session 长连接，`ensureReady()` 返回固定 generation（进程重启才换 generation）；`resetSession()` 标记需重新 initialize
- **进程崩溃**：stdout 读到 EOF 或进程 `isAlive == false` 时，所有 pending 请求以 `IOException("MCP server process exited")` 失败，触发 `McpRegistry` 重试逻辑（重启进程）
- **关闭**：`close()` 调用 `RuntimeProcessManager.stop(engineId)`，优雅 `destroy()` → 2s 超时 → `destroyForcibly()`

> **注**：`RuntimeProcessManager.start()` 当前会 `redirectErrorStream(true)` 并 drain stdout。stdio MCP 传输**不能**用这个构造——stdin/stdout 是协议通道，不能被 drain。需在 `RuntimeProcessManager` 新增一个 `startPiped()` 重载（不 redirectErrorStream、不 drain stdout），或让 `StdioMcpTransport` 直接用 `ProcessBuilder` 自管进程。推荐后者：`StdioMcpTransport` 自管 `Process`，但仍向 `RuntimeProcessManager` 注册 `touch` / `stopHandler` 以复用空闲回收。

### 6.2 扩展 `McpTransportType`

**位置**：`data/SettingsContracts.kt`

```
enum class McpTransportType {
    @SerialName("streamable_http") STREAMABLE_HTTP,
    @SerialName("sse")             SSE,
    @SerialName("stdio")           STDIO,   // ← 新增
}
```

### 6.3 扩展 `McpServerConfig`

**位置**：`data/SettingsContracts.kt`

新增 stdio 启动所需字段（HTTP/SSE 模式下留空）：

```
data class McpServerConfig(
    ...现有字段...,
    val command: List<String> = emptyList(),  // stdio: 启动命令，如 ["<pythonBin>", "-m", "crawler_agent.mcp_server"]
    val args: List<String> = emptyList(),     // 预留（也可合并进 command）
    val workingDir: String = "",              // stdio: 工作目录
)
```

> **注**：`url` 字段在 stdio 模式下留空。`McpRegistry.normalizeEndpoint()` 需放宽：stdio 模式跳过 URL 校验。

### 6.4 扩展 `McpRegistry`

**位置**：`mcp/McpRegistry.kt`

- `normalizeEndpoint()`：stdio 模式跳过 http/https 校验，改为校验 `command` 非空
- `replaceRuntimeLocked()`：创建 `McpProtocolClient` 时，stdio 模式传入 `StdioMcpTransport`（需注入 `RuntimeProcessManager` 引用到 `McpRegistry` 构造参数）
- `McpRegistry` 构造参数新增 `processManager: RuntimeProcessManager`（由 `AppContainer` 注入）

### 6.5 扩展 `McpProtocolClient`

**位置**：`mcp/McpProtocolClient.kt`

- `protocolVersion`：stdio 模式使用 `2025-06-18`（stdio 标准，crawl4ai 的 mcp 库默认协商）
- `createMcpClientTransport()`：新增 `STDIO` 分支，构造 `StdioMcpTransport`

### 6.6 扩展 `SettingsMcpPage` UI

**位置**：`ui/settings/SettingsMcpPage.kt`

- 传输类型下拉新增「stdio」选项
- stdio 模式下：隐藏 URL 输入框，显示命令 / 参数 / 工作目录输入框（或对内置 crawl4ai 只显示「已安装」状态 + 启用开关，不暴露原始命令编辑，降低用户心智负担）

### 6.7 市场注册 crawl4ai SKILL

**位置**：`plugin/market/BuiltinMarketSources.kt`

在 `fetchBuiltinSkillCatalog()` 新增 crawl4ai 条目（见 §5.2 步骤 2）。

### 6.8 集成点总览

| 改动文件 | 改动内容 | 工作量 |
|----------|----------|--------|
| `mcp/StdioMcpTransport.kt`（新建） | stdio 传输实现 | 中 |
| `mcp/McpClientTransport.kt` | `createMcpClientTransport` 新增 STDIO 分支 | 小 |
| `mcp/McpRegistry.kt` | 注入 `RuntimeProcessManager`；`normalizeEndpoint` 放宽；stdio 创建 transport | 小 |
| `data/SettingsContracts.kt` | `McpTransportType.STDIO`；`McpServerConfig.command/args/workingDir` | 小 |
| `plugin/market/BuiltinMarketSources.kt` | crawl4ai SKILL 市场条目 | 小 |
| `runtime/RuntimeToolProvider.kt` | crawl4ai 安装/启动工具（参照 webnovel-writer） | 中 |
| `ui/settings/SettingsMcpPage.kt` | stdio 传输 UI 选项 | 小 |
| `di/AppContainer.kt` | `McpRegistry` 注入 `RuntimeProcessManager` | 小 |

---

## 7. 工具暴露映射

### 7.1 映射机制（复用现有）

crawl4ai 的 4 个工具通过 LxChat 现有机制自动映射为 `ToolDescriptor`，**无需额外代码**：

1. `McpProtocolClient.listTools()` 调用 `tools/list`，获取 4 个 `McpRemoteTool`（含 name / description / inputSchema）
2. `McpRegistry.launchConnectionLoop()` 为每个 remote tool 生成 `McpToolDescriptor`：
   - `publicName = publicMcpToolName("crawl4ai-mcp", remote.name)` → `mcp_crawl4aimcp_<toolKey>_<digest>`
   - `enabled = remote.name !in config.disabledTools`（默认全启用）
3. `McpToolProvider.definitions()` 将 `McpToolDescriptor.asToolDefinition()` 暴露给模型
4. `McpToolProvider.handles(name)` / `execute(name, args)` 路由到 `McpRegistry.execute()`

### 7.2 工具清单（crawl4ai-mcp server id = `crawl4ai-mcp`）

| crawl4ai 工具 | LxChat publicName | 风险等级 | 默认需批准 | 说明 |
|---|---|---|---|---|
| `scrape` | `mcp_crawl4aimcp_scrape_<6位hex>` | Moderate | ✅ 是 | 单页抓取，返回 Markdown + links |
| `crawl` | `mcp_crawl4aimcp_crawl_<6位hex>` | Moderate | ✅ 是 | 多页广度爬取（max_depth ≤ 4, max_pages ≤ 100） |
| `crawl_site` | `mcp_crawl4aimcp_crawlsite_<6位hex>` | Moderate | ✅ 是 | 全站爬取（max_pages ≤ 5000，始终持久化） |
| `crawl_sitemap` | `mcp_crawl4aimcp_crawlsitemap_<6位hex>` | Moderate | ✅ 是 | sitemap 爬取（max_entries ≤ 1000，始终持久化） |

> **注**：`McpToolProvider.riskLevel()` 对所有 MCP 工具返回 `RiskLevel.Moderate`，`requiresApprovalByDefault()` 返回 `true`。用户首次调用每个工具需批准（现有机制，无需改动）。

### 7.3 inputSchema 转换

crawl4ai 用 Pydantic `BaseModel.model_json_schema()` 生成 JSON Schema。LxChat `McpModels.kt` 的 `JsonObject.toToolParameters()` / `toToolProperty()` 已能解析标准 JSON Schema（type / properties / required / items / description），**无需改动**。

### 7.4 输出处理

crawl4ai 工具返回 `content` 数组（type=text 的 Markdown）。LxChat `McpProtocolClient.parseCallPayload()` 已能解析：
- `text` → 文本内容
- `image` → 图片附件（crawl4ai 不返回图片，但机制就绪）
- `resource` → 资源内容
- `structuredContent` → 结构化 JSON

`McpOutputGuard` 自动截断超长输出防 token 爆炸（现有机制）。

---

## 8. 生命周期管理

### 8.1 状态机

```
                ┌──────────┐
                │  IDLE    │ ← 未安装 / 已禁用
                └────┬─────┘
                     │ 启用
                     ▼
                ┌──────────┐  进程启动失败/崩溃
                │CONNECTING│─────────────┐
                └────┬─────┘             │
                     │ initialize 成功   │
                     ▼                   │
                ┌──────────┐             │
                │CONNECTED │             │
                └────┬─────┘             │
                     │ 禁用/卸载         │
                     ▼                   │
                ┌──────────┐             │
                │  IDLE    │             │
                └──────────┘             │
                                         ▼
                ┌──────────┐  重试 5s→5min 指数退避
                │  ERROR   │─────────────┘
                └──────────┘
```

### 8.2 启动

- 触发：用户启用 crawl4ai MCP server，或模型首次调用 crawl4ai 工具时懒启动
- 动作：`RuntimeProcessManager.start("crawl4ai-mcp", command, env, cwd)` → `McpProtocolClient.initialize()` → `tools/list`
- 超时：`Constants.NETWORK_CONNECT_TIMEOUT_MS`（连接）+ `Constants.NETWORK_TOOL_TIMEOUT_MS`（initialize）

### 8.3 停止

- 触发：用户禁用 / 卸载 / App 退出 / 空闲超时
- 动作：`RuntimeProcessManager.stop("crawl4ai-mcp")` → `process.destroy()` → 2s 超时 → `destroyForcibly()`
- `McpRegistry.Runtime.close()` 取消 connectionJob 并 close transport

### 8.4 空闲回收（复用现有）

`RuntimeProcessManager` 空闲 10 分钟自动 stop 进程。每次工具调用 `touch("crawl4ai-mcp")` 重置计时。下次调用时 `McpProtocolClient.ensureInitializedLocked()` 检测到进程已退出，重新启动并 initialize。

### 8.5 崩溃恢复（复用现有 + 增强）

- `StdioMcpTransport` 检测到 stdout EOF 或进程 `isAlive == false` → 所有 pending 请求失败
- `McpRegistry.execute()` catch 异常 → `markError(runtime, e)` + `scheduleRetry(runtime)`
- `launchConnectionLoop()` 重试：5s → 10s → 20s → ... → 5min 上限（指数退避，复用现有 `INITIAL_RETRY_MS` / `MAX_RETRY_MS`）
- 重试时重启 Python 进程并重新 initialize

### 8.6 升级

- crawl4ai SKILL 有新版本时，市场提示更新
- 更新流程：stop 进程 → 下载新包 → 解压覆盖 → 重启进程
- 工具列表可能变化（如新增工具），`tools/list` 重新发现后自动反映

---

## 9. 权限和安全

### 9.1 网页爬取安全（crawl4ai 内置）

crawl4ai-mcp-server 的 `safety.py` 已内置 SSRF 防护，**无需 LxChat 重复实现**：

| 防护项 | 实现 |
|--------|------|
| localhost | 阻止 `localhost` / `127.0.0.1` / `::1` |
| 私有 IP | 阻止 RFC1918（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16） |
| file 协议 | 阻止 `file://` scheme |
| 内网域名 | 阻止 `.local` / `.internal` / `.lan` |

### 9.2 LxChat 侧安全

| 风险 | 缓解 |
|------|------|
| **工具调用批准** | `McpToolProvider.requiresApprovalByDefault()` 返回 `true`，用户首次调用每个工具需批准（现有机制） |
| **输出截断** | `McpOutputGuard` 截断超长 Markdown 防 token 爆炸（现有机制）；crawl4ai 的 `output_dir` 模式可进一步避免（仅返回 manifest 元数据） |
| **进程隔离** | Python 进程以 App UID 运行，受 Android 沙箱隔离；无 root 权限 |
| **网络权限** | LxChat 已声明 `INTERNET` 权限；crawl4ai 通过 Playwright 访问网络，受同一沙箱约束 |
| **存储权限** | crawl4ai 持久化输出到 `cwd/crawls/`，位于 App noBackupFiles 目录，无需外部存储权限 |
| **Chromium 体积** | Playwright Chromium 约 150MB，安装时提示用户；可选预打包进 zip 减少安装步骤 |
| **隐私合规** | 网页爬取行为需在隐私政策中声明（LxChat 已有 `PRIVACY.md`，需补充第三方插件数据使用声明——见任务 #19 已完成） |

### 9.3 用户可控约束

| 约束 | 默认 | 用户可调 |
|------|------|----------|
| `max_depth`（crawl） | 1 | ≤ 4 |
| `max_pages`（crawl） | 5 | ≤ 100 |
| `max_pages`（crawl_site） | 200 | ≤ 5000 |
| `max_entries`（crawl_sitemap） | 1000 | — |
| `timeout_sec` | 45/60/600/900 | ≤ 上限 |
| `same_domain_only` | true | 可关 |
| `adaptive`（自适应停止） | false | 可开 |

LxChat 可在 `McpServerConfig.disabledTools` 中按工具名禁用（如只允许 `scrape`，禁用 `crawl_site`）。

---

## 10. 实现清单

### 10.1 Kotlin 改动（按优先级）

| 优先级 | 文件 | 改动 |
|--------|------|------|
| P0 | `mcp/StdioMcpTransport.kt`（新建） | stdio 传输：进程管理、stdin 写、stdout 读、请求-响应路由、崩溃检测 |
| P0 | `data/SettingsContracts.kt` | `McpTransportType.STDIO`；`McpServerConfig.command/args/workingDir` |
| P0 | `mcp/McpClientTransport.kt` | `createMcpClientTransport` 新增 STDIO 分支 |
| P0 | `mcp/McpRegistry.kt` | 注入 `RuntimeProcessManager`；`normalizeEndpoint` 放宽 stdio；`replaceRuntimeLocked` 支持 stdio |
| P0 | `di/AppContainer.kt` | `McpRegistry` 构造注入 `RuntimeProcessManager` |
| P1 | `plugin/market/BuiltinMarketSources.kt` | crawl4ai SKILL 市场条目 |
| P1 | `runtime/RuntimeToolProvider.kt` | crawl4ai 安装/启动工具（参照 webnovel-writer 的 `ensureDependencyRoot` + `pythonBin` 模式） |
| P2 | `ui/settings/SettingsMcpPage.kt` | stdio 传输 UI 选项；crawl4ai 内置卡片（已安装状态 + 启用开关） |
| P2 | `app/src/main/res/values/strings.xml` + `values-zh/strings.xml` | crawl4ai 相关字符串 |

### 10.2 Python 侧打包（推荐）

为避免在 Android 设备上跑 `pip install` 和 `playwright install`（耗时且易失败），**推荐预打包方案**：

1. 在 PC 上用 Python 3.11 arm64 交叉环境（或 Termux）安装全部依赖到目标目录：
   ```
   pip install -r requirements.txt --target=./crawl4ai-bundle/pylibs
   python -m playwright install chromium  # 下载 Chromium 到 ./crawl4ai-bundle/playwright
   ```
2. 将 `crawler_agent/` 源码 + `pylibs/` + `playwright/` + `requirements.txt` 打包成 `crawl4ai-mcp-1.0.0-android-arm64.zip`
3. 发布到 `https://github.com/ojbkxc/lxchat-runtime/releases/download/crawl4ai-v1.0.0/`
4. 用户在 LxChat 市场一键下载解压即用，无需在设备上跑 pip

**启动命令**调整为指向预打包的 Python 库路径：

```
command = ["<pythonBin>", "-m", "crawler_agent.mcp_server"]
env = {
    PYTHONPATH = "<crawl4ai根>/pylibs:<crawl4ai根>",
    PLAYWRIGHT_BROWSERS_PATH = "<crawl4ai根>/playwright",
    CRAWL4AI_MCP_LOG = "INFO",
}
cwd = "<crawl4ai根>"
```

### 10.3 备选：设备上 pip 安装

若预打包体积过大（>200MB）不可接受，备选在设备上安装：

1. 下载 crawl4ai-mcp-server 源码包（小，~50KB）
2. `pythonBin -m pip install -r requirements.txt`（安装到 site-packages，约 80MB）
3. `pythonBin -m playwright install chromium`（下载 Chromium，约 150MB）
4. 需向 UI 报告两步进度，且处理网络失败重试

> 此方案需 Python 支持 `pip` 模块。LxChat 现有 `runtime-python` 是否包含 pip 需验证；若不含，需在 Python 打包时包含 `ensurepip` + `pip`。

---

## 11. 风险与权衡

| 风险 | 影响 | 缓解 |
|------|------|------|
| **Chromium 体积大** | 安装包 +150MB，用户存储压力 | 预打包为可选扩展，不进基础包；安装时明确提示体积 |
| **Playwright 在 Android 兼容性** | Chromium arm64 在 Android 可能缺依赖 | 用 Termux/NDK 交叉编译的 Chromium；先在真机验证 |
| **stdio 传输并发** | 多工具并发调用时 stdin 写竞争 | `StdioMcpTransport` 同步锁序列化写；`McpProtocolClient.mutex` 已序列化请求 |
| **进程崩溃恢复延迟** | 崩溃后首次重试 5s，用户感知卡顿 | UI 显示「正在重启爬虫服务」；降低首次重试到 2s |
| **Python 运行时体积** | runtime-python 约 30MB | 已有功能，crawl4ai 复用，无额外成本 |
| **内存占用** | Chromium + Python 进程常驻约 200-300MB | 空闲 10 分钟自动回收（现有机制）；仅在有爬取需求时启动 |
| **crawl4ai 上游版本** | crawl4ai 0.7.x 仍在迭代，API 可能变 | 锁定 `crawl4ai>=0.7.0,<0.8.0`；预打包固定版本 |
| **MCP 协议版本协商** | crawl4ai 的 mcp 库版本与 LxChat 支持的协议版本需匹配 | LxChat 已支持 2025-06-18 / 2025-03-26 / 2024-11-05；crawl4ai 用 mcp>=1.1.0 默认协商，应能命中 |

---

## 12. 验证计划

### 12.1 单元测试

- `StdioMcpTransportTest`：mock 进程，验证 stdin 写 / stdout 读 / 请求-响应路由 / 崩溃检测
- `McpRegistryTest`：stdio 配置 reconcile / 启停 / 重试
- `McpServerConfigTest`：stdio 序列化 / 反序列化

### 12.2 集成测试（真机）

1. 安装 runtime-python → 安装 crawl4ai SKILL → 启用
2. 验证 `McpRegistry.snapshots` 中 crawl4ai-mcp 状态为 CONNECTED，4 个工具出现
3. 模型调用 `scrape` 抓取 `https://example.com` → 返回非空 Markdown
4. 模型调用 `crawl` 抓取 `https://docs.crawl4ai.com` max_pages=2 → 返回 2 页
5. 禁用 → 验证进程停止
6. 杀掉 Python 进程 → 验证 LxChat 自动重启并恢复

### 12.3 安全测试

- 调用 `scrape` 抓取 `http://127.0.0.1` → 被 crawl4ai safety 拒绝
- 调用 `scrape` 抓取 `file:///etc/hosts` → 被拒绝
- 验证 LxChat 工具调用批准门禁生效

---

## 13. 未来演进

| 演进 | 说明 |
|------|------|
| **Streamable HTTP 模式** | crawl4ai-mcp-server 上游已考虑支持 SSE/Streamable HTTP（见 CLAUDE.md「consider SSE/Streamable HTTP later」）。届时 LxChat 可直接用现有 HTTP 传输，无需 stdio |
| **更多 Python MCP server** | 本设计的 stdio 传输通用，任何 stdio MCP server（如 mcp-server-fetch、mcp-server-filesystem）均可同样接入 |
| **爬取结果持久化浏览** | crawl4ai 的 `output_dir` 模式落盘 Markdown，LxChat 可加文件浏览器查看历史爬取结果 |
| **Chromium 复用** | 若未来接入更多需浏览器的 MCP server，可共享同一 Chromium 实例 |
| **自适应爬取策略** | crawl4ai 的 `adaptive` 模式基于内容量停止；可接入 LLM 判断「已获取足够信息」的更智能策略 |

---

## 附录 A：crawl4ai-mcp-server 工具完整签名

### scrape

```json
{
  "name": "scrape",
  "description": "Fetch a single URL with Crawl4AI. Returns markdown + links by default. If output_dir provided, persists to disk and returns metadata only.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "url": { "type": "string", "format": "uri" },
      "crawler": { "type": "object", "default": {} },
      "browser": { "type": "object", "default": {} },
      "script": { "type": "string" },
      "timeout_sec": { "type": "integer", "default": 45, "minimum": 1, "maximum": 600 },
      "output_dir": { "type": "string" }
    },
    "required": ["url"]
  }
}
```

### crawl

```json
{
  "name": "crawl",
  "description": "Breadth-first crawl up to max_depth starting from seed_url. Respects same_domain_only and allows include/exclude regex patterns.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "seed_url": { "type": "string", "format": "uri" },
      "max_depth": { "type": "integer", "default": 1, "minimum": 1, "maximum": 4 },
      "max_pages": { "type": "integer", "default": 5, "minimum": 1, "maximum": 100 },
      "same_domain_only": { "type": "boolean", "default": true },
      "include_patterns": { "type": "array", "items": { "type": "string" }, "default": [] },
      "exclude_patterns": { "type": "array", "items": { "type": "string" }, "default": [] },
      "adaptive": { "type": "boolean", "default": false },
      "output_dir": { "type": "string" }
    },
    "required": ["seed_url"]
  }
}
```

### crawl_site / crawl_sitemap

见 §3.3 工具签名表，均要求 `output_dir`（始终持久化）。

---

## 附录 B：LxChat 现有 MCP 协议版本支持

| 协议版本 | 传输 | LxChat 支持 | 用途 |
|----------|------|-------------|------|
| 2025-11-25 | Streamable HTTP | ✅ | 最新（elicitation） |
| 2025-06-18 | Streamable HTTP / stdio | ✅ | stdio 标准 |
| 2025-03-26 | Streamable HTTP | ✅ | 中间版 |
| 2024-11-05 | SSE / stdio | ✅ | legacy |

crawl4ai-mcp-server 使用 `mcp>=1.1.0`，默认协商最新版本。stdio 模式下 LxChat 用 `2025-06-18` 发起 initialize，crawl4ai 应接受或协商降级到支持的版本。

---

*文档结束。本设计复用 LxChat 现有 MCP 协议层、Python 运行时引擎、进程管理与插件市场体系，仅新增 stdio 传输实现，即可接入 crawl4ai-mcp-server 的 4 个网页爬取工具。*