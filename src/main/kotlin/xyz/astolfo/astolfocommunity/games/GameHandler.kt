package xyz.astolfo.astolfocommunity.games

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.newSingleThreadContext
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object GameHandler {

    private val gameHandlerContext = newSingleThreadContext("Game Handler")

    private val nextId = AtomicLong()

    private val gameSessionCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .removalListener<Long, GameSession> { (id, gameSession) ->
                if (gameSession.game != null) launch(gameHandlerContext) { gameSession.stopGame() }
                playerMap.filterValues { it == id }.forEach { key, _ -> playerMap.remove(key) }
            }
            .build(object : CacheLoader<Long, GameSession>() {
                override fun load(key: Long): GameSession = GameSession()
            })

    private val playerMap = ConcurrentHashMap<GameSessionKey, Long>()

    private suspend fun startGame(sessionKey: GameSessionKey, game: Game) {
        val id = playerMap.computeIfAbsent(sessionKey) { nextId.incrementAndGet() }
        gameSessionCache[id]!!.also { it.startGame(game) }
    }

    private suspend fun stopGame(sessionKey: GameSessionKey) {
        val id = playerMap[sessionKey] ?: return
        gameSessionCache.getIfPresent(id)?.stopGame()
    }

    operator fun get(channelId: Long, userId: Long): GameSession? {
        val id = playerMap.computeIfAbsent(GameSessionKey(channelId, userId)) { nextId.incrementAndGet() }
        return gameSessionCache.get(id)
    }

    fun getAllInChannel(channelId: Long): Collection<GameSession> = gameSessionCache.getAll(playerMap.filterKeys { it.channelId == channelId }.values).values

    suspend fun stopGame(channelId: Long, userId: Long) {
        val sessionKey = GameSessionKey(channelId, userId)
        val id = playerMap[sessionKey] ?: return
        stopGame(sessionKey)
        playerMap.filterValues { it == id }.forEach { key, _ -> playerMap.remove(key) }
    }

    suspend fun startGame(channelId: Long, userId: Long, game: Game) {
        val sessionKey = GameSessionKey(channelId, userId)
        startGame(sessionKey, game)
        if (game is GroupGame) {
            val id = playerMap[sessionKey]!!
            game.players.forEach {
                playerMap[GameSessionKey(channelId, it)] = id
            }
        }
    }

    suspend fun leaveGame(channelId: Long, member: Member) {
        val sessionKey = GameSessionKey(channelId, member.user.idLong)
        val id = playerMap[sessionKey] ?: return
        val game = gameSessionCache.getIfPresent(id)?.game ?: return
        if (game is GroupGame) {
            game.leave0(member)
            playerMap.remove(sessionKey)
        }
    }

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