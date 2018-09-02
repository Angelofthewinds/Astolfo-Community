package xyz.astolfo.astolfocommunity.lib

import kotlinx.coroutines.experimental.DefaultDispatcher
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import xyz.astolfo.astolfocommunity.lib.jda.contentEquals
import xyz.astolfo.astolfocommunity.lib.jda.message
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Allows you to change the content as much as you want but the message will only update at a specified rate
 */
fun CachedMessage.asConflated(
        context: CoroutineContext = DefaultDispatcher,
        time: Long,
        unit: TimeUnit = TimeUnit.SECONDS
): ConflatedMessage = ConflatedMessageImpl(this, context, time, unit)

interface ConflatedMessage {
    val cachedMessage: CachedMessage

    var content: String
    var contentEmbed: MessageEmbed
    var contentMessage: Message
}

internal class ConflatedMessageImpl(override val cachedMessage: CachedMessage,
                                    private val context: CoroutineContext,
                                    private val time: Long,
                                    private val unit: TimeUnit) : ConflatedMessage {

    companion object {
        private val logger = createLogger()
    }

    override var content: String
        get() = contentMessage.contentRaw
        set(value) {
            contentMessage = message(value)
        }

    override var contentEmbed: MessageEmbed
        get() = contentMessage.embeds.first()
        set(value) {
            contentMessage = message(value)
        }

    private val contentSync = Any()
    private var job: Job? = null
    private var internalContent: Message? = null

    override var contentMessage: Message
        get() = internalContent ?: cachedMessage.contentMessage
        set(value) = synchronized(contentSync) {
            internalContent = value
            if (job != null) return@synchronized
            job = launch(context) {
                logger.debug("Started conflated message loop")
                var firstLoop = true
                while (isActive) {
                    synchronized(contentSync) {
                        val internalContent = this@ConflatedMessageImpl.internalContent
                        if (internalContent != null && !cachedMessage.contentMessage.contentEquals(internalContent)) {
                            cachedMessage.contentMessage = internalContent
                            this@ConflatedMessageImpl.internalContent = null
                        } else if (!firstLoop) {
                            logger.debug("Stopped conflated message loop")
                            job = null
                            // stop the coroutine since nothing is changing
                            return@launch
                        }
                    }
                    firstLoop = false
                    delay(time, unit)
                }
            }
        }

}