package xyz.astolfo.astolfocommunity.lib.messagecache

import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.requests.RestAction
import net.dv8tion.jda.core.requests.restaction.MessageAction
import xyz.astolfo.astolfocommunity.lib.createLogger
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object MessageCache {

    private val logger = createLogger()

    private val cachedMessages = ConcurrentHashMap<Long, MessageReference>()
    private val referenceQueue = ReferenceQueue<CachedMessage>()

    val cachedMessageCount: Int
        get() {
            cleanUp()
            return cachedMessages.size
        }

    private fun cleanUp() {
        var toCleanUp = referenceQueue.poll()
        while (toCleanUp != null) {
            val id = (toCleanUp as MessageReference).id
            logger.debug("Removed $id from memory")
            cachedMessages.remove(id)
            toCleanUp = referenceQueue.poll()
        }
    }

    operator fun get(message: Message): CachedMessage {
        val id = message.idLong
        return MessageCache[id] ?: CachedMessageImpl(message.jda).apply {
            logger.debug("Adding $id to memory since we know it exists")
            cachedMessages[id] = MessageReference(id, this, referenceQueue)
            update(CachedMessageState.SENT, message)
        }
    }

    operator fun get(id: Long): CachedMessage? {
        cleanUp()
        if (id <= 0) throw IllegalArgumentException("Id must be positive!")
        return cachedMessages[id]?.get()
    }

    fun sendCached(restAction: RestAction<Message>): CachedMessage {
        cleanUp()
        val cachedMessage = CachedMessageImpl(restAction.jda)
        restAction.queue({
            val id = it.idLong
            logger.debug("Adding $id to memory since it successfully sent")
            cachedMessages[id] = MessageReference(id, cachedMessage, referenceQueue)
            cachedMessage.update(CachedMessageState.SENT, it)
        }) {
            cachedMessage.update(CachedMessageState.FAILED, null)
        }
        return cachedMessage
    }

}

class MessageReference internal constructor(
        val id: Long,
        cachedMessage: CachedMessage,
        referenceQueue: ReferenceQueue<CachedMessage>
) : WeakReference<CachedMessage>(cachedMessage, referenceQueue)

fun MessageAction.sendCached() = MessageCache.sendCached(this)