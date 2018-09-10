package xyz.astolfo.astolfocommunity.modules.music

import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import xyz.astolfo.astolfocommunity.commands.argsIterator
import xyz.astolfo.astolfocommunity.commands.next
import xyz.astolfo.astolfocommunity.lib.Utils
import xyz.astolfo.astolfocommunity.lib.jda.message
import xyz.astolfo.astolfocommunity.menus.chatInput
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.menus.renderer
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import xyz.astolfo.astolfocommunity.modules.SubModuleBase
import xyz.astolfo.astolfocommunity.support.SupportLevel
import java.awt.Color
import java.util.concurrent.TimeUnit

class MusicGenericSubModule(private val musicManager: MusicManager) : SubModuleBase() {

    override fun ModuleBuilder.create() {
        command("join", "j") {
            musicAction { joinAction(true) }
        }
        command("leave", "l", "disconnect") {
            action {
                musicManager.dispose(event.guild.idLong)
                embed("\uD83D\uDEAA I have disconnected.").queue()
            }
        }
        command("play", "p", "search", "yt") {
            musicAction { playAction(false, false) }
        }
        command("playtop", "pt", "ptop") {
            musicAction { playAction(true, false) }
        }
        command("playskip", "ps", "pskip") {
            musicAction { playAction(true, true) }
        }
        command("playing", "nowplaying", "np", "q", "queue") {
            musicAction(mustBeInVoice = false, needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val paginator = paginator("Astolfo-Community Music Queue") {
                    provider(8, {
                        val songs = musicSession.musicQueue.songs
                        if (songs.isEmpty()) listOf("No songs in queue")
                        else songs.map { audioTrack ->
                            val stringBuilder = StringBuilder()
                            if (audioTrack.musicData.repeat) stringBuilder.append("\uD83D\uDD04 ")
                            stringBuilder.append("[${audioTrack.info.title}](${audioTrack.info.uri}) **${Utils.formatSongDuration(audioTrack.info.length, audioTrack.info.isStream)}**")
                            stringBuilder.toString()
                        }
                    })
                    renderer {
                        message {
                            embedRaw {
                                titleProvider.invoke()?.let { title(it) }
                                val lavaPlayer = musicSession.link.player
                                val currentTrack = lavaPlayer.playingTrack
                                field("\uD83C\uDFB6 Now Playing" + if (currentTrack != null) " - ${Utils.formatSongDuration(lavaPlayer.trackPosition)}/${Utils.formatSongDuration(currentTrack.info.length, currentTrack.info.isStream)}" else "", false) {
                                    if (currentTrack == null) {
                                        "No song currently playing"
                                    } else {
                                        "[${currentTrack.info.title}](${currentTrack.info.uri})"
                                    }
                                }
                                field("\uD83C\uDFBC Queue", false) { providedString }
                                footer = "Page ${currentPage + 1}/${provider.pageCount}"
                            }
                        }
                    }
                }
                updatable(7, TimeUnit.SECONDS) { paginator.render() }
                suspendCancellableCoroutine<Unit> { cont ->
                    cont.invokeOnCancellation {
                        paginator.destroy()
                    }
                }
            }
        }
        command("skip", "s") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val amountToSkip = if (args.isNotBlank()) {
                    val amountNum = args.toBigIntegerOrNull()?.toInt()
                    if (amountNum == null) {
                        errorEmbed("Amount to skip must be a whole number!").queue()
                        return@musicAction
                    }
                    if (amountNum < 1) {
                        errorEmbed("Amount to skip must be a greater than zero!").queue()
                        return@musicAction
                    }
                    amountNum
                } else 1
                val songsSkipped = musicSession.musicQueue.skip(amountToSkip)
                embed {
                    description = when {
                        songsSkipped.isEmpty() -> "No songs where skipped."
                        songsSkipped.size == 1 -> {
                            val skippedSong = songsSkipped.first()
                            "⏩ [${skippedSong.info.title}](${skippedSong.info.uri}) was skipped."
                        }
                        else -> "⏩ ${songsSkipped.size} songs where skipped"
                    }
                }.queue()
            }
        }
        command("volume", "v") {
            val supportLevel = SupportLevel.SUPPORTER
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val newVolume = if (args.isNotBlank()) {
                    val amountNum = args.toBigIntegerOrNull()?.toInt()
                    if (amountNum == null) {
                        errorEmbed("The new volume must be a whole number!").queue()
                        return@musicAction
                    }
                    if (amountNum < 5) {
                        errorEmbed("The new volume must be at least 5%!").queue()
                        return@musicAction
                    }
                    if (amountNum > 150) {
                        errorEmbed("The new volume must be no more than 150%!").queue()
                        return@musicAction
                    }
                    amountNum
                } else null
                if (newVolume == null) {
                    val currentVolume = musicSession.link.player.volume
                    embed("Current volume is **$currentVolume%**!").queue()
                } else {
                    val donationEntry = application.donationManager.getByMember(event.member)
                    if (donationEntry.ordinal < supportLevel.ordinal) {
                        embed {
                            description = "\uD83D\uDD12 Due to performance reasons volume changing is locked!" +
                                    " You can unlock this feature by becoming a [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)" +
                                    " and getting at least the **${supportLevel.rewardName}** Tier."
                            color = Color.RED
                        }.queue()
                        return@musicAction
                    }
                    val oldVolume = musicSession.link.player.volume
                    musicSession.link.player.volume = newVolume
                    embed("${volumeIcon(newVolume)} Volume has changed from **$oldVolume%** to **$newVolume%**").queue()
                }
            }
        }
        command("seek") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val currentTrack = musicSession.link.player.playingTrack
                if (currentTrack == null) {
                    errorEmbed("There are no tracks currently playing!").queue()
                    return@musicAction
                }
                if (!currentTrack.isSeekable) {
                    errorEmbed("You cannot seek this track!").queue()
                    return@musicAction
                }
                if (args.isBlank()) {
                    errorEmbed("Please specify a time to go to!").queue()
                    return@musicAction
                }
                val time = Utils.parseTimeString(args)
                if (time == null) {
                    errorEmbed("Unknown time format!\n" +
                            "Examples: `1:25:22`, `1h 25m 22s`").queue()
                    return@musicAction
                }
                if (time < 0) {
                    errorEmbed("Please give a time that's greater than 0!").queue()
                    return@musicAction
                }
                if (time > currentTrack.info.length) {
                    errorEmbed("I cannot seek that far into the song!").queue()
                    return@musicAction
                }
                musicSession.link.player.seekTo(time)
                embed("I have sought to the time **${Utils.formatSongDuration(time)}**").queue()
            }
        }
        command("replay", "reset") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val currentTrack = musicSession.link.player.playingTrack
                if (currentTrack == null) {
                    errorEmbed("There are no tracks currently playing!").queue()
                    return@musicAction
                }
                if (!currentTrack.isSeekable) {
                    errorEmbed("You cannot replay this track!").queue()
                    return@musicAction
                }
                musicSession.link.player.seekTo(0)
                embed("I have replayed the song ${currentTrack.info.title}**").queue()
            }
        }
        command("forward", "fwd") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val currentTrack = musicSession.link.player.playingTrack
                if (currentTrack == null) {
                    errorEmbed("There are no tracks currently playing!").queue()
                    return@musicAction
                }
                if (!currentTrack.isSeekable) {
                    errorEmbed("You cannot forward this track!").queue()
                    return@musicAction
                }
                if (args.isBlank()) {
                    errorEmbed("Please specify a amount to forward by!").queue()
                    return@musicAction
                }
                val time = Utils.parseTimeString(args)
                if (time == null) {
                    errorEmbed("Unknown time format!\n" +
                            "Examples: `1:25:22`, `1h 25m 22s`").queue()
                    return@musicAction
                }
                if (time < 0) {
                    errorEmbed("Please give a time that's greater than 0!").queue()
                    return@musicAction
                }
                val effectiveTime = time + musicSession.link.player.trackPosition
                if (effectiveTime > currentTrack.info.length) {
                    errorEmbed("You cannot forward into the song by that much!").queue()
                    return@musicAction
                }
                musicSession.link.player.seekTo(effectiveTime)
                embed("I have forwarded the song to the time **${Utils.formatSongDuration(effectiveTime)}**").queue()
            }
        }
        command("rewind", "rwd") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val currentTrack = musicSession.link.player.playingTrack
                if (currentTrack == null) {
                    errorEmbed("There are no tracks currently playing!").queue()
                    return@musicAction
                }
                if (!currentTrack.isSeekable) {
                    errorEmbed("You cannot rewind this track!").queue()
                    return@musicAction
                }
                if (args.isBlank()) {
                    errorEmbed("Please specify a amount to rewind by!").queue()
                    return@musicAction
                }
                val time = Utils.parseTimeString(args)
                if (time == null) {
                    errorEmbed("Unknown time format!\n" +
                            "Examples: `1:25:22`, `1h 25m 22s`").queue()
                    return@musicAction
                }
                if (time < 0) {
                    errorEmbed("Please give a time that's greater than 0!").queue()
                    return@musicAction
                }
                val effectiveTime = musicSession.link.player.trackPosition - time
                if (effectiveTime < 0) {
                    errorEmbed("You cannot rewind back in the song by that much!").queue()
                    return@musicAction
                }
                musicSession.link.player.seekTo(effectiveTime)
                embed("I have rewound the song to the time **${Utils.formatSongDuration(effectiveTime)}**").queue()
            }
        }
        command("repeat") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val currentTrack = musicSession.link.player.playingTrack
                if (currentTrack == null) {
                    errorEmbed("There are no tracks currently playing!").queue()
                    return@musicAction
                }
                val repeatType = when (args.toLowerCase()) {
                    "current", "single", "" -> RepeatMode.SINGLE
                    "queue", "all" -> RepeatMode.QUEUE
                    else -> {
                        errorEmbed("Unknown repeat mode! Valid Modes: **current/single**, **queue/all**").queue()
                        return@musicAction
                    }
                }
                if (musicSession.musicQueue.repeatMode == repeatType) {
                    when (repeatType) {
                        RepeatMode.QUEUE -> embed("The queue is no longer repeating!").queue()
                        RepeatMode.SINGLE -> embed("The song is no longer repeating!").queue()
                        else -> TODO("This shouldn't be called")
                    }
                    musicSession.musicQueue.repeatMode = RepeatMode.NONE
                } else {
                    when (repeatType) {
                        RepeatMode.QUEUE -> embed("The queue is now repeating!").queue()
                        RepeatMode.SINGLE -> embed("The song is now repeating!").queue()
                        else -> TODO("This shouldn't be called")
                    }
                    musicSession.musicQueue.repeatMode = repeatType
                }
            }
        }
        command("shuffle") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val currentTrack = musicSession.link.player.playingTrack
                if (currentTrack == null) {
                    errorEmbed("There are no tracks currently playing!").queue()
                    return@musicAction
                }
                musicSession.musicQueue.shuffle()
                embed("Music Queue has been shuffled!").queue()
            }
        }
        command("pause") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                musicSession.link.player.isPaused = true
                embed("Music has paused!").queue()
            }
        }
        command("resume", "unpause") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                musicSession.link.player.isPaused = false
                embed("Music has resumed playing!").queue()
            }
        }
        command("stop", "clear") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                musicSession.musicQueue.stop()
                embed("Music has stopped!").queue()
            }
        }
        command("leavecleanup", "lc") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val songsCleanedUp = musicSession.musicQueue.performLeaveCleanUp()
                embed {
                    description = when {
                        songsCleanedUp.isEmpty() -> "No songs where cleaned up."
                        songsCleanedUp.size == 1 -> {
                            val cleanedUpSong = songsCleanedUp.first()
                            val requesterName = cleanedUpSong.musicData.requestedBy?.effectiveName
                            "\uD83D\uDDD1 [${cleanedUpSong.info.title}](${cleanedUpSong.info.uri})${if (requesterName != null) " requested by $requesterName" else ""} was cleaned up."
                        }
                        else -> "\uD83D\uDDD1 ${songsCleanedUp.size} songs where cleaned up" // TODO show user names that got cleaned up
                    }
                }.queue()
            }
        }
        command("removedupes", "drm", "dr") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]
                val songsCleanedUp = musicSession.musicQueue.performDuplicateCleanUp()
                embed {
                    description = when {
                        songsCleanedUp.isEmpty() -> "No songs where cleaned up."
                        songsCleanedUp.size == 1 -> "\uD83D\uDDD1 One duplicate song cleaned up"
                        else -> "\uD83D\uDDD1 ${songsCleanedUp.size} duplicate songs where cleaned up"
                    }
                }.queue()
            }
        }
        command("move") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]

                if (musicSession.musicQueue.songs.isEmpty()) {
                    errorEmbed("The queue is empty").queue()
                    return@musicAction
                }

                val argsIterator = args.argsIterator()

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

                val moveFrom = getIndex("Provide the index of the song you want to move:", argsIterator.next(""))
                        ?: return@musicAction
                val moveTo = getIndex("Provide the index of where you want to move the song:", argsIterator.next(""))
                        ?: return@musicAction

                if (moveFrom < 1 || moveTo < 1) {
                    errorEmbed("A index cannot be lower then 1").queue()
                    return@musicAction
                }

                val (movedTrack, newIndex) = musicSession.musicQueue.move(moveFrom - 1, moveTo - 1)

                if (movedTrack == null) {
                    errorEmbed("No song found at index $moveFrom").queue()
                    return@musicAction
                }

                embed("Moved **${movedTrack.info.title}** to position ${newIndex + 1}").queue()
            }
        }
        command("remove") {
            musicAction(needsSession = true) {
                val musicSession = musicManager[event.guild.idLong]

                if (musicSession.musicQueue.songs.isEmpty()) {
                    errorEmbed("The queue is empty").queue()
                    return@musicAction
                }

                val argsIterator = args.argsIterator()

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
                        ?: return@musicAction

                if (removeIndex < 1) {
                    errorEmbed("A index cannot be lower then 1").queue()
                    return@musicAction
                }

                val response = musicSession.musicQueue.remove(removeIndex - 1)

                if (response == null) {
                    errorEmbed("No song found at index $removeIndex").queue()
                    return@musicAction
                }

                embed("Removed **${response.info.title}** from song queue").queue()
            }
        }
    }

}