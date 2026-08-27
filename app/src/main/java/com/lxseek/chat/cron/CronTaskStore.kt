package com.lxseek.chat.cron

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [CronTask] 列表的持久化存储。
 *
 * 沿用 [com.lxseek.chat.notification.NotificationReplyStore] 的模式：用一个独立的
 * `preferencesDataStore("cron_tasks")`，把整个任务列表序列化为 JSON 写在单个 string key 下。
 * 列表规模小（典型几十条），整体读写简单且原子。
 *
 * 所有写操作都通过 [edit] 在 DataStore 的单线程写锁内完成，因此无需外部同步。
 */
private val Context.cronTasksDataStore by preferencesDataStore(name = "cron_tasks")

class CronTaskStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_TASKS = stringPreferencesKey("tasks_json")

    /** 当前所有 Cron 任务（按创建时间升序）。 */
    val tasks: Flow<List<CronTask>> = context.cronTasksDataStore.data.map { pref ->
        val raw = pref[KEY_TASKS] ?: return@map emptyList()
        decodeTasks(raw)
    }

    /** 一次性读取当前任务列表快照。 */
    suspend fun currentTasks(): List<CronTask> = tasks.first()

    /** 添加一条新任务。调用方负责生成 [CronTask.id]。 */
    suspend fun addTask(task: CronTask) {
        context.cronTasksDataStore.edit { pref ->
            val current = decodeTasks(pref[KEY_TASKS] ?: "")
            pref[KEY_TASKS] = json.encodeToString(current + task)
        }
    }

    /** 按 id 删除一条任务。 */
    suspend fun removeTask(id: String) {
        context.cronTasksDataStore.edit { pref ->
            val current = decodeTasks(pref[KEY_TASKS] ?: "")
            pref[KEY_TASKS] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    /** 用 [task] 替换同 id 的任务（用于改名 / 改表达式 / 改 prompt / 启停）。 */
    suspend fun updateTask(task: CronTask) {
        context.cronTasksDataStore.edit { pref ->
            val current = decodeTasks(pref[KEY_TASKS] ?: "")
            pref[KEY_TASKS] = json.encodeToString(
                current.map { if (it.id == task.id) task else it }
            )
        }
    }

    /** 仅翻转某条任务的 enabled 开关。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        context.cronTasksDataStore.edit { pref ->
            val current = decodeTasks(pref[KEY_TASKS] ?: "")
            pref[KEY_TASKS] = json.encodeToString(
                current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            )
        }
    }

    /** 标记某条任务刚刚执行完一次（更新 [CronTask.lastRunAt]）。 */
    suspend fun markRun(id: String, at: Long = System.currentTimeMillis()) {
        context.cronTasksDataStore.edit { pref ->
            val current = decodeTasks(pref[KEY_TASKS] ?: "")
            pref[KEY_TASKS] = json.encodeToString(
                current.map { if (it.id == id) it.copy(lastRunAt = at) else it }
            )
        }
    }

    /** 解码 JSON，兼容空串 / 损坏数据（返回空列表而不是抛异常，避免 DataStore 卡死）。 */
    private fun decodeTasks(raw: String): List<CronTask> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<CronTask>>(raw) }
            .getOrElse {
                // 兼容旧版单对象格式（理论上不会出现，但保持与 NotificationReplyStore 一致的兜底风格）。
                runCatching { listOf(json.decodeFromString<CronTask>(raw)) }
                    .getOrElse { emptyList() }
            }
    }
}

/** 仅用于序列化兼容性占位（当前无旧格式需要迁移，保留以与 NotificationReplyStore 风格对齐）。 */
@Serializable
private data class CronTasksEnvelope(val tasks: List<CronTask> = emptyList())