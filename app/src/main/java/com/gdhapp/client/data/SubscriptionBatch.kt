package com.gdhapp.client.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Independent subscriptions may download together; progress callbacks stay serialized. */
internal object SubscriptionBatch {
    suspend fun refresh(ids: Collection<String>, operation: suspend (String) -> Unit,
        completed: (String, Boolean) -> Unit = { _, _ -> }): Int = coroutineScope {
        val slots = Semaphore(3)
        val progress = Mutex()
        ids.map { id -> async {
            slots.withPermit {
                val success = try { operation(id); true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                progress.withLock { completed(id, success) }
                success
            }
        } }.awaitAll().count { !it }
    }
}
