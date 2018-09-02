package xyz.astolfo.astolfocommunity.games

import com.markozajc.akiwrapper.Akiwrapper
import com.markozajc.akiwrapper.AkiwrapperBuilder
import com.markozajc.akiwrapper.core.entities.Guess
import com.markozajc.akiwrapper.core.entities.Question
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.withContext
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import xyz.astolfo.astolfocommunity.lib.Timeout
import xyz.astolfo.astolfocommunity.lib.commands.RequestedByElement
import xyz.astolfo.astolfocommunity.lib.flatten
import xyz.astolfo.astolfocommunity.lib.jda.builders.eventListenerBuilder
import xyz.astolfo.astolfocommunity.lib.jda.embed
import xyz.astolfo.astolfocommunity.lib.jda.errorEmbed
import xyz.astolfo.astolfocommunity.lib.levenshteinDistance
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import xyz.astolfo.astolfocommunity.lib.smartActor
import java.lang.Math.max
import java.util.concurrent.TimeUnit


class AkinatorGame(member: Member, channel: TextChannel) : Game(member, channel) {

    companion object {
        private val akinatorContext = newFixedThreadPoolContext(5, "Akinator")

        private val questionResponses = Answer.values()
        private val guessResponses = arrayOf(Answer.YES, Answer.NO)
    }

    enum class Answer(val akiAnswer: Akiwrapper.Answer, vararg val responses: String) {
        YES(Akiwrapper.Answer.YES, "Y", "yes"),
        NO(Akiwrapper.Answer.NO, "N", "no"),
        DONT_KNOW(Akiwrapper.Answer.DONT_KNOW, "DK", "dont know", "don't know"),
        PROBABLY(Akiwrapper.Answer.PROBABLY, "P", "probably"),
        PROBABLY_NOT(Akiwrapper.Answer.PROBABLY_NOT, "PN", "probably not"),
        UNDO(Akiwrapper.Answer.PROBABLY_NOT, "U", "B", "undo", "back");

        companion object {
            private val reverseLookUp = values().map { answer ->
                answer.responses.associate { response ->
                    response to answer
                }
            }.flatten()

            fun findBestAnswer(responses: Array<Answer>, content: String): Triple<Answer, String, Int> {
                val distanceMap = reverseLookUp.filterValues { responses.contains(it) }
                        .mapValues { it.key.levenshteinDistance(content, true) }

                val bestMatch = distanceMap.minBy { it.value }!!

                return Triple(reverseLookUp[bestMatch.key]!!, bestMatch.key, bestMatch.value)
            }
        }
    }

    private val jdaListener = eventListenerBuilder<MessageReceivedEvent>(akinatorContext) {
        if (event.author.idLong != member.user.idLong || event.channel.idLong != channel.idLong) return@eventListenerBuilder

        akinatorActor.send(AkinatorEvent.MessageEvent(event.message.contentRaw))
    }

    private lateinit var akiWrapper: Akiwrapper
    private var akiState = State.ASKING
    private val hasGuessed = mutableListOf<String>()

    private var timeout = Timeout(akinatorContext, 5, TimeUnit.MINUTES) {
        akinatorActor.send(AkinatorEvent.TimeoutEvent)
    }

    private val questionMessages = mutableListOf<QuestionMessage>()
    private var errorMessage: CachedMessage? = null

    private enum class State {
        ASKING,
        GUESS
    }

    private sealed class AkinatorEvent {
        class MessageEvent(val message: String) : AkinatorEvent()
        object NoMoreQuestions : AkinatorEvent()
        object NextQuestion : AkinatorEvent()
        object TimeoutEvent : AkinatorEvent()
    }

    private val akinatorActor = smartActor<AkinatorEvent>(context = akinatorContext, capacity = Channel.UNLIMITED) {
        withContext(RequestedByElement(member.user)) {
            for (event in this.channel) {
                if (gameState == GameState.DESTROYED) continue
                handleEvent(event)
            }
        }
    }

