package xyz.astolfo.astolfocommunity.modules.music

import kotlinx.coroutines.experimental.Job
import lavalink.client.io.Link
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.lib.Timeout
import xyz.astolfo.astolfocommunity.lib.cancelQuietly
import xyz.astolfo.astolfocommunity.lib.jda.embed
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

class MusicSession(val context: CoroutineContext,
                   val application: AstolfoCommunityApplication,
                   val musicManager: MusicManager,
                   val link: Link,
                   val guildId: Long) {

    private val sessionJob = Job()
    private val playSongTimeout = Timeout(context, 5, TimeUnit.MINUTES, sessionJob) {
        musicTextChannel.send(embed("Disconnected due to no songs being played."))
        musicManager.dispose(guildId)
    }

    private val aloneTimeout = MusicVoiceChannelTimeout(context, this, Timeout(context, 5, TimeUnit.MINUTES, sessionJob) {
        musicTextChannel.send(embed("Disconnected due being all alone."))
        musicManager.dispose(guildId)
    }, Timeout(context, 5, TimeUnit.MINUTES, sessionJob) {
        // Dispose after 5 minutes when session isn;t used
        musicManager.dispose(guildId)
    })

    val musicTextChannel = MusicTextChannel(application, context)
    val musicLoader = MusicLoader(sessionJob, context, musicManager) { requestedBy, tracks, queueTop, skipCurrentSong ->
        val response = musicQueue.addAll(requestedBy, tracks, queueTop)
        if (response.first == QueueResponse.QUEUED && skipCurrentSong) musicQueue.skip(1)
        response
    }

    val musicQueue = MusicQueue(application, guildId, link.player, musicTextChannel, playSongTimeout)

    fun dispose() {
        aloneTimeout.dispose()
        sessionJob.cancelQuietly()
        musicTextChannel.dispose()

        link.disconnect()
        link.resetPlayer()
    }

}