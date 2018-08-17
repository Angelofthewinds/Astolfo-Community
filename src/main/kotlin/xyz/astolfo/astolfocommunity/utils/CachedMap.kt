package xyz.astolfo.astolfocommunity.utils

import kotlinx.coroutines.experimental.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

class CachedMap<K : Any, V : Any> private constructor(private val map: MutableMap<K, V>,
                                                      private val invalidateCallback: (K, V) -> Unit,
                                                      private val context: CoroutineContext = DefaultDispatcher,
                                                      private val time: Long,
                                                      private val timeUnit: TimeUnit) : MutableMap<K, V> by map {

    constructor(time: Long,
                timeUnit: TimeUnit,
                context: CoroutineContext = DefaultDispatcher,
                invalidateCallback: (K, V) -> Unit = { _, _ -> }) : this(ConcurrentHashMap<K, V>(), invalidateCallback, context, time, timeUnit)

    private val jobMap = ConcurrentHashMap<K, CleanUpJob>()

    fun keepAlive(key: K) = synchronized(key) {
        jobMap.computeIfAbsent(key) { CleanUpJob(key) }.keepAlive()
    }

    private fun invalidate(key: K): V? = synchronized(key) {
        if (!map.containsKey(key)) return@synchronized null
        val result = map.remove(key)!!
        val job = jobMap.remove(key)!!
        job.stop()
        invalidateCallback(key, result)
        result
    }

    override fun clear() {
        val iterator = map.iterator()
        iterator.forEachRemaining {
            invalidate(it.key)
        }
    }

    inline fun using(key: K, block: (V) -> Unit) = synchronized(key) {
        val value = get(key) ?: return@synchronized
        block(value)
        keepAlive(key)
    }

    override fun put(key: K, value: V): V? = synchronized(key) {
        val result = map.put(key, value)
        keepAlive(key)
        return@synchronized result
    }

    override fun get(key: K): V? = synchronized(key) {
        keepAlive(key)
        return@synchronized map[key]
    }

    override fun remove(key: K): V? = synchronized(key) {
        invalidate(key)
    }

    inner class CleanUpJob(private val key: K) {
        private lateinit var job: Job
        private var lastUsed: Long = 0L

        init {
            keepAlive(true)
            start()
        }

        fun keepAlive() = keepAlive(false)
        private fun keepAlive(firstRun: Boolean) = synchronized(key) {
            lastUsed = System.currentTimeMillis()
            if (!firstRun) stop()
            start()
        }

        private fun start() = synchronized(key) {
            job = launch(context) {
                delay(time, timeUnit)
                synchronized(key) {
                    if (System.currentTimeMillis() - lastUsed >= timeUnit.toMillis(time)) {
                        invalidate(key)
                    }
                }
            }
        }

        fun stop() = synchronized(key) {
            job.cancel()
        }
    }
}

