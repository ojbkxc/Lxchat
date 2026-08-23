# dsh-im ↔ Lxchat 功能缺口报告

> 生成时间：2026-08-24
> 对比对象：
> - **dsh-im** `C:\GitHub\dsh-im`（Node.js 插件，把 9 种 IM 机器人 + AI Office 接入本机 DeepSeek Harness）
> - **Lxchat** `C:\GitHub\Lxchat`（Android Kotlin App，自身即 AI 客户端，已接入 8 种 IM 渠道）

---

## 一、总体结论

Lxchat 已实现 IM 渠道接收消息 → AI 处理 → IM 渠道回复 的**核心闭环**，覆盖 8 个原生渠道（微信/Telegram/飞书/钉钉/企微/QQ/Discord/Slack）+ SMS HTTP 回退，并支持多 bot 多渠道、主动消息、分段发送、打字延迟。

但相比 dsh-im，Lxchat **缺失以下 11 类功能**（按优先级排序）：

| # | 缺失功能 | 优先级 | 涉及 dsh-im 模块数 |
|---|---------|:------:|:-----------------:|
| 1 | IM 命令系统（`/compact` `/model` `/preset` `/workspace` `/session` `/stop` `/steer`） | **高** | 7 |
| 2 | 图片识别（入站图片→喂给 agent） | **高** | 1 |
| 3 | 流式回复（边生成边 edit 消息） | **高** | 1 |
| 4 | Harness 工具审批转发到 IM | **高** | 1 |
| 5 | Harness 补充提问转发到 IM | **高** | 1 |
| 6 | 连接测试（从设置页发测试消息） | 中 | 1 |
| 7 | Agent Preset 切换 | 中 | 2 |
| 8 | AI Office Connector | 中 | 6 |
| 9 | WhatsApp 渠道 | 中 | 8 |
| 10 | 工作区绑定（bot↔本地目录） | 低 | 2 |
| 11 | 会话绑定到外部 session ID | 低 | 2 |

> **不适用项**（已在分析中排除）：
> - `harness-client.mjs` — Lxchat 自身即 AI 客户端，无需连接外部 Harness 进程
> - `lib/index.js` `lib/client.js` — 构建打包产物
> - `plugin-src/client/*` 的 React UI — Lxchat 是 Android 原生 UI，需有等价 Compose 页面但非"缺失"
> - `worker/random-badge.mjs`、README badges — 广告相关，按要求不实现
> - `rpc-authority.mjs` — Android app 本身即本地，无需 loopback/trusted-host 区分

---

## 二、dsh-im 模块逐项对比

### A. src/channels/shared/ （20 个模块）

#### A1. harness-client.mjs（1462 行）— ⚠️ 不适用

| 项 | 内容 |
|----|------|
| **功能** | HTTP RPC 客户端，连接本机 DeepSeek Harness 进程，管理 session、发送 ask 请求、执行命令、控制运行中的 turn |
| **Lxchat 对应** | 不适用。Lxchat 自身即 AI 客户端，`TaskExecutionEngine.runOnce()` 承担等价职责 |
| **需要的文件** | 无需创建 |
| **优先级** | — |

#### A2. token-bot-controller.mjs（347 行）— ✅ 已有等价

| 项 | 内容 |
|----|------|
| **功能** | 基于 token 的 IM 机器人控制器：管理多 bot 运行时、凭据解析、配置存储、连接生命周期 |
| **Lxchat 对应** | `ImBridgeService.kt` + `ImGatewayStore.kt` + `ImMultiGatewayConfig` 已实现多 bot 管理、凭据持久化、配置变更监听 |
| **需要修改的文件** | 无（已完整） |
| **优先级** | — |

