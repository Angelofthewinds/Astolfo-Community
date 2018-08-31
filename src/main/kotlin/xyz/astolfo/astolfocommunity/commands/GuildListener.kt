package xyz.astolfo.astolfocommunity.commands

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import io.sentry.Sentry
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.withTimeout
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import java.util.concurrent.TimeUnit

class GuildListener(
        val application: AstolfoCommunityApplication,
        val messageListener: MessageListener
) {

    companion object {
        private val processorContext = newFixedThreadPoolContext(10, "Guild Message Processor")
    }

    private var destroyed = false

    private val listenerCacheMap = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener<Long, ChannelListener> { it.value.dispose() }
            .build(object : CacheLoader<Long, ChannelListener>() {
                override fun load(guildId: Long): ChannelListener = ChannelListener(application, this@GuildListener)
            })

    class GuildMessageData(
            val prefixMatched: String,
            messageReceivedEvent: GuildMessageReceivedEvent,
            timeIssued: Long
    ) : MessageListener.MessageData(messageReceivedEvent, timeIssued)

    private val messageActor = actor<MessageListener.MessageData>(context = processorContext, capacity = Channel.UNLIMITED) {
        for (messageData in channel) {
            if (destroyed) continue
            try {
                withTimeout(15, TimeUnit.SECONDS) {
                    handleData(messageData)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                Sentry.capture(e)
            }
        }
        listenerCacheMap.invalidateAll()
    }

    suspend fun addMessage(messageData: MessageListener.MessageData) = messageActor.send(messageData)

    private suspend fun handleData(messageData: MessageListener.MessageData) {
        val botId = messageData.messageReceivedEvent.jda.selfUser.idLong
        val prefix = application.astolfoRepositories.getEffectiveGuildSettings(messageData.messageReceivedEvent.guild.idLong).getEffectiveGuildPrefix(application)
        val channel = messageData.messageReceivedEvent.channel!!

        val rawMessage = messageData.messageReceivedEvent.message.contentRaw!!
        val validPrefixes = listOf(prefix, "<@$botId>", "<@!$botId>")

        val matchedPrefix = validPrefixes.find { rawMessage.startsWith(it, true) }

        val guildMessageData = GuildMessageData(matchedPrefix ?: "",
                messageData.messageReceivedEvent, messageData.timeIssued)

        // This only is true when a user says a normal message
        if (matchedPrefix == null) {
            // Ignore if channel listener is invalid
            listenerCacheMap.getIfPresent(channel.idLong)?.addMessage(guildMessageData)
            return
        }
        // Process the message as if it was a command
        listenerCacheMap.get(channel.idLong).addCommand(guildMessageData)
    }

    fun dispose() {
        destroyed = true
        messageActor.close()
    }

}