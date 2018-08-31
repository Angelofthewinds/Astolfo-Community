package xyz.astolfo.astolfocommunity.lib.jda.builders

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.DefaultDispatcher
import kotlinx.coroutines.experimental.runBlocking
import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.hooks.EventListener
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.full.isSuperclassOf

typealias EventBlock<T> = suspend EventScope<T>.() -> Unit

class EventScope<T : Event>(val event: T,
                            val timeIssued: Long,
                            val scope: CoroutineScope) : CoroutineScope by scope

inline fun <reified T : Event> eventListenerBuilder(context: CoroutineContext = DefaultDispatcher,
                                                    crossinline block: EventBlock<T>) = listenerBuilder(context) { on<T> { block(this) } }

fun listenerBuilder(context: CoroutineContext = DefaultDispatcher,
                    block: ListenerBuilder.() -> Unit) = ListenerBuilder(context).also(block).build()

class ListenerBuilder(private val context: CoroutineContext) {
    private val eventHandlers = mutableListOf<Entry<*>>()

    inline fun <reified T : Event> on(noinline block: EventBlock<T>) = on(T::class, block)

    fun <T : Event> on(clazz: KClass<out T>, block: EventBlock<T>) = also {
        eventHandlers += Entry(clazz, block)
    }

    inner class Entry<T : Event>(
            val clazz: KClass<out T>,
            val block: EventBlock<T>
    ) {
        operator fun invoke(event: Event, timeIssued: Long) {
            @Suppress("UNCHECKED_CAST")
            val eventObj = event as T
            runBlocking(context) {
                block(EventScope(eventObj, timeIssued, this))
            }
        }
    }

    fun build() = EventListener { event ->
        val timeIssued = System.nanoTime()
        val eventClass = event::class
        eventHandlers.forEach { entry ->
            if (entry.clazz.isSuperclassOf(eventClass)) entry(event, timeIssued)
        }
    }
}