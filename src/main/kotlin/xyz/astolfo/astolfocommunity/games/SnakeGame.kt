package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import xyz.astolfo.astolfocommunity.lib.jda.AstolfoEmbedBuilder
import xyz.astolfo.astolfocommunity.lib.jda.embed
import xyz.astolfo.astolfocommunity.lib.smartActor
import java.awt.Point
import java.util.*
import java.util.concurrent.TimeUnit

class SnakeGame(member: Member, channel: TextChannel) : ReactionGame(member, channel, SnakeDirection.values().map { it.emote }) {

    companion object {
        private val snakeContext = newFixedThreadPoolContext(10, "Snake")

        const val MAP_SIZE = 10
        val MAP_RANGE = 0 until MAP_SIZE
        const val UPDATE_SPEED = 2L
        private val random = Random()
    }

    private var appleLocation = randomPoint()
    private val snake = mutableListOf<Point>()
    private var snakeDirection = SnakeDirection.UP

    private lateinit var updateJob: Job

    override suspend fun onGenericMessageReaction(event: GenericMessageReactionEvent) {
        val emoteName = event.reactionEmote.name
        val newDirection = SnakeDirection.values().find { it.emote == emoteName } ?: return
        snakeActor.send(SnakeEvent.DirectionEvent(newDirection))
    }

    enum class SnakeDirection(val xa: Int, val ya: Int, val emote: String) {
        LEFT(-1, 0, "\u2B05"),
        UP(0, -1, "\uD83D\uDD3C"),
        DOWN(0, 1, "\uD83D\uDD3D"),
        RIGHT(1, 0, "\u27A1")
    }

    private sealed class SnakeEvent {
        object UpdateEvent : SnakeEvent()
        class DirectionEvent(val newDirection: SnakeDirection) : SnakeEvent()
    }

    private val snakeActor = smartActor<SnakeEvent>(snakeContext, Channel.UNLIMITED, CoroutineStart.LAZY) {
        for (event in this.channel) {
            if (gameState == GameState.DESTROYED) continue
            handleEvent(event)
        }
    }

    override suspend fun start() {
        super.start()

        var startLocation = randomPoint()
        while (startLocation == appleLocation) startLocation = randomPoint()
        snake.add(startLocation)

        updateJob = launch(snakeContext) {
            while (isActive && gameState == GameState.RUNNING) {
                snakeActor.send(SnakeEvent.UpdateEvent)
                delay(UPDATE_SPEED, TimeUnit.SECONDS)
            }
        }
    }

    private suspend fun handleEvent(event: SnakeEvent) {
        when (event) {
            is SnakeEvent.DirectionEvent -> {
                snakeDirection = event.newDirection
            }
            is SnakeEvent.UpdateEvent -> {
                if (currentMessage != null) {
                    val newPoint = snake.first().let { Point(it.x + snakeDirection.xa, it.y + snakeDirection.ya) }

                    snake.add(0, newPoint)

                    if (snake.any { it.x !in MAP_RANGE || it.y !in MAP_RANGE }) {
                        snake.removeAt(0)
                        setContent(embed { render("Oof your snake went outside its cage!") })
                        endGame()
                        return
                    }

                    if (appleLocation == newPoint) {
                        var startLocation = randomPoint()
                        while (startLocation == appleLocation || snake.contains(startLocation)) startLocation = randomPoint()
                        appleLocation = startLocation
                    } else {
                        snake.removeAt(snake.size - 1)
                    }

                    if (snake.map { c1 -> snake.filter { c1 == it }.count() }.any { it > 1 }) {
                        setContent(embed { render("Oof you ran into yourself!") })
                        endGame()
                        return
                    }
                }

                setContent(embed { render() })
            }
        }
    }

    private fun randomPoint() = Point(random.nextInt(MAP_SIZE), random.nextInt(MAP_SIZE))

    private fun AstolfoEmbedBuilder.render(deadReason: String? = null) {
        val dead = deadReason != null
        title = "${member.effectiveName}'s Snake Game - Score: ${snake.size}" + if (dead) " - Dead" else ""
        description = (0 until MAP_SIZE).joinToString(separator = "\n") { y ->
            (0 until MAP_SIZE).joinToString(separator = "") { x ->
                val point = Point(x, y)
                if (point == appleLocation) {
                    "\uD83C\uDF4E"
                } else if (snake.contains(point)) {
                    val index = snake.indexOf(point)
                    if (index == 0) {
                        if (dead) {
                            "\uD83D\uDCA2"
                        } else {
                            "\uD83D\uDD34"
                        }
                    } else {
                        "\uD83D\uDD35"
                    }
                } else {
                    "\u2B1B"
                }
            }
        } + if (dead) "\n**You have died!**\n$deadReason" else ""
    }

    override suspend fun destroy() {
        super.destroy()
        updateJob.cancel()
        snakeActor.closeAndJoin()
    }

}