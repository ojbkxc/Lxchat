# plugin/ 边界规则

> 本文件约束 `com.lxseek.chat.plugin` 包的演进方向。改本包前先读一遍；
> 违反规则的改动会在 CI 快照测试（`PluginContractSnapshotTest`）或人工评审中被拦下。

## 契约优先

- 一切插件（原生工具集 / MCP / 市场插件）收敛为 [Plugin] 一个契约，
  由 [PluginHost] 统一管理生命周期与启用状态。新增能力 = 扩 [Plugin] 契约，
  **禁止**在 [PluginHost.toolProviders] 里写任何 provider 专属分支。
- 第三方生态对齐（DeepSeek Harness / Operit / cc-haha / MCP）只能发生在
  `plugin/adapters/`，不得让外部格式渗透进 [Plugin] 接口本身。

## 依赖方向

- 插件实现只允许依赖：`plugin/` 契约、`tool/ToolProvider`、`skill/Skill`
  与 `PluginContext` 暴露的最小运行时。
- 禁止 `import com.lxseek.chat.viewmodel.*`、`com.lxseek.chat.ui.*`、
  `com.lxseek.chat.data.*`（宿主装配在 `di/AppContainer`，插件只消费不感知）。
- 会员门禁只发生在 [PluginHost.toolProviders] 出口
  （`MembershipToolProvider` 包装），单个插件不得自行判断付费状态。

## 懒加载（启动性能）

- `register()` 只持有 manifest；`toolProviders(context)` 调用时才实例化重对象。
  新插件不得在 `onEnable` 里做磁盘 I/O / 大对象初始化。

## 契约变更纪律

- 改动 [Plugin] 接口公共签名（增删方法/参数）= 破坏性变更：必须同步迁移
  全部内置插件（`McpPlugin` / `NativeToolsPlugin` / `BuiltinSkillsPlugin` 等）
  并更新 `PluginContractSnapshotTest` 的快照，二者在同一提交内完成。
- 验证：本包改动依赖 GitHub Actions CI 编译（遵守项目铁律：禁止本地编译）。