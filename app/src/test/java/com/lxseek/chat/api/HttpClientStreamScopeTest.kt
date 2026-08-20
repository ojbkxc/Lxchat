package com.lxseek.chat.api

import com.lxseek.chat.viewmodel.StreamScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HttpClientStreamScopeTest {

    @Test
    fun parallelCoroutines_keepIndependentStreamScopesAcrossSuspension() = runTest {
        val scopeA = StreamScope()
        val scopeB = StreamScope()
        val aSuspended = CompletableDeferred<Unit>()
        val bObserved = CompletableDeferred<Unit>()
        var observedA: StreamScope? = null
        var observedB: StreamScope? = null

        val jobA = launch {
            HttpClient.withStreamScope(scopeA) {
                aSuspended.complete(Unit)
                bObserved.await()
                observedA = HttpClient.boundStreamScope()
            }
        }
        aSuspended.await()
        val jobB = launch {
            HttpClient.withStreamScope(scopeB) {
                observedB = HttpClient.boundStreamScope()
                bObserved.complete(Unit)
            }
        }

        jobA.join()
        jobB.join()

        assertSame(scopeA, observedA)
        assertSame(scopeB, observedB)
        assertNull(HttpClient.boundStreamScope())
    }
}
