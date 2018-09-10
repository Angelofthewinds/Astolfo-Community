package xyz.astolfo.astolfocommunity.modules.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import lavalink.client.player.IPlayer
import lavalink.client.player.LavalinkPlayer
import lavalink.client.player.event.PlayerEventListenerAdapter
import net.dv8tion.jda.core.entities.Member
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.lib.Timeout
import xyz.astolfo.astolfocommunity.lib.add
import xyz.astolfo.astolfocommunity.lib.addAllLast
import xyz.astolfo.astolfocommunity.lib.jda.embedRaw
import xyz.astolfo.astolfocommunity.lib.removeAt
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

class MusicQueue(private val application: AstolfoCommunityApplication,
                 private val guildId: Long,
                 private val player: LavalinkPlayer,
                 private val musicTextChannel: MusicTextChannel,
                 private val playSongTimeout: Timeout) {

    private val mainQueue = ConcurrentLinkedDeque<AudioTrack>()
    private val repeatQueue = object : ConcurrentLinkedDeque<AudioTrack>() {
        override fun addFirst(e: AudioTrack) {
            applySettings(e)
            super.addFirst(e)
        }

        override fun addLast(e: AudioTrack) {
            applySettings(e)
            super.addLast(e)
        }

        override fun add(element: AudioTrack): Boolean {
            applySettings(element)
            return super.add(element)
        }

        private fun applySettings(track: AudioTrack) = track.withMusicData {
            it.copy(repeat = true)
        }
    }

    val songs
        get() = mainQueue.toList() + repeatQueue.toList()

    var repeatMode = RepeatMode.NONE
        set(value) {
            if (value != RepeatMode.QUEUE) {
                repeatQueue.clear()
            }
            field = value
        }

    init {
        player.addListener(object : PlayerEventListenerAdapter() {
            private var cachedTrack: AudioTrack? = null

            override fun onTrackStart(player: IPlayer, track: AudioTrack) {
                cachedTrack = track
                playSongTimeout.stop()
                musicTextChannel.nowPlayingMessage.currentlyPlaying = track
            }

            override fun onTrackEnd(player: IPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                playSongTimeout.start()
                val cachedTrack = this.cachedTrack
                this.cachedTrack = null
                if (repeatMode != RepeatMode.NONE && cachedTrack != null) {
                    val userData = cachedTrack.userData
                    val clonedTrack = cachedTrack.makeClone()
                    clonedTrack.userData = userData
                    repeatQueue.addLast(clonedTrack)
                }
                if (!playNextSong()) {
                    musicTextChannel.nowPlayingMessage.currentlyPlaying = null
                    musicTextChannel.send(embedRaw("\uD83C\uDFC1 Song Queue Finished!"))
                }
            }
        })
    }

    private fun playNextSong(): Boolean = synchronized(player) {
        if (player.playingTrack != null) return false
        val nextSong = next() ?: return false
        player.playTrack(nextSong)
        return true
    }

    private fun next(): AudioTrack? = when (repeatMode) {
        RepeatMode.NONE -> mainQueue.poll()
        RepeatMode.SINGLE -> {
            val songToRepeat = repeatQueue.firstOrNull()
            // If somehow we cannot repeat, go back to normal mode
            if (songToRepeat == null) {
                val newRepeatTrack = mainQueue.firstOrNull()
                if (newRepeatTrack == null) {
                    repeatMode = RepeatMode.NONE
                    null
                } else newRepeatTrack
            } else songToRepeat
        }
        RepeatMode.QUEUE -> {
            val track = mainQueue.poll()
            if (track != null) {
                repeatQueue.addLast(track)
                track
            } else {
                val songToRepeat = repeatQueue.poll()
                // If somehow we cannot repeat, go back to normal mode
                if (songToRepeat == null) repeatMode = RepeatMode.NONE
                else repeatQueue.addLast(songToRepeat)
                songToRepeat
            }
        }
    }

    fun addAll(requestedBy: Member, tracks: List<AudioTrack>, queueTop: Boolean): Pair<QueueResponse, Int> {
        if (tracks.isEmpty()) return QueueResponse.QUEUED to 0

        val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(guildId)
        val supportLevel = application.donationManager.getByMember(application.shardManager.getGuildById(guildId)!!.owner)

        val userLimitAmount = guildSettings.maxUserSongs
        val duplicateSongPrevention = guildSettings.dupSongPrevention

        var currentUserSongCount = songs.count { it.musicData.requestedBy == requestedBy }

        fun add(track: AudioTrack, queueTop: Boolean): QueueResponse {
            repeatMode = RepeatMode.NONE
            track.withMusicData { it.copy(requestedBy = requestedBy) }

            if (duplicateSongPrevention && songs.any { it.info.uri == track.info.uri }) return QueueResponse.DUPLICATE
            if (userLimitAmount in 1..currentUserSongCount) return QueueResponse.USER_QUEUE_FULL
            if (songs.size >= supportLevel.queueSize) return QueueResponse.QUEUE_FULL

            currentUserSongCount++

            if (queueTop) mainQueue.addFirst(track)
            else mainQueue.addLast(track)
            playNextSong()
            return QueueResponse.QUEUED
        }

        var response: QueueResponse = QueueResponse.QUEUED
        var queuedDuplicate = false
        var amountAdded = 0
        mainForLoop@ for (track in tracks) {
            val addResponse = add(track, queueTop)
            when (addResponse) {
                QueueResponse.QUEUE_FULL -> {
                    response = addResponse
                    break@mainForLoop
                }
                QueueResponse.USER_QUEUE_FULL -> {
                    response = addResponse
                    break@mainForLoop
                }
                QueueResponse.DUPLICATE -> {
                    queuedDuplicate = true
                }
                QueueResponse.QUEUED -> {
                    amountAdded++
                }
            }
        }
        if (response == QueueResponse.QUEUED && queuedDuplicate) {
            response = QueueResponse.DUPLICATE
        }
        return response to amountAdded
    }


    fun skip(amountToSkip: Int): List<AudioTrack> {
        repeatMode = RepeatMode.NONE
        val skippedTracks = mutableListOf<AudioTrack>()
        val playingTrack = player.playingTrack
        val effectiveAmountToSkip = if (playingTrack != null) amountToSkip - 1 else amountToSkip
        repeat(effectiveAmountToSkip) { _ ->
            val removedTrack = mainQueue.removeFirst() ?: return@repeat
            skippedTracks += removedTrack
        }
        if (playingTrack != null) {
            skippedTracks.add(0, playingTrack)
            player.stopTrack()
        }
        return skippedTracks
    }

    fun shuffle() {
        fun shuffle(queue: Deque<AudioTrack>) {
            val queueList = queue.toMutableList()
            queueList.shuffle()
            queue.addAllLast(queueList)
            repeat(queue.size - queueList.size) { queue.removeFirst() }
        }

        shuffle(mainQueue)
        shuffle(repeatQueue)
    }

    fun stop() {
        repeatMode = RepeatMode.NONE
        mainQueue.clear()
        player.stopTrack()
    }

    fun performLeaveCleanUp(): List<AudioTrack> {
        val voiceChannel = player.link.channel ?: return emptyList()
        val membersInChannel = voiceChannel.members

        val songsRemoved = mutableListOf<AudioTrack>()
        fun performLeaveCleanUp(queue: Deque<AudioTrack>) {
            queue.removeIf {
                val requestedBy = it.musicData.requestedBy ?: return@removeIf false
                val doRemove = !membersInChannel.contains(requestedBy)
                if (doRemove) songsRemoved += it
                doRemove
            }
        }
        performLeaveCleanUp(mainQueue)
        performLeaveCleanUp(repeatQueue)
        return songsRemoved
    }

    fun performDuplicateCleanUp(): List<AudioTrack> {
        val songsApproved = mutableListOf<String>()
        val songsRemoved = mutableListOf<AudioTrack>()
        fun performDuplicateCleanUp(queue: Deque<AudioTrack>) {
            queue.removeIf {
                val identifier = it.identifier
                if (songsApproved.contains(identifier)) {
                    songsRemoved += it
                    true
                } else {
                    songsApproved += identifier
                    false
                }
            }
        }

        val playingTrack = player.playingTrack
        if (playingTrack != null) songsApproved += playingTrack.identifier
        performDuplicateCleanUp(repeatQueue)
        performDuplicateCleanUp(mainQueue)
        return songsRemoved
    }

    fun move(fromIndex: Int, toIndex: Int): Pair<AudioTrack?, Int> {
        val songToMove = mainQueue.toList().getOrNull(fromIndex) ?: return null to 0
        mainQueue.remove(songToMove)
        val newIndex = if (toIndex >= mainQueue.size) {
            mainQueue.addLast(songToMove)
            mainQueue.size - 1
        } else {
            mainQueue.add(toIndex, songToMove)
            toIndex
        }
        return songToMove to newIndex
    }

    fun remove(index: Int): AudioTrack? = mainQueue.removeAt(index)

}

fun AudioTrack.withMusicData(block: (MusicData) -> MusicData) {
    musicData = block(musicData)
}

var AudioTrack.musicData
    get() = getUserData(MusicData::class.java) ?: MusicData()
    set(value) {
        userData = value
    }

data class MusicData(
        val repeat: Boolean = false,
        val requestedBy: Member? = null
)

enum class QueueResponse {
    QUEUE_FULL,
    USER_QUEUE_FULL,
    DUPLICATE,
    QUEUED
}

enum class RepeatMode {
    NONE,
    SINGLE,
    QUEUE
}