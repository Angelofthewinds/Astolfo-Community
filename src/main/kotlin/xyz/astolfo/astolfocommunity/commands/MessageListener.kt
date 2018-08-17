package xyz.astolfo.astolfocommunity.commands

import io.sentry.Sentry
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.newSingleThreadContext
import net.dv8tion.jda.core.events.channel.text.TextChannelDeleteEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.RateLimiter
import xyz.astolfo.astolfocommunity.utils.CachedMap
import java.util.concurrent.TimeUnit

class MessageListener(val application: AstolfoCommunityApplication) {

    companion object {
        private val messageContext = newSingleThreadContext("Message Listener")
        private val messageCleanUpContext = newSingleThreadContext("Message Listener Clean Up")
    }

    val listener = object : ListenerAdapter() {
        override fun onGuildMessageReceived(event: GuildMessageReceivedEvent) {
            application.statsDClient.incrementCounter("messages_received")
            val timeIssued = System.nanoTime()
            if (event.author.isBot || event.isWebhookMessage || !event.channel.canTalk()) return
            messageActor.sendBlocking(MessageData(event, timeIssued))
        }

        override fun onGuildLeave(event: GuildLeaveEvent) {
            invalidate(event.guild.idLong)
        }

        override fun onTextChannelDelete(event: TextChannelDeleteEvent) {
            guildListeners.using(event.guild.idLong) {
                it.invalidate(event.channel.idLong)
            }
        }
    }

    internal val commandRateLimiter = RateLimiter<Long>(4, 6)
    internal val chatBotManager = ChatBotManager(application.properties)

    private val messageActor = actor<MessageData>(context = messageContext, capacity = Channel.UNLIMITED) {
        for (data in channel) {
            try {
                processData(data)
            } catch (e: Throwable) {
                e.printStackTrace()
                Sentry.capture(e)
            }
        }
    }

    val guildListenerCount
        get() = guildListeners.size

    val channelListenerCount
        get() = guildListeners.values.sumBy { it.channelListenerCount }


    private val guildListeners = CachedMap(10L, TimeUnit.MINUTES, messageCleanUpContext, ::invalidate)

    private fun invalidate(guildId: Long) {
        guildListeners.remove(guildId)
    }

    private fun invalidate(guildId: Long, listener: GuildListener) {
        listener.dispose()
        println("REMOVE GUILDLISTENER: $guildId")
    }

    private suspend fun processData(data: MessageData) {
        val guild = data.messageReceivedEvent.guild
        val guildId = guild.idLong
        synchronized(guildId){
            if(guild.owner == null || !guild.jda.guildCache.contains(guild)) return
            val listener = guildListeners.computeIfAbsent(guildId) {
                // Create if it doesn't exist
                println("CREATE GUILDLISTENER: $guildId")
                GuildListener(application, this, guildId)
            }
            listener.addMessage(data)
        }
    }

}

open class MessageData(val messageReceivedEvent: GuildMessageReceivedEvent, val timeIssued: Long)