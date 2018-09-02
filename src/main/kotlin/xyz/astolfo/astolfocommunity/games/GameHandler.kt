package xyz.astolfo.astolfocommunity.games

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageDeleteEvent
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import xyz.astolfo.astolfocommunity.lib.ConflatedMessage
import xyz.astolfo.astolfocommunity.lib.asConflated
import xyz.astolfo.astolfocommunity.lib.hasPermission
import xyz.astolfo.astolfocommunity.lib.jda.builders.listenerBuilder
import xyz.astolfo.astolfocommunity.lib.jda.message
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import xyz.astolfo.astolfocommunity.lib.smartActor
import java.util.concurrent.TimeUnit

object GameHandler {

    private val gameHandlerContext = newSingleThreadContext("Game Handler")

    private val gameSessionCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .removalListener<GameSessionKey, GameSession> {
                if (it.value.game != null) launch(gameHandlerContext) { it.value.stopGame() }
            }
            .build(object : CacheLoader<GameSessionKey, GameSession>() {
                override fun load(key: GameSessionKey): GameSession = GameSession()
            })

    private suspend fun startGame(sessionKey: GameSessionKey, game: Game) =
            gameSessionCache[sessionKey]!!.also { it.startGame(game) }

    private suspend fun stopGame(sessionKey: GameSessionKey) {
        gameSessionCache.getIfPresent(sessionKey)?.stopGame()
    }

    operator fun get(channelId: Long, userId: Long): GameSession = gameSessionCache.get(GameSessionKey(channelId, userId))

    fun getAllInChannel(channelId: Long): Collection<GameSession> = gameSessionCache.getAll(gameSessionCache.asMap().keys.filter { it.channelId == channelId }).values

    suspend fun stopGame(channelId: Long, userId: Long) = stopGame(GameSessionKey(channelId, userId))
    suspend fun startGame(channelId: Long, userId: Long, game: Game) = startGame(GameSessionKey(channelId, userId), game)

    data class GameSessionKey(val channelId: Long, val userId: Long)

}

class GameSession {

    private val sessionSync = Mutex()

    var game: Game? = null

    suspend fun startGame(game: Game) = sessionSync.withLock {
        if (this.game != null) throw IllegalStateException("Cannot start new game since a game is already running!")
        this.game = game
        game.start0()
    }

    suspend fun stopGame() = sessionSync.withLock {
        val game = this.game ?: return
        this.game = null
        game.destroy0()
    }

}

abstract class ReactionGame(member: Member, channel: TextChannel, private val reactions: List<String>) : Game(member, channel) {

    companion object {
        private val reactionGameContext = newFixedThreadPoolContext(10, "Reaction Game")
    }

    private val currentMessageSync = Any()
    private var _currentMessage: ConflatedMessage? = null
    protected val currentMessage
        get() = _currentMessage?.cachedMessage


    private sealed class ReactionGameEvent {
        class ReactionEvent(val event: GenericMessageReactionEvent) : ReactionGameEvent()
        class DeleteEvent(val event: MessageDeleteEvent) : ReactionGameEvent()
    }

    private val reactionGameActor = smartActor<ReactionGameEvent>(reactionGameContext, Channel.UNLIMITED, CoroutineStart.LAZY) {
        for (event in this.channel) {
            if (gameState == GameState.DESTROYED) continue
            handleEvent(event)
        }
    }

    private suspend fun handleEvent(event: ReactionGameEvent) {
        when (event) {
            is ReactionGameEvent.ReactionEvent -> {
                val reactionEvent = event.event
                if (currentMessage == null || reactionEvent.user.idLong == reactionEvent.jda.selfUser.idLong) return

                if (currentMessage!!.idLong != reactionEvent.messageIdLong || reactionEvent.user.isBot) return

                if (reactionEvent.user.idLong != member.user.idLong) {
                    if (reactionEvent.textChannel.hasPermission(Permission.MESSAGE_MANAGE)) reactionEvent.reaction.removeReaction(reactionEvent.user).queue()
                    return
                }

                onGenericMessageReaction(reactionEvent)
            }
            is ReactionGameEvent.DeleteEvent -> {
                if (currentMessage?.idLong == event.event.messageIdLong) endGame()
            }
        }
    }

    private val listener = listenerBuilder(reactionGameContext) {
        on<GenericMessageReactionEvent> {
            if (event.channel.idLong != channel.idLong) return@on

            reactionGameActor.send(ReactionGameEvent.ReactionEvent(event))
        }
        on<MessageDeleteEvent> {
            reactionGameActor.send(ReactionGameEvent.DeleteEvent(event))
        }
    }

    protected fun setContent(messageEmbed: MessageEmbed) = setContent(message(messageEmbed))
    @Suppress("MemberVisibilityCanBePrivate")
    protected fun setContent(message: Message) = synchronized(currentMessageSync) {
        if (_currentMessage == null) {
            _currentMessage = channel.sendMessage(message).sendCached().asConflated(reactionGameContext, 2)
            currentMessage!!.reactions += reactions
        } else _currentMessage!!.contentMessage = message
    }

    abstract suspend fun onGenericMessageReaction(event: GenericMessageReactionEvent)

    override suspend fun start() {
        super.start()
        channel.jda.addEventListener(listener)
    }

    override suspend fun destroy() {
        super.destroy()
        channel.jda.removeEventListener(listener)

        reactionGameActor.closeAndJoin()
        currentMessage?.reactions?.clear()
    }

}