#### A3. agent-preset.mjs（74 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | Agent Preset ID 验证（正则 `/^[a-z0-9][a-z0-9-]*$/`）、目录归一化、从 Host 拉取 preset 列表 |
| **Lxchat 对应** | **无**。Lxchat 没有 Agent Preset 概念 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/AgentPreset.kt`（数据类 + 验证）、`app/src/main/java/com/lxseek/chat/im/AgentPresetStore.kt`（preset 目录存储） |
| **优先级** | **中** |

#### A4. bot-workspace-store.mjs（1056 行）— ❌ 缺失（部分不适用）

| 项 | 内容 |
|----|------|
| **功能** | 每个 bot 绑定到一个 Harness 工作区目录（绝对路径），支持工作区切换、Agent Preset 绑定、工作区移除监听 |
| **Lxchat 对应** | **无**。Lxchat 是 Android app，没有本地工作区目录概念；但"每个 bot 绑定到一组配置（system prompt / model / preset）"的需求存在 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/BotProfileStore.kt`（每个 bot 的 profile：绑定 model、systemPrompt、agentPreset） |
| **需要修改的文件** | `ImGatewayConfig` 增加 `boundProfileId` 字段；`ImGatewayStore` 增加 profile 持久化 |
| **优先级** | **低**（工作区路径不适用，但 bot profile 绑定有价值） |

#### A5. compact-command.mjs（95 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | `/compact` 命令：触发 Harness 上下文压缩，把长对话历史压缩成摘要 |
| **Lxchat 对应** | **无**。Lxchat 的 `ImPollingReceiver` 直接把消息文本喂给 agent，没有命令路由 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/command/CompactCommand.kt`、`app/src/main/java/com/lxseek/chat/im/command/CommandRouter.kt`（命令路由器） |
| **需要修改的文件** | `ImPollingReceiver.feedInboundBatch` 在调用 `runOnce` 前先过 `CommandRouter.tryRoute()` |
| **优先级** | **高** |

#### A6. connection-test.mjs（54 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | 连接测试：记住最近一个私聊消息发送者，从插件设置页发一条测试消息验证 bot 连通性 |
| **Lxchat 对应** | **无**。设置页没有"测试连接"按钮 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/ConnectionTester.kt` |
| **需要修改的文件** | IM 设置 UI 页面增加"测试连接"按钮 |
| **优先级** | **中** |

#### A7. control-command.mjs（84 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | `/stop` 命令：停止当前正在运行的 agent turn；`/steer <指令>` 命令：向正在运行的 turn 注入补充指令 |
| **Lxchat 对应** | **无**。`TaskExecutionEngine` 有 `Busy` 状态但没有从 IM 停止/引导的路径 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/command/ControlCommand.kt` |
| **需要修改的文件** | `TaskExecutionEngine` 增加 `stopActiveTurn(convId)` 和 `steerActiveTurn(convId, text)` API；`CommandRouter` 注册此命令 |
| **优先级** | **高** |

#### A8. conversation-state-store.mjs（112 行）— ✅ 已有等价

| 项 | 内容 |
|----|------|
| **功能** | 会话状态持久化：IM 会话 ↔ Harness session 绑定、已读消息 ID 去重（最近 1000 条）、cursor |
| **Lxchat 对应** | `ImRuntimeState`（conversationBindings + seenMessageIds，最近 2000 条）+ `ImGatewayStore` DataStore 持久化 |
| **需要修改的文件** | 无（已完整，Lxchat 的 MAX_SEEN=2000 比 dsh-im 的 1000 更大） |
| **优先级** | — |

#### A9. editable-message-stream.mjs（81 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | 流式回复：先创建一条"正在处理…"消息，随 agent 生成逐段 edit 更新内容，最后 finish 时发送剩余部分 |
| **Lxchat 对应** | **无**。`ImPollingReceiver` 等 agent 完整生成后一次性 `segmentSender.send`。`TelegramBotApi.editMessageText` 存在但未用于流式回复 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/StreamingReplySender.kt`（封装 create→update→finish 流程） |
| **需要修改的文件** | `MessageChannel` 接口增加 `editMessage(messageId, text)` 可选方法；`TelegramChannel` `DiscordChannel` `SlackChannel` `FeishuChannel` 等实现 editMessage；`ImPollingReceiver` 改为流式回调 |
| **优先级** | **高** |

