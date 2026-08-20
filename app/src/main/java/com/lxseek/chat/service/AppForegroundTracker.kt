package com.lxseek.chat.service

import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppForegroundTracker {
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    @Volatile
    var isInForeground: Boolean = false
        private set

    fun setInForeground(inForeground: Boolean) {
        if (isInForeground == inForeground) return
        isInForeground = inForeground
        _foreground.value = inForeground
        listeners.forEach { it(inForeground) }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isInForeground)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
