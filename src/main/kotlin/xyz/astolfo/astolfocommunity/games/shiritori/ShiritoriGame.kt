package xyz.astolfo.astolfocommunity.games.shiritori

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import org.springframework.core.io.ClassPathResource
import xyz.astolfo.astolfocommunity.games.*
import xyz.astolfo.astolfocommunity.lib.jda.builders.eventListenerBuilder
import xyz.astolfo.astolfocommunity.lib.jda.embed
import xyz.astolfo.astolfocommunity.lib.jda.embedRaw
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import xyz.astolfo.astolfocommunity.lib.smartActor
import java.lang.Math.max
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.streams.toList


class ShiritoriGame(member: Member, channel: TextChannel, private val difficulty: Difficulty) : GroupGame(member, channel, SinglePlayerMode.BOT) {

    companion object {
        private val shiritoriContext = newFixedThreadPoolContext(10, "Shiritori")

        val validWordList: List<String>
        private val random = Random()

        init {
            val wordRegex = Regex("\\w+")
            validWordList = ClassPathResource("twl06.txt").inputStream.bufferedReader().use { it.lines().toList() }
                    .filter { it.isNotBlank() && it.matches(wordRegex) && it.length >= 4 }
                    .sorted()
        }
    }

    enum class Difficulty(val responseTime: Long, val wordLengthRange: IntRange, val startingScore: Int) {
        EASY(15, 4..6, 100),
        NORMAL(10, 5..8, 100),
        HARD(8, 5..Int.MAX_VALUE, 100),
        IMPOSSIBLE(5, 6..Int.MAX_VALUE, 100)
    }

    private val wordPool = validWordList.filter { it.length in difficulty.wordLengthRange }

    private val _usedWords = mutableListOf<String>()
    val usedWords: List<String> = _usedWords
    var startLetter = ('a'..'z').toList().let { it[random.nextInt(it.size)] }
        private set
    private var lastWordTime = 0L
    private var infoMessage: CachedMessage? = null
    private var thinkingMessage: CachedMessage? = null
    private var scoreboardMessage: CachedMessage? = null
    private var yourTurnMessage: CachedMessage? = null

    private var computerDelay: Job? = null

    private var turnId = 0
    private val playerMap = mutableMapOf<Long, ShiritoriPlayer>()
    val currentTurn
        get() = playerMap[players[turnId]]!!

    private val jdaListener = eventListenerBuilder<MessageReceivedEvent>(shiritoriContext) {
        if (event.author.isBot || !players.contains(event.author.idLong) || event.channel.idLong != channel.idLong) return@eventListenerBuilder

        shiritoriActor.send(ShiritoriJdaMessageEvent(event))
    }

    private val shiritoriActor = smartActor<ShiritoriMessageEvent>(shiritoriContext, Channel.UNLIMITED, CoroutineStart.LAZY) {
        for (event in this.channel) {
            if (gameState == GameState.DESTROYED) continue
            playerMap[event.member.user.idLong]?.onMessage(event)
        }
    }

    override suspend fun start() {
        super.start()

        members.forEach { playerMap[it.user.idLong] = ShiritoriPlayer(this, it, difficulty.startingScore.toDouble()) }

        infoMessage = channel.sendMessage(embed {
            title("Astolfo Shiritori")
            description = "When the game *starts* you are given a **random letter**. You must pick a word *beginning* with that *letter*." +
                    "After you pick your word, the bot will pick another word starting with the **last part** of your *word*." +
                    "Then you will play against the bot till your **score reaches zero**, starting at *100*." +
                    "First one to **zero points wins**!" +
                    "\n" +
                    "\n__**Words must:**__" +
                    "\n- Be in the *dictionary*" +
                    "\n- Have at least ***4*** *letters*" +
                    "\n- Have *not* been *used*" +
                    "\n" +
                    "\n__**Points:**__" +
                    "\n- *Length Bonus:* Number of letters *minus four*" +
                    "\n- *Speed Bonus:* **15 seconds** minus time it took"
        }).sendCached()
        scoreboardMessage = channel.sendMessage("**${currentTurn.member.effectiveName}:** You may go first, can be any word that is 4 letters or longer and starting with the letter **$startLetter**!").sendCached()
        lastWordTime = System.currentTimeMillis()

        channel.jda.addEventListener(jdaListener)
    }

