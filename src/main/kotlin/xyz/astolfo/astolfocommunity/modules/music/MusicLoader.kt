package xyz.astolfo.astolfocommunity.modules.music

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import net.dv8tion.jda.core.entities.Member
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.lib.commands.RequestedByElement
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import kotlin.coroutines.experimental.CoroutineContext

class MusicLoader(parentJob: Job? = null,
                  private val loaderContext: CoroutineContext,
                  private val musicManager: MusicManager,
                  private val queueBlock: suspend (requestedBy: Member, List<AudioTrack>, queueTop: Boolean, skipCurrentSong: Boolean) -> Pair<QueueResponse, Int>) {

    private val loaderJob = Job(parentJob)

    suspend fun loadAndQueue(
            scope: CommandScope,
            query: String,
            queueTop: Boolean,
            skipCurrentSong: Boolean,
            bypassUrlCheck: Boolean = false
    ) = load(scope, query, bypassUrlCheck) { progressMessage, audioItem ->
        queue(scope, query, queueTop, skipCurrentSong, progressMessage, audioItem)
    }

    suspend fun queue(
            scope: CommandScope,
            query: String,
            queueTop: Boolean,
            skipCurrentSong: Boolean,
            progressMessage: CachedMessage,
            audioItem: AudioItem
    ) = with(scope) {
        when (audioItem) {
            is AudioPlaylist -> {
                // User wanted the playlist so queue it
                val (response, amountAdded) = queueBlock(event.member, audioItem.tracks, queueTop, skipCurrentSong)

                val playlistNameWithQuery = "[${audioItem.name}]($query)"

                if (response == QueueResponse.QUEUED) {
                    progressMessage.contentEmbed = embed("\uD83D\uDCDD The playlist $playlistNameWithQuery has been added to the queue.")
                    return@with
                }

                val stringBuilder = StringBuilder("❗ ")

                if (amountAdded == 0) stringBuilder.append("No songs from the playlist $playlistNameWithQuery could be added to the queue since ")
                else stringBuilder.append("Only **$amountAdded** of **${audioItem.tracks.size}** songs from the playlist $playlistNameWithQuery could be added to the queue since ")

                when (response) {
                    QueueResponse.QUEUE_FULL -> stringBuilder.append("the queue is full! To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)")
                    QueueResponse.USER_QUEUE_FULL -> stringBuilder.append("you hit the user song limit!")
                    QueueResponse.DUPLICATE -> stringBuilder.append("they where all duplicates and duplicate song prevention is enabled!")
                    else -> TODO("This shouldn't happen")
                }

                progressMessage.contentEmbed = errorEmbed(stringBuilder.toString())
            }
            is AudioTrack -> {
                // User wanted this song so queue it
                val (response, _) = queueBlock(event.member, listOf(audioItem), queueTop, skipCurrentSong)

                val trackTitleWithUrl = "[${audioItem.info.title}](${audioItem.info.uri})"

                val newContent = when (response) {
                    QueueResponse.QUEUE_FULL -> errorEmbed("❗ $trackTitleWithUrl couldn't be added to the queue since the queue is full! " +
                            "To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)")
                    QueueResponse.USER_QUEUE_FULL -> errorEmbed("❗ $trackTitleWithUrl couldn't be added to the queue since you hit the user song limit!")
                    QueueResponse.DUPLICATE -> errorEmbed("❗ $trackTitleWithUrl couldn't be added to the queue since it was a duplicate and duplicate song prevention is enabled!")
                    QueueResponse.QUEUED -> embed("\uD83D\uDCDD $trackTitleWithUrl has been added to the queue")
                }
                if (progressMessage.isDeleted) newContent.queue()
                else progressMessage.contentEmbed = newContent
            }
            else -> TODO("What is this")
        }
    }

    suspend fun load(
            scope: CommandScope,
            query: String,
            bypassUrlCheck: Boolean = false,
            callback: suspend CommandScope.(CachedMessage, AudioItem) -> Unit
    ) = with(scope) {
        if (!loaderJob.isActive) throw IllegalStateException("Cannot load anymore songs after MusicLoader has been disposed of.")

        var musicQuery = musicManager.getEffectiveSearchQuery(query)

        if (musicQuery == null) {
            if (bypassUrlCheck) {
                musicQuery = MusicQuery(query, false)
            } else {
                // TODO improve this to tell you what actually happened
                errorEmbed("Either im not allowed to play music from that website or I do not support it!").queue()
                return@with
            }
        }

        val progressMessage = if (musicQuery.search) {
            embed("\uD83D\uDD0E Searching for **$query**...")
        } else {
            embed("\uD83D\uDD0E Loading **$query**...")
        }.sendCached()

        val modifiedContext = loaderContext + RequestedByElement(event.author)

        val handle = loaderJob.invokeOnCompletion(onCancelling = true) {
            if (loaderJob.isCancelled) runBlocking(modifiedContext) {
                progressMessage.contentEmbed = embed("${progressMessage.contentEmbed.description} **[CANCELLED]**")
            }
        }

        suspend fun load() {
            try {
                val audioItem = musicManager.audioPlayerManager.loadItemOrdered(queueBlock, musicQuery.query)

                val effectiveTrack = if (audioItem is AudioPlaylist && audioItem.isSearchResult) {
                    // Prompt the user to select which song they want

                    progressMessage.delete()

                    selectionBuilder<AudioTrack>()
                            .title("\uD83D\uDD0E Music Search Results:")
                            .results(audioItem.tracks)
                            .noResultsMessage("Unknown Song!")
                            .resultsRenderer { "**${it.info.title}** *by ${it.info.author}*" }
                            .description("Type the number of the song you want").execute() ?: return
                } else audioItem

                callback(progressMessage, effectiveTrack)
            } catch (e: FriendlyException) {
                progressMessage.contentEmbed = errorEmbed("❗ Failed due to an error: **${e.message}**")
            } catch (e: NoMatchException) {
                progressMessage.contentEmbed = errorEmbed("❗ No matches found for **$query**")
            }
            handle.dispose()
        }

        if (musicQuery.search) {
            // Search is part of the command therefore needs to run with the command
            suspendCancellableCoroutine<Unit> { cont ->
                val job = launch(modifiedContext, parent = loaderJob) {
                    load()
                }
                job.invokeOnCompletion(onCancelling = true) {
                    if (it != null) cont.tryResumeWithException(it)
                    else cont.tryResume(Unit)
                }
                cont.invokeOnCancellation {
                    job.cancel(it)
                }
            }
        } else {
            launch(modifiedContext, parent = loaderJob) {
                load()
            }
        }
    }
}

suspend fun AudioPlayerManager.loadItemOrdered(orderingKey: Any, identifier: String) = suspendCancellableCoroutine<AudioItem> { cont ->
    val future = loadItemOrdered(orderingKey, identifier, object : AudioLoadResultHandler {
        override fun loadFailed(exception: FriendlyException) {
            cont.resumeWithException(exception)
        }

        override fun trackLoaded(track: AudioTrack) {
            cont.resume(track)
        }

        override fun noMatches() {
            cont.resumeWithException(NoMatchException())
        }

        override fun playlistLoaded(playlist: AudioPlaylist) {
            cont.resume(playlist)
        }
    })
    cont.invokeOnCancellation {
        future.cancel(true)
    }
}

class NoMatchException : RuntimeException()