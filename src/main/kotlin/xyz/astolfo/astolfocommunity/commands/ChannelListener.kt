package xyz.astolfo.astolfocommunity.commands

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import io.sentry.Sentry
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import java.util.concurrent.TimeUnit

class ChannelListener(
        val application: AstolfoCommunityApplication,
        val guildListener: GuildListener
) {

    companion object {
        private val processorContext = newFixedThreadPoolContext(10, "Channel Message Processor")
    }

    private var destroyed = false

    private val listenerCacheMap = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener<Long, SessionListener> { it.value.dispose() }
            .build(object : CacheLoader<Long, SessionListener>() {
                override fun load(guildId: Long): SessionListener = SessionListener(application, this@ChannelListener)
            })

    suspend fun addMessage(guildMessageData: GuildListener.GuildMessageData) = channelActor.send(MessageEvent(guildMessageData))
    suspend fun addCommand(guildMessageData: GuildListener.GuildMessageData) = channelActor.send(CommandEvent(guildMessageData))

    private interface ChannelEvent
    private class CommandEvent(val guildMessageData: GuildListener.GuildMessageData) : ChannelEvent
    private class MessageEvent(val guildMessageData: GuildListener.GuildMessageData) : ChannelEvent

    private val channelActor = actor<ChannelEvent>(context = processorContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            if (destroyed) continue
            try {
                handleEvent(event)
            } catch (e: Throwable) {
                e.printStackTrace()
                Sentry.capture(e)
            }
        }
        listenerCacheMap.invalidateAll()
    }

    private suspend fun handleEvent(event: ChannelEvent) {
        when (event) {
            is MessageEvent -> {
                // forward to session listener
                val guildMessageData = event.guildMessageData
                val user = guildMessageData.messageReceivedEvent.author!!
                // Ignore if session is invalid
                listenerCacheMap.getIfPresent(user.idLong)
                        ?.addMessage(guildMessageData)
            }
            is CommandEvent -> {
                // forward to and create session listener
                val guildMessageData = event.guildMessageData
                val member = guildMessageData.messageReceivedEvent.member!!

                listenerCacheMap.get(member.user.idLong).addCommand(guildMessageData)
            }
        }
    }

    fun dispose() {
        destroyed = true
        channelActor.close()
    }

}