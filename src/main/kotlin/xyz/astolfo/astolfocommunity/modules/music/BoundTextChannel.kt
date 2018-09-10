package xyz.astolfo.astolfocommunity.modules.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.coroutines.experimental.runBlocking
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.lib.commands.RequestedByElement
import xyz.astolfo.astolfocommunity.lib.jda.embed
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import kotlin.coroutines.experimental.CoroutineContext

class MusicTextChannel(private val application: AstolfoCommunityApplication,
                       private val context: CoroutineContext,
                       var textChannelId: Long = 0L) {

    private val textChannel: TextChannel?
        get() = application.shardManager.getTextChannelById(textChannelId)

    val nowPlayingMessage = NowPlayingMessage()

    fun send(embed: MessageEmbed) {
        textChannel?.sendMessage(embed)?.queue()
    }

    fun dispose() {
        nowPlayingMessage.dispose()
    }

    inner class NowPlayingMessage {
        var cachedMessage: CachedMessage? = null

        var currentlyPlaying: AudioTrack? = null
            set(value) = synchronized(this) {
                if (value != field) {
                    if (value == null) {
                        // No longer playing anything, this message isn't needed anymore
                        cachedMessage?.delete()
                        cachedMessage = null
                    } else {
                        val textChannel = this@MusicTextChannel.textChannel
                        if (textChannel != null) {
                            val requestedByUser = value.musicData.requestedBy?.user
                            val content = runBlocking(if (requestedByUser != null) context + RequestedByElement(requestedByUser) else context) {
                                embed { author("\uD83C\uDFB6 Now Playing: ${value.info.title}", value.info.uri) }
                            }
                            if (cachedMessage != null && cachedMessage?.idLong == textChannel.latestMessageIdLong) {
                                // Just update the message since its the latest in the text channel
                                cachedMessage!!.contentEmbed = content
                            } else {
                                // No longer the latest, update its position by sending a new one
                                cachedMessage?.delete()
                                cachedMessage = textChannel.sendMessage(content).sendCached()
                            }
                        } else {
                            // Text channel doesn't exist, not sure what to do here
                            cachedMessage?.delete()
                            cachedMessage = null
                        }
                    }
                }
                field = value
            }

        fun dispose() {
            currentlyPlaying = null
        }
    }

}