package xyz.astolfo.astolfocommunity.commands

import io.sentry.Sentry
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.newSingleThreadContext
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.utils.CachedMap
import java.sql.Time
import java.util.concurrent.TimeUnit

class GuildListener(
        val application: AstolfoCommunityApplication,
        val messageListener: MessageListener,
        private val guildId: Long
) {

    companion object {
        private val guildContext = newFixedThreadPoolContext(20, "Guild Listener")
        private val guildCleanUpContext = newSingleThreadContext("Guild Listener Clean Up")
    }

    private var destroyed = false

    suspend fun addMessage(messageData: MessageData) = messageActor.send(messageData)

    class GuildMessageData(
            val prefixMatched: String,
            messageReceivedEvent: GuildMessageReceivedEvent,
            timeIssued: Long
    ) : MessageData(messageReceivedEvent, timeIssued)

    private val messageActor = actor<MessageData>(context = guildContext, capacity = Channel.UNLIMITED) {
        for (data in channel) {
            if (destroyed) continue
            try {
                processData(data)
            } catch (e: Throwable) {
                e.printStackTrace()
                Sentry.capture(e)
            }
        }
        channelListeners.clear()
    }

    val channelListenerCount
        get() = channelListeners.size

    private val channelListeners = CachedMap(10L, TimeUnit.MINUTES, guildCleanUpContext, ::invalidate)

    fun invalidate(channelId: Long) {
        channelListeners.remove(channelId)
    }

    private fun invalidate(channelId: Long, listener: ChannelListener) {
        listener.dispose()
        println("REMOVE CHANNELLISTENER: $guildId/$channelId")
    }

    private suspend fun processData(messageData: MessageData) {
        val botId = messageData.messageReceivedEvent.jda.selfUser.idLong
        val prefix = application.astolfoRepositories.getEffectiveGuildSettings(messageData.messageReceivedEvent.guild.idLong).getEffectiveGuildPrefix(application)
        val channel = messageData.messageReceivedEvent.channel!!

        if (!channel.jda.guildCache.contains(channel.guild)
                || !channel.guild.textChannelCache.contains(channel)) return

        val rawMessage = messageData.messageReceivedEvent.message.contentRaw!!
        val validPrefixes = listOf(prefix, "<@$botId>", "<@!$botId>")

        val matchedPrefix = validPrefixes.find { rawMessage.startsWith(it, true) }

        val guildMessageData = GuildMessageData(matchedPrefix ?: "",
                messageData.messageReceivedEvent, messageData.timeIssued)

        val channelId = channel.idLong

        // This only is true when a user says a normal message
        if (matchedPrefix == null) {
            channelListeners.using(channel.idLong) {
                it.addMessage(guildMessageData)
            }
            return
        }
        synchronized(channelId) {
            // Process the message as if it was a command
            val listener = channelListeners.computeIfAbsent(channel.idLong) {
                // Create channel listener if it doesn't exist
                //println("CREATE CHANNELLISTENER: ${guild.idLong}/${channel.idLong}")
                ChannelListener(application, this)
            }
            listener.addCommand(guildMessageData)
        }
    }

    fun dispose() {
        destroyed = true
        messageActor.close()
    }

}