#### A10. harness-approval.mjs（476 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | Harness 工具调用审批转发：当 agent 要执行需审批的工具时，把审批请求（工具名+参数+原因）转发到 IM 让用户回复"批准"/"拒绝"，支持群聊 @机器人 |
| **Lxchat 对应** | **无**。`ToolApproval.kt` 是本地 UI 审批，不是 IM 审批转发。`AskUserToolProvider` 是 agent 主动问用户，不是工具审批钩子 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/ImApprovalBridge.kt`（把 ToolApprovalRequest 转发到 IM channel，等待 IM 回复） |
| **需要修改的文件** | `TaskExecutionEngine` 的审批钩子接入 `ImApprovalBridge`；`ImPollingReceiver` 识别审批回复（"批准"/"拒绝"） |
| **优先级** | **高** |

#### A11. harness-question.mjs（85 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | Harness 补充提问转发：当 agent 需要补充信息时，把问题（含选项、多选）转发到 IM 收集答案，解析选项序号/文字 |
| **Lxchat 对应** | **无**。`AskUserToolProvider` 是本地 UI 问答，不是 IM 问答转发 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/ImQuestionBridge.kt` |
| **需要修改的文件** | `AskUserToolProvider` 或新建 `ImAskUserToolProvider` 把问答转发到 IM；`ImPollingReceiver` 识别问题回复 |
| **优先级** | **高** |

#### A12. harness-session-binding.mjs（110 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | 把一个已存在的 Harness session（通过 session ID）绑定到 bot 会话：查询工作区、验证 session 归属、创建 session.adopt |
| **Lxchat 对应** | **无**。`ImRuntimeState.conversationBindings` 是 IM 会话 ↔ Lxchat 会话绑定，不是"绑定到外部 session ID" |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/ExternalSessionBinding.kt`（如果需要绑定到外部 session） |
| **优先级** | **低**（Lxchat 自管会话，此需求弱） |

#### A13. image-prompt.mjs（299 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | 图片识别：下载图片（HTTPS、限制大小/数量/总大小、禁止重定向）、转 base64、附加到 prompt 作为 multimodal content |
| **Lxchat 对应** | **无**。`ImMessage` 只有 `text` 字段，无 image 字段。`WeixinIlinkApi` 能解密图片但没有喂给 agent 的路径。其他渠道明确说"non-text messages are dropped" |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/ImagePromptExtractor.kt`（下载/转换图片为 model multimodal input） |
| **需要修改的文件** | `ImMessage` 增加 `images: List<ImImage>` 字段；`MessageChannel.fetchMessages` 实现提取图片；`ImPollingReceiver.feedInboundBatch` 把图片附加到 `TaskExecutionEngine.runOnce` 的 multimodal input；各渠道（telegram/feishu/dingtalk/discord/qq/slack/wecom）实现图片下载 |
| **优先级** | **高** |

#### A14. model-command.mjs（352 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | `/model <序号>` 或 `/model <provider>/<model>`：切换当前会话模型；`/models`：列出可用模型分组 |
| **Lxchat 对应** | **无**。`ImGatewayConfig.autoReplyModel` 是配置时指定，不能从 IM 动态切换 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/command/ModelCommand.kt` |
| **需要修改的文件** | `ImRuntimeState` 增加 `currentModel` 字段；`CommandRouter` 注册此命令；`ImPollingReceiver.runOnce` 优先用 state 中的 model |
| **优先级** | **高** |

#### A15. preset-command.mjs（305 行）— ❌ 缺失

| 项 | 内容 |
|----|------|
| **功能** | `/preset` 查看当前 Agent Preset；`/preset <序号|ID>` 切换；`/presetlist` 列出可用 preset；`/preset --default` 跟随 Host 默认 |
| **Lxchat 对应** | **无** |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/command/PresetCommand.kt` |
| **需要修改的文件** | `CommandRouter` 注册此命令；依赖 A3 的 `AgentPresetStore` |
| **优先级** | **中** |

#### A16. session-binding-lock.mjs（40 行）— ✅ 已有等价

| 项 | 内容 |
|----|------|
| **功能** | 会话绑定锁：序列化同一会话的绑定事务，防止并发竞争 |
| **Lxchat 对应** | `ImGatewayStore.updateChannelState` 使用 DataStore `edit` 原子操作，天然序列化 |
| **需要修改的文件** | 无 |
| **优先级** | — |

#### A17. text-harness-bridge.mjs（808 行）— ⚠️ 部分已有

