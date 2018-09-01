package xyz.astolfo.astolfocommunity.lib

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ActorScope
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlin.coroutines.experimental.CoroutineContext

fun <E> smartActor(
        context: CoroutineContext = DefaultDispatcher,
        capacity: Int = 0,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        parent: Job? = null,
        onCompletion: CompletionHandler? = null,
        block: suspend ActorScope<E>.() -> Unit
): SmartActor<E> = object : SmartActor<E> {

    private val actorJob = Job(parent)
    private val actor = actor(context, capacity, start, actorJob, onCompletion) {
        block()
    }

    override suspend fun join() = actorJob.joinChildren()

    override val isClosedForSend: Boolean
        get() = actor.isClosedForSend
    override val isFull: Boolean
        get() = actor.isFull
    override val onSend: SelectClause2<E, SendChannel<E>>
        get() = actor.onSend

    override fun close(cause: Throwable?): Boolean = actor.close(cause)
    override fun offer(element: E): Boolean = actor.offer(element)
    override suspend fun send(element: E) = actor.send(element)
}

interface SmartActor<E> : SendChannel<E> {
    suspend fun closeAndJoin(cause: Throwable? = null) {
        close(cause)
        join()
    }

    suspend fun join()
}