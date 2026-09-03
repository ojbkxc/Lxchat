package com.lxseek.chat.baby

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次婴儿监护事件的可视化条目（多类事件流的一行）。
 *
 * [typeName] 用于 UI 侧解析文案：当 [kind] 为 [Kind.EVENT] 时即 [EventType.name]；
 * 哭声主状态机的事件用固定字符串 `CRY_ALERT` / `CRY_ENDED`。
 */
data class BabyEventEntry(
    val id: Long,
    val typeName: String,
    val score: Float,
    val timeMs: Long,
    val kind: Kind,
) {
    enum class Kind { CRY_ALERT, CRY_ENDED, EVENT }

    companion object {
        const val TYPE_CRY_ALERT = "CRY_ALERT"
        const val TYPE_CRY_ENDED = "CRY_ENDED"
    }
}

/**
 * 婴儿监护「多类事件流」的内存历史（进程内单例）。
 *
 * 由 [BabyMonitorService] 在每次事件产出时 [append]；设置页通过 [events] 订阅并渲染
 * 实时时间轴。仅内存、不持久化，重启即清空。
 */
object BabyEventHistory {
    private const val MAX_ENTRIES = 200

    private val _events = MutableStateFlow<List<BabyEventEntry>>(emptyList())
    val events: StateFlow<List<BabyEventEntry>> = _events.asStateFlow()

    @Synchronized
    fun append(entry: BabyEventEntry) {
        val next = listOf(entry) + _events.value
        _events.value = if (next.size > MAX_ENTRIES) next.take(MAX_ENTRIES) else next
    }

    fun clear() {
        _events.value = emptyList()
    }
}