    override suspend fun leave(member: Member) {
        if(players.size < 2) {
            endGame()
            return
        }
        val currentTurn = playerMap[member.user.idLong]!!
        if(currentTurn.member == member) {
            yourTurnMessage?.delete()
            turnId++
            if(turnId >= players.size) {
                turnId = 0
            }
            yourTurnMessage = channel.sendMessage("**${this.currentTurn.member.effectiveName}:** It is now your turn now since ${currentTurn.member.effectiveName} has left.").sendCached()
        }
        playerMap.remove(member.user.idLong)?.dispose()
    }

    override suspend fun destroy() {
        super.destroy()
        channel.jda.removeEventListener(jdaListener)

        shiritoriActor.closeAndJoin()

        infoMessage?.delete()
        scoreboardMessage?.delete()
        thinkingMessage?.delete()
        computerDelay?.cancel()
        yourTurnMessage?.delete()

        playerMap.forEach { _, value -> value.dispose()  }
        playerMap.clear()
    }

    suspend fun makeMove(wordInput: String) {
        yourTurnMessage?.delete()
        _usedWords.add(wordInput)

        val timeLeft = max(0, 15 * 1000 - (System.currentTimeMillis() - lastWordTime))

        val lengthBonus = max(0, wordInput.length - 4)
        val timeBonus = timeLeft / 1000.0
        val moveScore = lengthBonus + timeBonus
        currentTurn.score -= moveScore

        val score = currentTurn.score
        if (score <= 0) {
            channel.sendMessage(embedRaw("${currentTurn.member.effectiveName} has won!")).queue()
            endGame()
            return
        }

        startLetter = wordInput.last()
        lastWordTime = System.currentTimeMillis()

        scoreboardMessage?.delete()
        scoreboardMessage = channel.sendMessage(embedRaw {
            description = "*Word:* **$wordInput**"
            field("Breakdown", "*Time:* **${Math.ceil(timeBonus).toInt()}** (*${Math.ceil(timeLeft / 1000.0).toInt()}s left*)" +
                    "\n*Length:* **$lengthBonus** (*${wordInput.length} letters*)" +
                    "\n*Total:* **${Math.ceil(moveScore).toInt()}**", true)
            field("Scores", members.joinToString(separator = "\n") { "*${it.effectiveName}:* **${Math.ceil(playerMap[it.user.idLong]!!.score).toInt()}**" }, true)
        }).sendCached()

        if (turnId + 1 >= players.size) turnId = 0
        else turnId++

        if (currentTurn.member.user.idLong == channel.guild.selfMember.user.idLong) {
            thinkingMessage = channel.sendMessage("Thinking...").sendCached()
            val chosenWord = wordPool.filter { it.startsWith(startLetter) && !usedWords.contains(it) }.let { it[(random.nextDouble().pow(2) * it.size).toInt()] }
            computerDelay?.cancel()
            computerDelay = launch(shiritoriContext) {
                delay(((1 - random.nextDouble().pow(2)) * difficulty.responseTime).toLong(), TimeUnit.SECONDS)
                thinkingMessage?.delete()
                thinkingMessage = null
                channel.sendMessage(chosenWord).queue()
                shiritoriActor.send(object : ShiritoriMessageEvent {
                    override val message = chosenWord
                    override val member = channel.guild.selfMember
                })
            }
        } else if (members.none { it.user.isBot }) {
            yourTurnMessage = channel.sendMessage("**${currentTurn.member.effectiveName}:** It is now your turn.").sendCached()
        }
    }

}