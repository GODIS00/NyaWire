package com.gdhapp.client.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Cancellation closes the socket even while the response body is still being read. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resumeWith(runCatching { response.use(read) })
        }
    })
}
