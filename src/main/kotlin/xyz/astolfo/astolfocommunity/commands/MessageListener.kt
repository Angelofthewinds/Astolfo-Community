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
import xyz.astolfo.astolfocommunity.lib.RateLimiter
import xyz.astolfo.astolfocommunity.lib.jda.builders.eventListenerBuilder
import java.util.concurrent.TimeUnit

class MessageListener(val application: AstolfoCommunityApplication) {

    companion object {
        private val processorContext = newFixedThreadPoolContext(2, "Message Processor")
    }

    val listener = eventListenerBuilder<GuildMessageReceivedEvent>(processorContext) {
        if (!event.author.isBot && !event.isWebhookMessage && event.channel.canTalk()) {
            messageActor.send(MessageData(event, timeIssued))
        }
    }

    internal val commandRateLimiter = RateLimiter<Long>(4, 6)
    internal val chatBotManager = ChatBotManager(application.properties)

    private val listenerCacheMap = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener<Long, GuildListener> { it.value.dispose() }
            .build(object : CacheLoader<Long, GuildListener>() {
                override fun load(guildId: Long): GuildListener = GuildListener(application, this@MessageListener)
            })

    open class MessageData(val messageReceivedEvent: GuildMessageReceivedEvent, val timeIssued: Long)

    private val messageActor = actor<MessageData>(context = processorContext, capacity = Channel.UNLIMITED) {
        for (messageData in channel) {
            try {
                withTimeout(15, TimeUnit.SECONDS) {
                    val guildId = messageData.messageReceivedEvent.guild.idLong
                    val listener = listenerCacheMap[guildId]!!
                    listener.addMessage(messageData)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                Sentry.capture(e)
            }
        }
    }

}