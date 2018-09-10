package xyz.astolfo.astolfocommunity.modules.music

import net.dv8tion.jda.core.events.guild.voice.GuildVoiceJoinEvent
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceLeaveEvent
import xyz.astolfo.astolfocommunity.lib.Timeout
import xyz.astolfo.astolfocommunity.lib.jda.builders.listenerBuilder
import kotlin.coroutines.experimental.CoroutineContext

class MusicVoiceChannelTimeout(
        private val context: CoroutineContext,
        private val musicSession: MusicSession,
        private val aloneTimeout: Timeout,
        private val noVoiceChannelTimeout: Timeout
) {

    private val eventListener = listenerBuilder(context) {
        on<GuildVoiceJoinEvent> {
            if (event.channelJoined == musicSession.link.channel || event.member == event.guild.selfMember) {
                updateVoiceChannel()
            }
        }
        on<GuildVoiceLeaveEvent> {
            if (event.channelLeft == musicSession.link.channel || event.member == event.guild.selfMember) {
                updateVoiceChannel()
            }
        }
    }

    init {
        updateVoiceChannel()
        musicSession.application.eventManager.register(eventListener)
    }

    @Synchronized
    fun updateVoiceChannel() {
        val voiceChannel = musicSession.link.channel
        if (voiceChannel == null) {
            noVoiceChannelTimeout.start()
            aloneTimeout.stop()
            return
        }
        noVoiceChannelTimeout.stop()
        val members = voiceChannel.members.filter { !it.user.isBot }
        if (members.isEmpty()) aloneTimeout.start()
        else aloneTimeout.stop()
    }

    fun dispose() {
        musicSession.application.eventManager.unregister(eventListener)
    }

}