package xyz.astolfo.astolfocommunity.lib

import kotlinx.coroutines.experimental.Job

fun Job.cancelQuietly(cause: Throwable? = null): Boolean {
    if (!isActive) return false
    return try {
        cancel(cause)
    } catch (e: IllegalStateException) {
        false
    }
}