| 项 | 内容 |
|----|------|
| **功能** | 文本桥接核心：入站消息处理、命令路由（compact/control/model/preset/workspace）、审批队列、问题队列、出站回复、流式更新、连接测试目标记忆 |
| **Lxchat 对应** | `ImPollingReceiver.feedInboundBatch` 实现了入站→agent→回复主流程，但**缺命令路由、审批队列、问题队列、流式更新** |
| **需要修改的文件** | `ImPollingReceiver` 接入 `CommandRouter`、`ImApprovalBridge`、`ImQuestionBridge`、`StreamingReplySender` |
| **优先级** | **高**（这是把 A5/A7/A10/A11/A9/A14 串起来的总集成点） |

#### A18. token-config-store.mjs（193 行）— ✅ 已有等价

| 项 | 内容 |
|----|------|
| **功能** | 基于 token 的 bot 配置存储：list/get/save/remove，botId/tokenRef 派生（SHA-256），platformId 脱敏 |
| **Lxchat 对应** | `ImGatewayStore` 的 multiConfig API + `ImMultiGatewayConfig.upsert/remove` |
| **需要修改的文件** | 无 |
| **优先级** | — |

#### A19. workspace-command.mjs（375 行）— ❌ 缺失（部分不适用）

| 项 | 内容 |
|----|------|
| **功能** | `/workspace <路径>` 切换工作区；`/workspacelist` 列出；`/sessionlist` 列出会话；`/session <ID>` 绑定到指定 session |
| **Lxchat 对应** | **无**。工作区路径不适用（Android 无本地工作区目录）；但 `/sessionlist` 列出会话、`/session` 切换会话有价值 |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/command/SessionListCommand.kt`、`app/src/main/java/com/lxseek/chat/im/command/SessionBindCommand.kt` |
| **需要修改的文件** | `CommandRouter` 注册 |
| **优先级** | **低** |

#### A20. workspace-session.mjs（70 行）— ⚠️ 部分已有

| 项 | 内容 |
|----|------|
| **功能** | 工作区会话解析：获取/创建 session、发送 ask、处理工作区切换导致的 session 失效重试 |
| **Lxchat 对应** | `ImPollingReceiver.runOnce` + `bindConversation` 实现了会话获取/创建/ask，但没有"工作区切换失效重试"逻辑 |
| **需要修改的文件** | 无（Lxchat 无工作区概念，不需要失效重试） |
| **优先级** | — |

---

### B. src/channels/office/ （6 个文件）— ❌ 完全缺失

#### B1. protocol.mjs（43 行）

| 项 | 内容 |
|----|------|
| **功能** | AI Office 协议常量：`office-harness.v1`、Hook 路径（stream/heartbeat/job/accept/renew/progress/approval/result/fail）、URL 规范化 |
| **Lxchat 对应** | **无** |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/office/OfficeProtocol.kt` |
| **优先级** | **中** |

#### B2. config-store.mjs（107 行）

| 项 | 内容 |
|----|------|
| **功能** | AI Office 连接配置存储：baseUrl、deviceId、deviceToken、workspaces（别名→路径）、instructionPresets（别名→指令）、maxConcurrency、heartbeatSeconds |
| **Lxchat 对应** | **无** |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/office/OfficeConfig.kt` + `OfficeConfigStore.kt` |
| **优先级** | **中** |

#### B3. office-transport.mjs（170 行）

| 项 | 内容 |
|----|------|
| **功能** | AI Office HTTP/SSE 传输层：heartbeat POST、SSE stream 解析、job accept/renew/progress/approval/result/fail POST |
| **Lxchat 对应** | **无** |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/office/OfficeTransport.kt` |
| **优先级** | **中** |

#### B4. office-runtime.mjs（171 行）

| 项 | 内容 |
|----|------|
| **功能** | AI Office 运行时：心跳循环、SSE 事件流监听、任务派发到 JobExecutor、重连退避 |
| **Lxchat 对应** | **无** |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/office/OfficeRuntime.kt` |
| **优先级** | **中** |

#### B5. office-job-executor.mjs（390 行）

| 项 | 内容 |
|----|------|
| **功能** | Office Job 执行器：从 Office 队列领取任务、本地 Harness 执行、审批/问题转发回 Office、回报结果/失败 |
| **Lxchat 对应** | **无** |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/office/OfficeJobExecutor.kt` |
| **优先级** | **中** |

#### B6. office-controller.mjs（167 行）

