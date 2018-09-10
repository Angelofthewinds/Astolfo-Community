package xyz.astolfo.astolfocommunity.modules.music

import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import net.dv8tion.jda.core.Permission
import xyz.astolfo.astolfocommunity.GuildPlaylistEntry
import xyz.astolfo.astolfocommunity.commands.argsIterator
import xyz.astolfo.astolfocommunity.commands.next
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.lib.splitFirst
import xyz.astolfo.astolfocommunity.menus.chatInput
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import xyz.astolfo.astolfocommunity.modules.SubModuleBase

class GuildPlaylistSubModule(val musicManager: MusicManager) : SubModuleBase() {

    override fun ModuleBuilder.create() {
        command("guildplaylist", "gpl") {
            command("create", "c") {
                description("Creates a new guild playlist using the given name")
                usage("[name]")
                permission(Permission.MANAGE_SERVER)
                action {
                    if (args.isBlank()) {
                        errorEmbed("Enter a name to give the playlist!").queue()
                        return@action
                    }
                    val effectiveName = args.replace(Regex("\\s+"), "-")
                    if (application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, effectiveName) != null) {
                        errorEmbed("Playlist by that name already exists!").queue()
                        return@action
                    }
                    if (effectiveName.length > 20) {
                        errorEmbed("Max name length is 20 characters!").queue()
                        return@action
                    }
                    val playlist = application.astolfoRepositories.guildPlaylistRepository.save(GuildPlaylistEntry(name = effectiveName, guildId = event.guild.idLong))
                    embed("Playlist **${playlist.name}** (*${playlist.playlistKey!!}*) has been created!").queue()
                }
            }
            command("list") {
                description("Lists the playlists in the guild or the songs within a playlist")
                usage("", "[name]")
                action {
                    if (args.isBlank()) {
                        paginator("\uD83C\uDFBC __**Guild Playlists:**__") {
                            provider(10, application.astolfoRepositories.guildPlaylistRepository.findByGuildId(event.guild.idLong).map { "**${it.name}** (${it.songs.size} Songs)" })
                        }
                    } else {
                        val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
                        if (playlist == null) {
                            errorEmbed("That playlist doesn't exist!").queue()
                            return@action
                        }
                        paginator("\uD83C\uDFBC __**${playlist.name} Playlist:**__") {
                            provider(10, playlist.lavaplayerSongs.map { "[${it.info.title}](${it.info.uri})" })
                        }
                    }
                }
            }
            command("delete") {
                description("Deletes a guildplaylist in the guild")
                usage("[name]")
                permission(Permission.MANAGE_SERVER)
                action {
                    if (args.isBlank()) {
                        errorEmbed("Enter a playlist name!").queue()
                        return@action
                    }
                    val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
                    if (playlist == null) {
                        errorEmbed("I couldn't find a playlist with that name!").queue()
                        return@action
                    }
                    application.astolfoRepositories.guildPlaylistRepository.delete(playlist)
                    embed("I have deleted the playlist **${playlist.name}** (*${playlist.playlistKey}*)").queue()
                }
            }
            command("info") {
                description("Gives you information about a guildplaylist")
                usage("[name]")
                action {
                    if (args.isBlank()) {
                        errorEmbed("Enter a playlist name!").queue()
                        return@action
                    }
                    val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
                            ?: application.astolfoRepositories.guildPlaylistRepository.findByPlaylistKey(args)
                    if (playlist == null) {
                        errorEmbed("I couldn't find a playlist with that name!").queue()
                        return@action
                    }
                    embed {
                        title("Guild Playlist Info")
                        description = "**Name:** *${playlist.name}*\n" +
                                "**Key:** ${playlist.playlistKey}"
                        field("Details", "**Guild:** *${application.shardManager.getGuildById(playlist.guildId)?.name
                                ?: "Not Found"}*\n" +
                                "**Song Count:** *${playlist.songs.size}*", false)
                    }.queue()
                }
            }
            command("add") {
                description("Adds a song/playlist to a guildplaylist")
                usage("[name] [song/playlist]")
                permission(Permission.MANAGE_SERVER)
                action {
                    val (playlistName, songQuery) = args.splitFirst(" ")
                    if (playlistName.isBlank()) {
                        errorEmbed("Enter a playlist name!").queue()
                        return@action
                    }
                    if (songQuery.isBlank()) {
                        errorEmbed("Enter a song/playlist to add!").queue()
                        return@action
                    }
                    val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, playlistName)
                            ?: application.astolfoRepositories.guildPlaylistRepository.findByPlaylistKey(playlistName)
                    if (playlist == null) {
                        errorEmbed("I couldn't find a playlist with that name!").queue()
                        return@action
                    }
                    val audioItem = suspendCancellableCoroutine<AudioItem> { cont ->
                        val loadJob = Job()
                        val musicLoader = MusicLoader(
                                loadJob,
                                cont.context,
                                musicManager
                        ) { _, _, _, _ -> throw NotImplementedError() }
                        launch(cont.context, parent = loadJob) {
                            musicLoader.load(this@action, songQuery) { progressMessage, audioItem ->
                                progressMessage.delete()
                                cont.tryResume(audioItem)
                            }
                        }
                        loadJob.invokeOnCompletion {
                            if (it != null) cont.tryResumeWithException(it)
                        }
                        cont.invokeOnCancellation {
                            loadJob.cancel(it)
                        }
                    }
                    if (audioItem is AudioTrack) {
                        // If the track returned is a normal audio track
                        val audioTrack: AudioTrack = audioItem

                        val songs = playlist.lavaplayerSongs
                        songs.add(audioTrack)
                        playlist.lavaplayerSongs = songs
                        application.astolfoRepositories.guildPlaylistRepository.save(playlist)

                        embed("[${audioTrack.info.title}](${audioTrack.info.uri}) has been added to the playlist **${playlist.name}**").queue()
                    } else if (audioItem is AudioPlaylist) {
                        // If the track returned is a list of tracks
                        val audioPlaylist: AudioPlaylist = audioItem

                        // If the tracks are from directly from a url

                        val songs = playlist.lavaplayerSongs
                        songs.addAll(audioPlaylist.tracks)
                        playlist.lavaplayerSongs = songs
                        application.astolfoRepositories.guildPlaylistRepository.save(playlist)

                        embed("The playlist [${audioPlaylist.name}]($songQuery) has been added to the playlist **${playlist.name}**").queue()
                    }
                }
            }
            command("play", "p", "queue", "q") {
                description("Queues a guildplaylist to be played")
                usage("[name]")
                musicAction {
                    guildPlayAction(false)
                }
            }
            command("playshuffled", "psh", "queueshuffle", "qsh") {
                description("Queues a shuffled guildplaylist to be played")
                usage("[name]")
                musicAction {
                    guildPlayAction(true)
                }
            }
            command("remove") {
                action {
                    if (args.isBlank()) {
                        errorEmbed("Enter a playlist name!").queue()
                        return@action
                    }
                    val argsIterator = args.argsIterator()

                    val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, argsIterator.next())
                            ?: application.astolfoRepositories.guildPlaylistRepository.findByPlaylistKey(args)
                    if (playlist == null) {
                        errorEmbed("I couldn't find a playlist with that name!").queue()
                        return@action
                    }

                    suspend fun getIndex(title: String, arg: String): Int? {
                        return if (arg.isEmpty()) {
                            chatInput(title)
                                    .responseValidator {
                                        if (it.toBigIntegerOrNull() == null) {
                                            errorEmbed("Index must be a whole number!").queue()
                                            false
                                        } else true
                                    }
                                    .execute()?.toBigIntegerOrNull() ?: return null
                        } else {
                            val index = arg.toBigIntegerOrNull()
                            if (index == null) {
                                errorEmbed("Index must be a whole number!").queue()
                                return null
                            }
                            index
                        }.toInt()
                    }

                    val removeIndex = getIndex("Provide the index of the song you want to remove:", argsIterator.next(""))
                            ?: return@action

                    if (removeIndex < 1) {
                        errorEmbed("A index cannot be lower then 1").queue()
                        return@action
                    }

                    val removeAt = removeIndex - 1

                    val songs = playlist.lavaplayerSongs
                    val track = if (removeAt in songs.indices) {
                        songs.removeAt(removeAt)
                    } else null
                    playlist.lavaplayerSongs = songs
                    application.astolfoRepositories.guildPlaylistRepository.save(playlist)

                    if (track == null) {
                        errorEmbed("No song found at index $removeIndex in **${playlist.name}** (*${playlist.playlistKey}*)").queue()
                        return@action
                    }

                    embed("Removed **${track.info.title}** from the guild playlist **${playlist.name}** (*${playlist.playlistKey}*)").queue()
                }
            }
        }
    }
}

private suspend fun CommandScope.guildPlayAction(shuffle: Boolean) {
    val musicSession = joinAction() ?: return
    if (args.isBlank()) {
        errorEmbed("Enter a playlist name!").queue()
        return
    }
    val progressMessage = embed("Loading the guild playlist **$args**").sendCached()
    val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
            ?: application.astolfoRepositories.guildPlaylistRepository.findByPlaylistKey(args)
    if (playlist == null) {
        progressMessage.contentEmbed = errorEmbed("I couldn't find a playlist with that name!")
        return
    }
    val internalTracks = playlist.lavaplayerSongs.toMutableList()
    if (shuffle) internalTracks.shuffle()
    musicSession.musicLoader.queue(
            this,
            playlist.name,
            false,
            false,
            progressMessage,
            BasicAudioPlaylist(playlist.name, internalTracks, null, false)
    )
    musicSession.musicTextChannel.textChannelId = event.channel.idLong
}