package com.gdhapp.client.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionBatchTest {
    @Test fun `downloads overlap but never exceed three and errors do not block siblings`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = mutableSetOf<String>()
        val job = async {
            SubscriptionBatch.refresh((1..8).map(Int::toString), { id ->
                val n = active.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }
                if (n == 3) started.complete(Unit)
                try { release.await(); if (id == "2") error("unavailable") }
                finally { active.decrementAndGet() }
            }) { id, _ -> completed.add(id) }
        }
        withTimeout(2000) { started.await() }
        assertEquals(3, active.get())
        release.complete(Unit)
        assertEquals(1, job.await())
        assertEquals(3, peak.get())
        assertEquals((1..8).map(Int::toString).toSet(), completed)
        assertEquals(0, active.get())
    }

    @Test fun `cancellation stops active downloads and does not start queued ones`() = runBlocking {
        val calls = AtomicInteger()
        val stopped = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            SubscriptionBatch.refresh((1..20).map(Int::toString), {
                if (calls.incrementAndGet() == 3) started.complete(Unit)
                try { awaitCancellation() } finally { stopped.incrementAndGet() }
            })
        }
        withTimeout(2000) { started.await() }
        job.cancelAndJoin()
        assertEquals(3, calls.get())
        assertEquals(3, stopped.get())
    }
}
