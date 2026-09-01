---
name: inkos
description: inkos 长篇小说续写引擎（非交互批量写下一章），依赖 Node.js 运行时（>= 22.5），使用用户已配置的默认模型服务创作。
when_to_use: 用户要求续写小说下一章、批量创作章节，或指定用 inkos 引擎写作时使用。
---

# inkos 网文续写

通过 `novel_inkos` 工具驱动 inkos 引擎非交互批量续写小说下一章。
传入的 `message` 作为创作指令（`--context`）交给引擎。

## 调用要点

- `message`：创作指令，描述题材/设定/大纲/期望章节。
- `timeout_ms`：可选，默认 120 秒，上限 600 秒；长章创作建议调大。
- 引擎未运行时自动拉起，并强制满足 node >= 22.5 版本约束。
- 模型注入：使用用户已配置的默认模型服务（baseUrl/apiKey/model），
  未配置模型会返回 `model_not_configured` 错误。

## 输出

- 返回 JSON：`ok` / `exit_code` / `output`（创作结果）/ `timed_out`。
- 超时并不代表失败——创作可能仍在后台进行，可稍后查询 `runtime_status`。

## 建议

- 续写前先用 `runtime_status` 确认引擎就绪，减少冷启动等待。
- 单次指令聚焦一个章节的走向，避免一次塞入过多约束。
- 与 webnovel-writer 的区别：inkos 偏逐章续写，webnovel-writer 偏全流程
  （init/plan/write/review）与一致性管理。
