package xyz.astolfo.astolfocommunity.modules.`fun`

import com.github.natanbc.reliqua.request.PendingRequest
import org.jsoup.Jsoup
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import xyz.astolfo.astolfocommunity.lib.random
import xyz.astolfo.astolfocommunity.lib.web
import xyz.astolfo.astolfocommunity.lib.webJson
import xyz.astolfo.astolfocommunity.lib.words
import xyz.astolfo.astolfocommunity.modules.ModuleBase
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import java.math.BigInteger
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

class FunModule(application: AstolfoCommunityApplication) : ModuleBase("Fun") {

    private val random = Random()

    private val osuSubModule = OsuSubModule(application)
    private val imageSubModule = ImageSubModule(application)

    override fun ModuleBuilder.create() {
        osuSubModule.createHelper(this)
        imageSubModule.createHelper(this)
        command("advice") {
            action {
                embed("\uD83D\uDCD6 ${webJson<Advice>("http://api.adviceslip.com/advice").await().slip!!.advice}").queue()
            }
        }
        command("coinflip", "flip", "coin") {
            action {
                val flipMessage = embed("Flipping a coin for you...").send().sendCached()
                flipMessage.editMessage(embed("Coin landed on **${if (random.nextBoolean()) "Heads" else "Tails"}**"), 1L)
            }
        }
        command("thankyou") {
            val responses = listOf(
                    "No problem! I hope I can continue in this guild and providing my services",
                    "I hope to continue being of any help in this guild"
            )
            action {
                if (random.nextInt(100) < 25) {
                    embed("No, thank you! Here's **10 credits** for your generosity.").queue()
                    val profile = this.profile
                    profile.credits += 10
                    this.profile = profile
                } else {
                    embed(responses.random(random)).queue()
                }
            }
        }
        command("roll", "die", "dice") {
            val random = Random()

            val ONE = BigInteger.valueOf(1)
            val SIX = BigInteger.valueOf(6)

            action {
                val parts = args.words()
                val (bound1, bound2) = when (parts.size) {
                    0 -> ONE to SIX
                    1 -> {
                        val to = parts[0].toBigIntegerOrNull()
                        (to?.signum() ?: 1).toBigInteger() to to
                    }
                    2 -> parts[0].toBigIntegerOrNull() to parts[1].toBigIntegerOrNull()
                    else -> {
                        errorEmbed("Invalid roll format! Accepted Formats: *<max>*, *<min> <max>*").queue()
                        return@action
                    }
                }

                if (bound1 == null || bound2 == null) {
                    errorEmbed("Only whole numbers are allowed for bounds!").queue()
                    return@action
                }

                val lowerBound = bound1.min(bound2)
                val upperBound = bound1.max(bound2)

                val diffBound = upperBound - lowerBound

                var randomNum: BigInteger
                do {
                    randomNum = BigInteger(diffBound.bitLength(), random)
                } while (randomNum < BigInteger.ZERO || randomNum > diffBound)

                randomNum += lowerBound

                val rollingMessage = embed(":game_die: Rolling a dice for you...").send().sendCached()
                rollingMessage.editMessage(embed("Dice landed on **$randomNum**"), 1)
            }
        }
        command("8ball") {
            val random = Random()
            val responses = arrayOf("It is certain", "You may rely on it", "Cannot predict now", "Yes", "Reply hazy try again", "Yes definitely", "My reply is no", "Better not tell yo now", "Don't count on it", "Most likely", "Without a doubt", "As I see it, yes", "Outlook not so good", "Outlook good", "My sources say no", "Signs point to yes", "Very doubtful", "It is decidedly so", "Concentrate and ask again")
            action {
                val question = args
                if (question.isBlank()) {
                    embed(":exclamation: Make sure to ask a question next time. :)").queue()
                    return@action
                }
                embed {
                    title(":8ball: 8 Ball")
                    field("Question", question, false)
                    field("Answer", responses[random.nextInt(responses.size)], false)
                }.queue()
            }
        }
        command("csshumor", "cssjoke", "cssh") {
            action {
                embed("```css" +
                        "\n${Jsoup.parse(web("https://csshumor.com/").await()).select(".crayon-code").text()}" +
                        "\n```").queue()
            }
        }
        command("dadjoke", "djoke", "dadjokes", "djokes") {
            action {
                embed("\uD83D\uDCD6 **Dadjoke:** ${webJson<DadJoke>("https://icanhazdadjoke.com/").await().joke!!}").queue()
            }
        }
        createGameCommands()
    }

}

class Advice(val slip: AdviceSlip?) {
    inner class AdviceSlip(val advice: String?, @Suppress("unused") val slip_id: String?)
}

class Cat(val file: String?)
class Neko(val neko: String?)
class DadJoke(val id: String?, val status: Int?, var joke: String?)

suspend inline fun <E> PendingRequest<E>.await() = suspendCoroutine<E> { cont ->
    async({ cont.resume(it) }, { cont.resumeWithException(it) })
}