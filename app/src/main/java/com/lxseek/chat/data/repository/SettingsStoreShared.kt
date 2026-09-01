package com.lxseek.chat.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun <T> sharedSettingsState(
    flow: Flow<T>,
    initial: T,
    scope: CoroutineScope,
    initialLoadSignals: MutableList<CompletableDeferred<Unit>>,
): StateFlow<T> {
    val loaded = CompletableDeferred<Unit>()
    initialLoadSignals += loaded
    val state = MutableStateFlow(initial)
    flow
        .onEach { value ->
            state.value = value
            loaded.complete(Unit)
        }
        .catch { error ->
            loaded.completeExceptionally(error)
            throw error
        }
        .launchIn(scope)
    return state.asStateFlow()
}
