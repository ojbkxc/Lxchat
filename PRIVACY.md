# Privacy Policy

**Last updated: August 27, 2026**

LxChat is an on-device AI chat client that communicates directly with the API providers you configure. This policy explains what data is handled, where it lives, and what third parties may see it.

## Data Collection

**LxChat does not collect, store, or transmit any personal data to its developer. We operate no backend servers.**

- **Chat content** — every conversation, message and attachment is stored **locally on your device** in a Room database. It is never sent to LxChat; it leaves the device only when forwarded to the AI provider you selected.
- **API keys & model credentials** — stored **locally on your device** via DataStore, used solely to authenticate requests to the AI providers you configure.
- **App settings** — preferences, enabled plugins, automation rules, trigger rules, IM gateways, etc. are persisted locally via DataStore.
- **Membership state** — tier, expiry, source and active flag are persisted locally via DataStore (see *Membership & Payments*).
- **No personal identifiable information (PII)** — LxChat does not collect device identifiers, advertising IDs, phone numbers, email addresses, account names or location. There is no analytics SDK and no crash-reporting telemetry wired to a remote collector.

## Data Storage Location

All user data lives **on your device**:

| Data | Storage |
| --- | --- |
| Conversations, messages, attachments metadata | Room (on-device SQLite) |
| Settings, API keys, membership state, redeemed nonces | DataStore (on-device preferences) |
| Generated files (images, exports) | App-private storage / scoped MediaStore |

Nothing is uploaded to a LxChat-operated server. Clearing app data or uninstalling removes all local data.

## Third-Party Services

When you use LxChat, your messages and attached files are sent from your device to the services you configure:

