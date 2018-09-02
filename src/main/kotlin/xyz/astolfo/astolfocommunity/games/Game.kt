package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel

abstract class Game(val member: Member, val channel: TextChannel) {

    var gameState = GameState.CREATED
        private set

    open suspend fun start0() {
        gameState = GameState.RUNNING
        start()
    }

    open suspend fun destroy0() {
        destroy()
        gameState = GameState.DESTROYED
    }

    protected open suspend fun start() {}
    protected open suspend fun destroy() {}

    protected suspend fun endGame() {
        launch(Unconfined) { GameHandler.stopGame(channel.idLong, member.user.idLong) }
    }
}

enum class GameState {
    CREATED,
    RUNNING,
    DESTROYED
}