    private suspend fun handleEvent(event: AkinatorEvent) {
        when (event) {
            is AkinatorEvent.MessageEvent -> {
                timeout.reset() // reset timeout (this is only if there's no activity happening
                // clean up last error message (since its no longer important)
                errorMessage?.delete()

                val validResponses = if (akiState == State.GUESS) guessResponses else questionResponses
                // go through all possible response choices and select the most similar
                val (matchedAnswer, matchedText, matchedScore) = Answer.findBestAnswer(validResponses, event.message)
                // check if response is close enough to closest possible result
                if (matchedScore > max(1, matchedText.length / 3)) {
                    errorMessage = channel.sendMessage(errorEmbed("Unknown answer!")).sendCached()
                    return
                }
                // stop timeout since we got a valid response
                timeout.stop()
                // Do stuff with answer
                var bestGuess: Guess? = null
                when (akiState) {
                    State.GUESS -> {
                        // If the bot is guessing
                        if (matchedAnswer == Answer.YES) {
                            // Guess was correct. end game
                            channel.sendMessage(embed("Nice! Im glad I got it correct.")).queue()
                            endGame()
                            return
                        } else {
                            // Guess was wrong
                            // Find next guess
                            bestGuess = getGuess()
                            if (bestGuess == null) {
                                // No more guesses left
                                if(akiWrapper.currentQuestion == null){
                                    handleEvent(AkinatorEvent.NoMoreQuestions)
                                    return
                                }else {
                                    // Still has questions to ask (Only can guess normally if next question is valid)
                                    channel.sendMessage("Aww, here are some more questions to narrow the result.").queue()
                                    akiState = State.ASKING
                                }
                            }
                        }
                    }
                    State.ASKING -> {
                        // bot is asking a question to user
                        if (matchedAnswer == Answer.UNDO) {
                            // undo and remove current question
                            if(questionMessages.size >= 2) {
                                akiWrapper.undoAnswer()
                                questionMessages.removeAt(0).destroy()
                                questionMessages[0].update(null)
                                timeout.reset()
                            }else{
                                errorMessage = channel.sendMessage(errorEmbed("Nothing left to undo!")).sendCached()
                            }
                            return
                        }
                        questionMessages[0].update(matchedAnswer)
                        // send result back to akinator
                        if (akiWrapper.answerCurrentQuestion(matchedAnswer.akiAnswer) == null) {
                            // No more questions left
                            handleEvent(AkinatorEvent.NoMoreQuestions)
                            return
                        }
                        // populate best guess with guess after the answer
                        bestGuess = getGuess()
                    }
                }
                if (bestGuess != null) {
                    // If the bot has a good enough guess, ask it
                    hasGuessed += bestGuess.name
                    akiState = State.GUESS
                    ask(bestGuess)
                    return
                }
                // If no conditions met just ask the next question
                handleEvent(AkinatorEvent.NextQuestion)
            }
            is AkinatorEvent.NextQuestion -> {
                // Get the next question
                val question = akiWrapper.currentQuestion
                if (question == null) {
                    // If somehow the question is null, usually happens when it runs out of questions to ask and guess score is too low
                    handleEvent(AkinatorEvent.NoMoreQuestions)
                    return
                }
                // ask it and start timeout
                val message = QuestionMessage(question)
                questionMessages.add(0, message)
                message.send()
                timeout.reset()
            }
            is AkinatorEvent.NoMoreQuestions -> {
                // No more questions, enter the iterating state
                akiState = State.GUESS
                // Get best guess else get guess thats above 50%
                val bestGuess = akiWrapper.getGuessesAboveProbability(0.25)
                        .filter { !hasGuessed.contains(it.name) }
                        .sortedByDescending { it.probability }.firstOrNull()

                if (bestGuess == null) {
                    // No more guesses, defeat
                    channel.sendMessage(embed("Aww, you have defeated me!")).queue()
                    endGame()
                    return
                }
                // Ask the next guess
                hasGuessed += bestGuess.name
                ask(bestGuess)
            }
            is AkinatorEvent.TimeoutEvent -> {
                // Timeout met
                channel.sendMessage(errorEmbed("Akinator automatically ended since you didnt repond in time!")).queue()
                endGame()
            }
        }
    }

    private suspend fun ask(bestGuess: Guess) {
        // ask guess and start timeout
        channel.sendMessage(embed {
            description = "Is **${bestGuess.name}** correct?\n(Answer: yes/no)"
            try {
                image = bestGuess.image.toString()
            } catch (e: IllegalArgumentException) { // ignore
            }
        }).queue()
        timeout.reset()
    }

    /**
     * Get guess with probability greater then 85% and grab guess with highest probability
     */
    private fun getGuess() = akiWrapper.getGuessesAboveProbability(0.85)
            .filter { !hasGuessed.contains(it.name) }
            .sortedByDescending { it.probability }.firstOrNull()

    override suspend fun start() {
        super.start()

        // connect to server and start game
        akiWrapper = AkiwrapperBuilder().build()
        channel.jda.addEventListener(jdaListener)
        // start the game off by asking a question
        akinatorActor.send(AkinatorEvent.NextQuestion)
    }

    override suspend fun destroy() {
        super.destroy()
        channel.jda.removeEventListener(jdaListener)
        akinatorActor.closeAndJoin()

        errorMessage?.delete()
        timeout.stop()

        questionMessages.clear()
        errorMessage = null
    }

    inner class QuestionMessage(private val question: Question) {

        private lateinit var message: CachedMessage

        suspend fun send() {
            message = channel.sendMessage(constructMessage(null)).sendCached()
        }

        suspend fun update(answer: Answer?) {
            message.contentEmbed = constructMessage(answer)
        }

        private suspend fun constructMessage(answer: Answer?): MessageEmbed {
            val stringBuilder = StringBuilder("**#${question.step + 1}** ${question.question}\n(Answer: ")
            fun appendAnswer(associatedAnswer: Answer, display: String){
                val isAnswer = answer == associatedAnswer
                if(isAnswer) stringBuilder.append("**")
                stringBuilder.append(display)
                if(isAnswer) stringBuilder.append("**")
            }
            appendAnswer(Answer.YES, "yes")
            stringBuilder.append('/')
            appendAnswer(Answer.NO, "no")
            stringBuilder.append('/')
            appendAnswer(Answer.DONT_KNOW, "don't know")
            stringBuilder.append('/')
            appendAnswer(Answer.PROBABLY, "probably")
            stringBuilder.append('/')
            appendAnswer(Answer.PROBABLY_NOT, "probably not")
            stringBuilder.append(" or undo)")
            return embed(stringBuilder.toString())
        }

        fun destroy(){
            message.delete()
        }

    }

}