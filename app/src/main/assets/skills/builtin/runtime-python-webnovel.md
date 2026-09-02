---
name: webnovel-writer
description: 长篇网文一致性创作引擎（init/plan/write/review/query），基于持久化状态保证数百章的设定、伏笔与时间线一致。依赖 runtime-python（自动安装）。
when_to_use: 用户要创作长篇小说、规划卷纲章节、写章、审查前后一致性或查询创作状态时使用。
---

# webnovel-writer 长篇网文创作

通过 `webnovel` 工具驱动 webnovel-writer 引擎完成长篇创作。引擎在设备沙箱内运行，
使用用户已配置的默认模型服务。若当前模型缺少 API Key，工具会返回 model_not_configured 错误并提示可操作选项：可用 list_enabled_models 查看已配置的模型，再用 config_set 切换到 configured=true 的模型（内置 lxchat 网关如 lxchat:glm-4.7-flash 无需用户配置 API Key，开箱即用）。

## 工作流

1. **init** — 初始化作品：传入题材、世界观、主角设定等，建立持久化状态库。
2. **plan** — 规划卷纲：生成卷/章结构、每章摘要与伏笔埋设点。
3. **write** — 写章：按卷号/章号写作，引擎自动携带相关设定与伏笔上下文，保证不崩设定。
4. **review** — 一致性审查：检查已写内容与设定库的冲突（人物、时间线、道具）。
5. **query** — 查询状态：读取当前进度、设定条目、伏笔清单。

## 调用要点

- `action` 参数取 `init | plan | write | review | query` 之一。
- `params` 传 JSON 字符串，内容随 action 不同（设定对象 / 卷号章号 / 审查范围）。
- 引擎未安装时工具会自动拉起并自动安装依赖的 runtime-python，首次可能较慢。
- init/plan/write 需要 LLM 模型 Key；query/review 部分场景可离线。内置 lxchat 网关（如 lxchat:glm-4.7-flash）无需用户配置 API Key，开箱即用。

## 建议

- 长篇创作坚持「先 init 再 plan，逐章 write，定期 review」的节奏。
- write 前用 query 确认上一章结尾状态，避免情节衔接断裂。
- review 发现冲突后，优先在后续章节自然修正，而非重写已发布章节。
