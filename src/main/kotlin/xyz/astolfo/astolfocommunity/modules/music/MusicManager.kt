package xyz.astolfo.astolfocommunity.modules.music

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import lavalink.client.io.Lavalink
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.lib.createLogger
import java.net.MalformedURLException
import java.net.URI
import java.net.URL

class MusicManager(private val application: AstolfoCommunityApplication) {

    companion object {
        private val logger = createLogger()
        private val musicContext = newFixedThreadPoolContext(10, "Music Manager")
    }

    private val lavalink = Lavalink(
            application.properties.bot_user_id,
            application.properties.shard_count
    ) { shardID -> application.shardManager.getShardById(shardID) }

    private val musicSessionMap = CacheBuilder.newBuilder()
            .removalListener<Long, MusicSession> { (guildId, session) ->
                session.dispose()
                logger.debug("Disposed Music Session $guildId")
            }
            .build(object : CacheLoader<Long, MusicSession>() {
                override fun load(guildId: Long): MusicSession {
                    logger.debug("Created Music Session $guildId")
                    return MusicSession(musicContext, application, this@MusicManager, lavalink.getLink(guildId.toString()), guildId)
                }
            })

    val audioPlayerManager: AudioPlayerManager = DefaultAudioPlayerManager()

    init {
        application.properties.lavalink_nodes.split(",").forEach {
            lavalink.addNode(URI(it), application.properties.lavalink_password)
        }

        application.eventManager.register(lavalink)

        audioPlayerManager.setItemLoaderThreadPoolSize(10)
        val youtubeSourceManager = YoutubeAudioSourceManager(true)
        youtubeSourceManager.setPlaylistPageCount(5)
        audioPlayerManager.registerSourceManager(youtubeSourceManager)
        audioPlayerManager.registerSourceManager(SoundCloudAudioSourceManager())
        audioPlayerManager.registerSourceManager(BandcampAudioSourceManager())
        audioPlayerManager.registerSourceManager(VimeoAudioSourceManager())
        audioPlayerManager.registerSourceManager(TwitchStreamAudioSourceManager())
        audioPlayerManager.registerSourceManager(BeamAudioSourceManager())
        audioPlayerManager.registerSourceManager(HttpAudioSourceManager())
    }

    operator fun get(guildId: Long): MusicSession = musicSessionMap[guildId]
    fun getIfPresent(guildId: Long): MusicSession? = musicSessionMap.getIfPresent(guildId)

    fun dispose(guildId: Long) {
        musicSessionMap.invalidate(guildId)
    }

    private val allowedHosts = listOf("youtube.com", "youtu.be", "music.youtube.com", "soundcloud.com", "bandcamp.com", "beam.pro", "mixer.com", "vimeo.com")

    fun getEffectiveSearchQuery(query: String): MusicQuery? {
        return try {
            val url = URL(query)
            val host = url.host.let { if (it.startsWith("www")) it.substring(4) else it }
            if (!allowedHosts.any { it.equals(host, true) }) {
                return null
            }
            MusicQuery(query, false)
        } catch (e: MalformedURLException) {
            MusicQuery("ytsearch: $query", true)
        }
    }

}

class MusicQuery(val query: String, val search: Boolean)