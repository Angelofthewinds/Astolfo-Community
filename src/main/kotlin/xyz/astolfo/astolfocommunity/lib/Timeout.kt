package xyz.astolfo.astolfocommunity.lib

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

class Timeout(val context: CoroutineContext,
              val time: Long,
              val unit: TimeUnit,
              val callback: suspend () -> Unit) {

    private var job: Job? = null

    val isActive
        get() = job?.isActive ?: false

    fun start() {
        job = launch(context) {
            delay(time, unit)
            callback()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun reset() {
        stop()
        start()
    }

}