| 项 | 内容 |
|----|------|
| **功能** | AI Office 控制器：配置连接、启动/停止 runtime、状态查询、连接测试 |
| **Lxchat 对应** | **无** |
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/office/OfficeController.kt` |
| **优先级** | **中** |

> **Office 模块整体说明**：dsh-im 的 Office Connector 让本机 Harness 成为公网 AI Office 的执行节点，从 Office 队列领取任务本地执行。Lxchat 若要实现，需新增 `im/office/` 包共 6 个文件，并接入 `TaskExecutionEngine` 作为本地执行器。

---

### C. src/channels/whatsapp/ （8 个文件）— ❌ 完全缺失

| 文件 | 功能 | Lxchat 对应 |
|------|------|-------------|
| `config-store.mjs`（165 行） | WhatsApp 账号配置存储（JID 派生 botId、多账号） | **无** |
| `harness-client.mjs`（11 行） | WhatsApp 专用 Harness 客户端（继承 shared） | 不适用 |
| `state-store.mjs`（3 行） | WhatsApp 状态存储（继承 shared） | 不适用 |
| `whatsapp-bridge.mjs`（15 行） | WhatsApp 文本桥接（继承 shared） | 不适用 |
| `whatsapp-controller.mjs`（415 行） | WhatsApp 控制器：QR 扫码绑定、多账号管理、重连 | **无** |
| `whatsapp-runtime.mjs`（459 行） | WhatsApp 运行时：Baileys 库集成、消息接收、图片下载 | **无** |
| `whatsapp-web-session.mjs`（212 行） | WhatsApp Web 会话：Baileys socket、auth state、QR 生成 | **无** |

| 项 | 内容 |
|----|------|
| **需要创建的文件** | `app/src/main/java/com/lxseek/chat/im/whatsapp/WhatsappChannel.kt`、`WhatsappBaileysApi.kt`（或用 Android 原生 WhatsApp Business API）、`WhatsappConfigStore.kt` |
| **需要修改的文件** | `ImPlatform` 枚举增加 `WHATSAPP("whatsapp", "WhatsApp", true)`；`ImChannelFactory` 增加 whatsapp 分支 |
| **优先级** | **中** |
| **备注** | dsh-im 用 Baileys（Node.js WhatsApp Web 逆向库）。Android 上需用 WhatsApp Business Cloud API（官方）或评估 Baileys 是否可在 Node.js mobile 环境运行 |

---

### D. plugin-src/host/ （插件宿主侧）

| 文件 | 功能 | Lxchat 对应 | 优先级 |
|------|------|-------------|:------:|
| `index.mjs` | 注册所有 10 个渠道 | `ImChannelFactory` 已覆盖 8 个 | — |
| `harness-command-executor.mjs` | 适配 Host Typert 命令网关 | 不适用（Lxchat 自有命令系统） | — |
| `harness-session-coordinator.mjs` | 协调 Agent Registry 的 stop/steer | `TaskExecutionEngine` 需增加 stop/steer API | **高** |
| `rpc-authority.mjs` | RPC 权限 loopback/trusted-host | 不适用（Android 本地） | — |
| `channels/shared/connection-supervisor.mjs` | 重连退避监督器 | 各渠道自己实现（如 DingtalkStreamConnection） | — |
| `channels/shared/production.mjs` | token 渠道生产代码组装 | `ImBridgeService` 已覆盖 | — |
| `channels/shared/rpc.mjs` | bot 管理 RPC（bind/reconnect/delete/setWorkspace/setPreset） | 需有等价 Android 设置 UI | **中** |
| `channels/shared/agent-preset-rpc.mjs` | Agent Preset RPC | 依赖 A3 | **中** |
| `channels/shared/workspace-rpc.mjs` | 工作区 RPC | 不适用 | — |

---

### E. plugin-src/client/ （插件客户端侧 React UI）

> Lxchat 是 Android 原生 UI，这些 React 组件不直接对应，但需有等价的 Compose 页面。

| 文件 | 功能 | Lxchat 对应 | 优先级 |
|------|------|-------------|:------:|
| `index.js` | IM 设置面板主入口 | 需 Android 设置页 | **中** |
| `agent-preset.js` | Agent Preset 编辑器 | 依赖 A3 | **中** |
| `credential-binding.js` | 凭据绑定面板（token/QR） | 微信已有 `WeixinBindingFlow`，其他渠道需补 | **中** |
| `workspace-directory-picker.js` | 工作区目录选择器 | 不适用 | — |
| `workspace-editor.js` | 工作区编辑器 | 不适用 | — |
| `channel-logos.js` | 各渠道 Logo | Android 用 drawable 资源 | 低 |
| `i18n.js` | 中英文国际化 | Android `strings.xml` | 低 |
| `lifecycle.js` | React hooks（轮询/动画帧调度） | Kotlin 协程等价 | — |
| `styles.js` | CSS 样式 | Android Compose 主题 | — |

---

### F. lib/ （核心库打包产物）

| 文件 | 功能 | Lxchat 对应 | 优先级 |
|------|------|-------------|:------:|
| `index.js`（7.6 MB） | Host 侧 bundle | 不适用（构建产物） | — |
| `client.js`（527 KB） | Client 侧 bundle | 不适用（构建产物） | — |

---

## 三、Lxchat 需要新建的文件清单（按优先级）

### 🔴 高优先级（核心交互能力缺失）

| # | 文件路径 | 对应 dsh-im 模块 | 功能 |
|---|---------|-----------------|------|
| 1 | `im/command/CommandRouter.kt` | text-harness-bridge | IM 命令路由器总入口 |
| 2 | `im/command/CompactCommand.kt` | compact-command | `/compact` 上下文压缩 |
| 3 | `im/command/ControlCommand.kt` | control-command | `/stop` `/steer` 控制 |
| 4 | `im/command/ModelCommand.kt` | model-command | `/model` `/models` 模型切换 |
| 5 | `im/StreamingReplySender.kt` | editable-message-stream | 流式回复 create→edit→finish |
| 6 | `im/ImagePromptExtractor.kt` | image-prompt | 入站图片下载+转 multimodal |
| 7 | `im/ImApprovalBridge.kt` | harness-approval | 工具审批转发到 IM |
| 8 | `im/ImQuestionBridge.kt` | harness-question | 补充提问转发到 IM |

**需修改的现有文件**：

| 文件 | 修改内容 |
|------|---------|
| `im/ImModels.kt` | `ImMessage` 增加 `images: List<ImImage>` 字段 |
| `im/MessageChannel.kt` | 增加 `editMessage(messageId, text)` 可选方法 |
| `im/ImPollingReceiver.kt` | `feedInboundBatch` 接入 CommandRouter、StreamingReplySender、ImagePromptExtractor |
| `im/ImRuntimeState` | 增加 `currentModel` 字段 |
| `tool/ToolApproval.kt` 或 `TaskExecutionEngine` | 审批钩子接入 ImApprovalBridge |
| `tool/AskUserToolProvider.kt` | IM 问答转发接入 ImQuestionBridge |
| `TaskExecutionEngine.kt` | 增加 `stopActiveTurn(convId)` `steerActiveTurn(convId, text)` |
| 各渠道 Channel | 实现 `editMessage`；实现图片提取 |

### 🟡 中优先级（功能增强）

| # | 文件路径 | 对应 dsh-im 模块 | 功能 |
|---|---------|-----------------|------|
| 9 | `im/AgentPreset.kt` + `AgentPresetStore.kt` | agent-preset | Agent Preset 数据类+存储 |
| 10 | `im/command/PresetCommand.kt` | preset-command | `/preset` `/presetlist` |
| 11 | `im/ConnectionTester.kt` | connection-test | 连接测试发消息 |
| 12 | `im/office/OfficeProtocol.kt` | office/protocol | Office 协议常量 |
| 13 | `im/office/OfficeConfig.kt` + `OfficeConfigStore.kt` | office/config-store | Office 配置 |
| 14 | `im/office/OfficeTransport.kt` | office/office-transport | Office HTTP/SSE |
| 15 | `im/office/OfficeRuntime.kt` | office/office-runtime | Office 运行时 |
| 16 | `im/office/OfficeJobExecutor.kt` | office/office-job-executor | Office 任务执行 |
| 17 | `im/office/OfficeController.kt` | office/office-controller | Office 控制器 |
| 18 | `im/whatsapp/WhatsappChannel.kt` 等 | whatsapp/* | WhatsApp 渠道 |

**需修改的现有文件**：

| 文件 | 修改内容 |
|------|---------|
| `im/ImModels.kt` | `ImPlatform` 增加 `WHATSAPP` |
| `im/ImChannelFactory.kt` | 增加 whatsapp 分支 |
| IM 设置 UI | 增加 Agent Preset 编辑、连接测试按钮、Office 配置页 |

### 🟢 低优先级（边际价值/部分不适用）

| # | 文件路径 | 对应 dsh-im 模块 | 功能 |
|---|---------|-----------------|------|
| 19 | `im/BotProfileStore.kt` | bot-workspace-store | bot profile 绑定（非工作区路径） |
| 20 | `im/command/SessionListCommand.kt` | workspace-command | `/sessionlist` 列出会话 |
| 21 | `im/command/SessionBindCommand.kt` | workspace-command | `/session` 切换会话 |
| 22 | `im/ExternalSessionBinding.kt` | harness-session-binding | 绑定外部 session ID |

---

## 四、Lxchat 已有且完整的对应实现（无需改动）

| dsh-im 模块 | Lxchat 对应 | 说明 |
|------------|------------|------|
| token-bot-controller.mjs | ImBridgeService + ImGatewayStore | 多 bot 管理已完整 |
| conversation-state-store.mjs | ImRuntimeState + ImGatewayStore | 状态持久化已完整（MAX_SEEN=2000 > dsh-im 的 1000） |
| token-config-store.mjs | ImGatewayStore.multiConfig | 配置存储已完整 |
| session-binding-lock.mjs | DataStore edit 原子操作 | 天然序列化 |
| plugin-src/host/channels/shared/connection-supervisor.mjs | 各渠道自实现重连 | 如 DingtalkStreamConnection 指数退避 |

---

## 五、架构差异说明

| 维度 | dsh-im | Lxchat |
|------|--------|--------|
| **运行环境** | Node.js 插件，运行在 DeepSeek Harness Host 进程内 | Android Kotlin App，自身即 AI 客户端 |
| **AI 引擎** | 通过 HTTP RPC 调用本机 Harness 进程 | 内置 `TaskExecutionEngine`，直接调用 LLM Provider |
| **UI** | React Web 设置面板（plugin-src/client） | Android Compose 原生 UI |
| **持久化** | JSON 文件（fs/promises，原子 rename） | DataStore Preferences（加密） |
| **渠道数** | 9 个 IM + AI Office = 10 | 8 个 IM + SMS 回退 = 9 |
| **会话模型** | IM 会话 ↔ Harness session（外部进程） | IM 会话 ↔ Lxchat conversation（内部 DB） |
| **工作区** | 每个 bot 绑定本地工作区目录（绝对路径） | 不适用（Android 无本地工作区） |
| **命令系统** | 11 个斜杠命令（compact/model/preset/workspace/session/stop/steer 等） | **无**（消息直接喂 agent） |
| **流式回复** | editable-message-stream（create→edit→finish） | **无**（等完整生成再发送） |
| **图片识别** | image-prompt（下载→base64→multimodal） | **无**（仅 text） |
| **审批转发** | harness-approval（工具审批→IM 回复） | 本地 UI 审批（不转发 IM） |
| **提问转发** | harness-question（补充提问→IM 回复） | 本地 UI 问答（不转发 IM） |
| **Office** | 完整 Connector（6 文件） | **无** |
| **WhatsApp** | Baileys 完整实现 | **无** |

---

## 六、建议的实施顺序

### 第一批（核心交互能力，高优先级）

1. **命令路由器** `CommandRouter` + 4 个核心命令（compact/control/model）
2. **流式回复** `StreamingReplySender` + `MessageChannel.editMessage`
3. **图片识别** `ImagePromptExtractor` + `ImMessage.images`
4. **审批转发** `ImApprovalBridge`
5. **提问转发** `ImQuestionBridge`

### 第二批（功能增强，中优先级）

6. **Agent Preset** 数据层 + `/preset` 命令
7. **连接测试** `ConnectionTester`
8. **AI Office Connector**（6 文件）
9. **WhatsApp 渠道**

### 第三批（边际价值，低优先级）

10. **Bot Profile 绑定**
11. **会话列表/切换命令**
12. **外部 session 绑定**

---

*报告结束。本报告仅做分析，未修改任何源代码文件。*