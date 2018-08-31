package xyz.astolfo.astolfocommunity.lib.jda

import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import net.dv8tion.jda.core.requests.RestAction

suspend fun <T : Any?> RestAction<T>.await() = suspendCancellableCoroutine<T> { cont ->
    val future = submit()
    future.whenComplete { result, error ->
        if (error != null) cont.resumeWithException(error)
        else cont.resume(result)
    }
    cont.invokeOnCancellation { future.cancel(true) }
}