- **LLM API providers** — e.g. [Google Gemini](https://ai.google.dev/gemini-api/terms), [OpenAI](https://openai.com/policies/privacy-policy), [Anthropic](https://www.anthropic.com/legal/privacy), or self-hosted endpoints via Ollama / custom base URLs. Requests go directly from your device to the provider; LxChat does not intermediate or log them. Review each provider's privacy policy.
- **Payment gateway (Yipay / 易支付)** — used only when you purchase a membership in-app. The purchase is placed with the Yipay gateway over HTTPS; the gateway returns a signed callback that LxChat verifies locally (see *Membership & Payments*). The gateway operator sees the order details you submit; LxChat itself receives only the signed callback parameters.

## Third-Party Plugins

LxChat supports a plugin system (integrated tools, MCP servers, and external/marketplace plugins such as Claude plugins and Operit ToolPkg). Plugins are untrusted code paths:

- A plugin's tools may be invoked during chat generation and can therefore **read the current message context, tool inputs and produce tool outputs** that flow into the conversation.
- A plugin may declare `requiresMembership` and be gated by the capability layer, but **LxChat does not sandbox plugin network or filesystem access beyond the Android permission model**.
- Plugin settings you enter are persisted locally via DataStore and may be read by the plugin at runtime.
- **You are responsible for evaluating the privacy and security practices of any third-party plugin you install.** Only install plugins from sources you trust. Remove a plugin from the plugins list if you no longer want it to access your data.

## Membership & Payments

LxChat offers Premium / Pro tiers. **No Google Play Billing is used**; the app does not declare the `com.android.vending.BILLING` permission. Membership is established through two offline-friendly channels:

- **Redemption codes (兑换码)** — validated **entirely offline** on your device. Each code carries an HMAC-SHA256 signature over a Base64 payload (tier, duration, issue/expiry timestamps, nonce). Verification uses a secret key embedded in the app (moved to the native layer via NDK). Replay protection is enforced by persisting redeemed nonces locally in DataStore. No network call is made when you redeem a code.
- **Yipay (易支付) callback** — when you pay online, the Yipay gateway returns a callback signed with MD5. LxChat verifies the signature locally (constant-time compare) before activating the membership. The callback is delivered over HTTPS; the merchant key is stored on-device.

Membership tier, expiry timestamp, source (`redemption_code` or `yipay`) and active flag are stored locally in DataStore. An expired membership is reported as inactive even if the persisted flag is still true. No payment card numbers are ever seen or stored by LxChat — card data is handled entirely by the Yipay gateway / its upstream payment channels.

## Permissions

- **Internet** — communicate with AI provider APIs and the Yipay gateway.
- **Foreground service / data sync / notifications / vibrate** — keep long-running generation, automation and IM polling alive; notify on completion.
- **Camera / Record audio** — capture photos and voice input when you explicitly choose to.
- **System alert window** — the optional desktop-pet floating bubble; inert until you grant it.
- **Storage (pre-Q only)** — saving images to the gallery on Android 9 and below; Q+ uses scoped MediaStore (no permission).
- **Query all packages** — binding to system TTS engines (e.g. Xiaomi Mi Brain) on Android 11+.
- **Write secure settings** — retained for backward-compatible ADB Shell root-mode flows; not dangerous-permission gated.
- **Wi-Fi state / multicast** — LAN device discovery (mDNS / NSD).
- **Receive / read SMS** — the optional SMS command system (`smsf#` commands); user-granted at runtime, off by default.
- **Network state / exact alarm / boot completed** — automation triggers, scheduled tasks and re-arming after reboot.

No permission is requested until the related feature is activated by you.

## Data Retention

All chat history and settings are stored locally in on-device databases. You can delete individual conversations at any time within the app. Clearing the app's data or uninstalling removes all local data. Redemption nonces are retained locally to prevent code replay; revoking membership clears them.

## Children's Privacy

LxChat is not directed to children under the age of 13.

## Changes

This policy may be updated from time to time. Changes will be posted on this page.

## Contact

If you have questions about this policy, open an issue at [github.com/ojbkxc/lxchat](https://github.com/ojbkxc/lxchat).

---

# 隐私政策

**最后更新：2026年8月27日**

LxChat 是一款运行在设备上的 AI 聊天客户端，直接与你配置的 API 提供商通信。本政策说明处理了哪些数据、数据存放在哪里，以及哪些第三方可能看到这些数据。

## 数据收集

**LxChat 不会收集、存储或向开发者传输任何个人数据。我们不运营任何后端服务器。**

- **聊天内容**——所有对话、消息和附件均**存储在你的设备本地** Room 数据库中，绝不会发送给 LxChat；仅在你选择转发给所选 AI 提供商时才会离开设备。
- **API 密钥与模型凭证**——通过 DataStore **存储在设备本地**，仅用于向你配置的 AI 提供商认证请求。
- **应用设置**——偏好、已启用插件、自动化规则、触发器规则、IM 网关等均通过 DataStore 本地持久化。
- **会员状态**——等级、到期时间、来源与激活标记均通过 DataStore 本地持久化（见*会员与支付*）。
- **不收集个人身份信息（PII）**——LxChat 不收集设备标识符、广告 ID、电话号码、邮箱、账号或位置信息。未集成任何分析 SDK，也未接入任何远程崩溃上报。

## 数据存储位置

所有用户数据均**位于你的设备上**：

| 数据 | 存储 |
| --- | --- |
| 对话、消息、附件元数据 | Room（设备端 SQLite） |
| 设置、API 密钥、会员状态、已兑换 nonce | DataStore（设备端偏好） |
| 生成文件（图片、导出） | 应用私有存储 / 受限 MediaStore |

不会上传至 LxChat 运营的服务器。清除应用数据或卸载将删除所有本地数据。

## 第三方服务

使用 LxChat 时，你的消息和附件会从设备发送到你配置的服务：

- **LLM API 提供商**——如 [Google Gemini](https://ai.google.dev/gemini-api/terms)、[OpenAI](https://openai.com/policies/privacy-policy)、[Anthropic](https://www.anthropic.com/legal/privacy)，或通过 Ollama / 自定义 base URL 的自托管端点。请求从设备直接发往提供商，LxChat 不中转也不记录。请查阅各提供商的隐私政策。
- **支付网关（Yipay / 易支付）**——仅当你在应用内购买会员时使用。订单通过 HTTPS 提交至易支付网关；网关返回签名回调，LxChat 在本地验证（见*会员与支付*）。网关运营方可见你提交的订单信息；LxChat 仅接收签名回调参数。

## 第三方插件

LxChat 支持插件体系（内置工具、MCP 服务、外部/市场插件，如 Claude 插件与 Operit ToolPkg）。插件属于不可信代码路径：

- 插件暴露的工具可在聊天生成过程中被调用，因此**可读取当前消息上下文、工具输入，并产生流入对话的工具输出**。
- 插件可声明 `requiresMembership` 并由能力门禁层拦截，但**除 Android 权限模型外，LxChat 不对插件的网络或文件系统访问做额外沙箱隔离**。
- 你填写的插件设置通过 DataStore 本地持久化，运行时可被插件读取。
- **你有责任自行评估所安装第三方插件的隐私与安全实践。** 仅安装来自可信来源的插件；不再希望其访问数据时，可从插件列表移除。

## 会员与支付

LxChat 提供 Premium / Pro 等级。**不使用 Google Play Billing**；应用未声明 `com.android.vending.BILLING` 权限。会员通过两条对离线友好的渠道建立：

- **兑换码**——**完全在设备本地离线校验**。每个兑换码包含对 Base64 载荷（等级、时长、签发/过期时间戳、nonce）的 HMAC-SHA256 签名。校验使用内置于应用的密钥（已移至 NDK 原生层）。防重放通过在 DataStore 本地持久化已兑换 nonce 实现。兑换时不发起任何网络请求。
- **易支付回调**——在线支付时，易支付网关返回 MD5 签名回调。LxChat 在本地验证签名（常数时间比较）后再激活会员。回调通过 HTTPS 传输；商户密钥存储于设备本地。

会员等级、到期时间戳、来源（`redemption_code` 或 `yipay`）与激活标记本地存储于 DataStore。即便持久化标记仍为 true，过期会员也会被报告为未激活。LxChat 永远不会看到或存储任何银行卡号——卡数据完全由易支付网关及其上游支付渠道处理。

## 权限

- **网络**——与 AI 提供商 API 及易支付网关通信。
- **前台服务 / 数据同步 / 通知 / 震动**——保持长时生成、自动化与 IM 轮询存活；完成时通知。
- **相机 / 录音**——在你主动选择时拍摄照片、录入语音。
- **悬浮窗**——可选的桌面宠物浮泡；授权前保持 inert。
- **存储（仅 pre-Q）**——在 Android 9 及以下保存图片到相册；Q+ 使用受限 MediaStore（无需权限）。
- **查询所有包**——在 Android 11+ 绑定系统 TTS 引擎（如小米 Mi Brain）。
- **写入安全设置**——为 ADB Shell root 模式流程保留向后兼容；非危险权限门控。
- **Wi-Fi 状态 / 多播**——局域网设备发现（mDNS / NSD）。
- **接收 / 读取短信**——可选的短信命令系统（`smsf#` 命令）；运行时由用户授权，默认关闭。
- **网络状态 / 精确闹钟 / 开机完成**——自动化触发器、定时任务与开机后重新武装。

所有权限均在你激活相关功能时才请求。

## 数据保留

所有聊天记录与设置存储于设备本地数据库。你可随时在应用内删除单条对话。清除应用数据或卸载将删除所有本地数据。已兑换 nonce 本地保留以防兑换码重放；撤销会员将清除它们。

## 儿童隐私

LxChat 不面向 13 岁以下儿童。

## 变更

本政策可能不时更新，更新内容将发布于此页面。

## 联系

如有问题，请在 [github.com/ojbkxc/lxchat](https://github.com/ojbkxc/lxchat) 提交 issue。
