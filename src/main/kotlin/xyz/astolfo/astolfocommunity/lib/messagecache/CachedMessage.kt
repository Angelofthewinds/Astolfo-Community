package xyz.astolfo.astolfocommunity.lib.messagecache

import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.requests.RequestFuture
import xyz.astolfo.astolfocommunity.messages.message
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates.observable

interface CachedMessage {
    val jda: JDA

    val isDeleted: Boolean

    val guildId: Long
    val channelId: Long
    val idLong: Long

    val guild: Guild
    val channel: TextChannel

    var content: String
    var contentEmbed: MessageEmbed
    var contentMessage: Message
    val reactions: ReactionStore

    suspend fun editMessage(message: String, time: Long, unit: TimeUnit = TimeUnit.SECONDS) =
            editMessage(message(message), time, unit)

    suspend fun editMessage(message: MessageEmbed, time: Long, unit: TimeUnit = TimeUnit.SECONDS) =
            editMessage(message { setEmbed(message) }, time, unit)

    suspend fun editMessage(message: Message, time: Long, unit: TimeUnit = TimeUnit.SECONDS)

    fun delete()

    suspend fun await()
}

internal class CachedMessageImpl(override val jda: JDA) : CachedMessage {

    private val sendAwaitListeners = mutableListOf<() -> Unit>()
    private var messageState by observable(CachedMessageState.SENDING) { _, _, value ->
        if (value != CachedMessageState.SENDING) synchronized(sendAwaitListeners) {
            sendAwaitListeners.forEach { it() }
            sendAwaitListeners.clear()
        }
    }
    private val queuedTasks = mutableListOf<() -> Unit>()

    override val reactions = ReactionStoreImpl(this)

    override val isDeleted: Boolean
        get() = messageState == CachedMessageState.DELETED

    override var guildId = 0L
        private set
    override var channelId = 0L
        private set
    override var idLong = 0L
        private set

    override val guild: Guild
        get() = jda.getGuildById(guildId)
    override val channel: TextChannel
        get() = guild.getTextChannelById(channelId)

    private val contentSynchronized = Any()
    private var contentRequestFuture: RequestFuture<Message>? = null
    private var approvedContent = message("Message is loading...")
    private var contentInternal: Message? = null

    override var content: String
        get() = contentMessage.contentRaw
        set(value) {
            contentMessage = message(value)
        }

    override var contentEmbed: MessageEmbed
        get() = contentMessage.embeds.first()
        set(value) {
            contentMessage = message { setEmbed(value) }
        }

    override var contentMessage: Message
        get() = (contentInternal ?: approvedContent)
        set(value) {
            contentInternal = value
            runTask {
                synchronized(contentSynchronized) {
                    contentRequestFuture?.cancel(true)
                    contentRequestFuture = channel.editMessageById(idLong, value).submit()
                    contentRequestFuture!!.whenComplete { result, _ ->
                        contentInternal = null
                        if (result != null) approvedContent = result
                    }
                }
            }
        }

    override suspend fun editMessage(message: Message, time: Long, unit: TimeUnit) {
        launch(Unconfined) {
            delay(time, unit)
            runTask { contentMessage = message }
        }
    }

    fun update(messageState: CachedMessageState, message: Message?) {
        if (this.messageState != CachedMessageState.SENDING) throw IllegalStateException("Message has already been initialized!")
        if (messageState == CachedMessageState.SENT) {
            guildId = message!!.guild.idLong
            channelId = message.channel.idLong
            idLong = message.idLong
            update(message)
        }
        this.messageState = messageState
        sendQueuedTasks()
    }

    fun update(message: Message) {
        approvedContent = message
        reactions.update(message.reactions)
    }

    override fun delete() = runTask {
        messageState = CachedMessageState.DELETED
        reactions.dispose()
        contentRequestFuture?.cancel(true)
        channel.deleteMessageById(idLong).queue()
    }

    override suspend fun await() = synchronized(sendAwaitListeners) {
        if (messageState != CachedMessageState.SENDING) {
            if (sendAwaitListeners.isNotEmpty()) sendAwaitListeners.forEach { it() }
            sendAwaitListeners.clear()
            return@synchronized
        }
        suspendCancellableCoroutine<Unit> { cont ->
            val listener = { cont.resume(Unit) }
            sendAwaitListeners.add(listener)
            cont.invokeOnCancellation {
                synchronized(sendAwaitListeners) { sendAwaitListeners.remove(listener) }
            }
        }
    }

    internal fun runTask(task: () -> Unit) {
        when (messageState) {
            CachedMessageState.SENDING -> synchronized(queuedTasks) { queuedTasks.add(task) }
            CachedMessageState.SENT -> {
                sendQueuedTasks()
                task()
            }
            CachedMessageState.FAILED -> {
            }
            CachedMessageState.DELETED -> {
            }
        }
    }

    private fun sendQueuedTasks() {
        if (messageState != CachedMessageState.SENT) return
        if (queuedTasks.isNotEmpty()) synchronized(queuedTasks) {
            queuedTasks.forEach {
                it()
                if (messageState != CachedMessageState.SENT) return@synchronized
            }
            queuedTasks.clear()
        }
    }

}

enum class CachedMessageState {
    SENDING,
    SENT,
    FAILED,
    DELETED
}