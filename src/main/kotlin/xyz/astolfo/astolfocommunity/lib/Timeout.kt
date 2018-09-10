package xyz.astolfo.astolfocommunity.lib

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

class Timeout(val context: CoroutineContext,
              val time: Long,
              val unit: TimeUnit,
              val parentJob: Job? = null,
              val callback: suspend () -> Unit) {

    private var job: Job? = null

    val isActive
        get() = job?.isActive ?: false

    @Synchronized
    fun start() {
        if(job != null) return
        job = launch(context, parent = parentJob) {
            delay(time, unit)
            callback()
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @Synchronized
    fun reset() {
        stop()
        start()
    }

}