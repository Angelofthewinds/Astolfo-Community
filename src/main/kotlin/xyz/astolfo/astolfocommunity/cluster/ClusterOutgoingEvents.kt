package xyz.astolfo.astolfocommunity.cluster

sealed class ClusterOutgoingEvent {
    class MusicInit(state: ClusterMusicState) : ClusterOutgoingEvent()
    class MusicUpdatePlaying(state: ClusterPlayingSongState)   : ClusterOutgoingEvent()
    class MusicUpdateQueue()
}

data class ClusterMusicState(
        val guildId: Long,
        val textChannelId: Long,
        val voiceChannelId: Long,
        val playingSong: ClusterPlayingSongState,
        val songQueue: List<ClusterMusicSong>
)

data class ClusterPlayingSongState(
        val song: ClusterMusicSong,
        val progress: Long,
        val timeSampled: Long
)

data class ClusterMusicSong(
        val title: String,
        val duration: Long,
        val requester: ClusterUser?
)

data class ClusterUser(
        val userId: Long,
        val username: String
)