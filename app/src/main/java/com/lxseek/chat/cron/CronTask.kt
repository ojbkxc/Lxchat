package com.lxseek.chat.cron

import kotlinx.serialization.Serializable

/**
 * 一条 Cron 定时任务。
 *
 * 用 Cron 表达式（5 字段：minute hour day month weekday）定时让 AI 执行 [prompt]。
 * 例如「每天早上 9 点发天气摘要」对应 `cronExpression = "0 9 * * *"`、
 * `prompt = "帮我查一下今天的天气并生成一段简短的早间摘要"`。
 *
 * 持久化由 [CronTaskStore] 用 DataStore 完成；调度由 [CronScheduler] 用 WorkManager 完成；
 * 实际执行由 [CronWorker] 委托给 [com.lxseek.chat.automation.TaskExecutionEngine.runOnce]。
 *
 * @property id            稳定唯一标识（UUID），同时作为 WorkManager unique work name 的一部分。
 * @property name          人类可读的任务名称，仅用于 UI 展示。
 * @property cronExpression 5 字段 Cron 表达式，如 "0 9 * * *" / "*/15 * * * *"。
 * @property prompt        注入给 AI 的用户提示词，等价于在聊天框输入这段文本。
 * @property modelId       指定模型（"provider:model" 格式）；null/空表示跟随默认模型。
 * @property enabled       总开关。关闭后 [CronScheduler] 会取消对应的 WorkManager 工作。
 * @property createdAt     创建时间戳（ms）。
 * @property lastRunAt     最近一次成功执行的时间戳（ms），0 表示从未执行过。
 */
@Serializable
data class CronTask(
    val id: String,
    val name: String,
    val cronExpression: String,
    val prompt: String,
    val modelId: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0,
)