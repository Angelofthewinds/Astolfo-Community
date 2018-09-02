package xyz.astolfo.astolfocommunity.modules

import com.github.natanbc.reliqua.request.PendingRequest
import com.github.natanbc.weeb4j.image.HiddenMode
import com.github.natanbc.weeb4j.image.NsfwFilter
import com.jagrosh.jdautilities.commons.utils.FinderUtil
import com.oopsjpeg.osu4j.backend.EndpointUsers
import com.oopsjpeg.osu4j.backend.Osu
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import net.dv8tion.jda.core.entities.Message
import org.jsoup.Jsoup
import xyz.astolfo.astolfocommunity.commands.SessionListener
import xyz.astolfo.astolfocommunity.games.*
import xyz.astolfo.astolfocommunity.games.shiritori.ShiritoriGame
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.lib.commands.RequestedByElement
import xyz.astolfo.astolfocommunity.lib.commands.captureError
import xyz.astolfo.astolfocommunity.lib.jda.embedRaw
import xyz.astolfo.astolfocommunity.lib.jda.message
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import xyz.astolfo.astolfocommunity.lib.web
import xyz.astolfo.astolfocommunity.lib.webJson
import xyz.astolfo.astolfocommunity.lib.words
import xyz.astolfo.astolfocommunity.menus.memberSelectionBuilder
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import java.awt.Color
import java.math.BigInteger
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

fun createFunModule() = module("Fun") {
    command("osu") {
        action {
            embed {
                val osuPicture = "https://upload.wikimedia.org/wikipedia/commons/d/d3/Osu%21Logo_%282015%29.png"
                title("Astolfo Osu Integration")
                description = "**sig**  -  generates an osu signature of the user" +
                        "\n**profile**  -  gets user data from the osu api"
                thumbnail = osuPicture
            }.queue()
        }
        command("sig", "s") {
            action {
                val osuUsername = args
                embed {
                    val url = "http://lemmmy.pw/osusig/sig.php?colour=purple&uname=$osuUsername&pp=1"
                    title("Astolfo Osu Signature", url)
                    description = "$osuUsername\'s Osu Signature!"
                    image = url
                }.queue()
            }
        }
        command("profile", "p", "user", "stats") {
            action {
                val osu = Osu.getAPI(application.properties.osu_api_token)
                val user = try {
                    osu.users.query(EndpointUsers.ArgumentsBuilder(args).build())
                } catch (e: Exception) {
                    errorEmbed(":mag: I looked for `$args`, but couldn't find them!" +
                            "\n Try using the sig command instead.").queue()
                    return@action
                }
                embed {
                    val topPlayBeatmap = user.getTopScores(1).get().first().beatmap.get()
                    title("Osu stats for ${user.username}", user.url.toString())
                    description("\nProfile url: ${user.url}" +
                            "\nCountry: **${user.country}**" +
                            "\nGlobal Rank: **#${user.rank} (${user.pp}pp)**" +
                            "\nAccuracy: **${user.accuracy}%**" +
                            "\nPlay Count: **${user.playCount} (Lv${user.level})**" +
                            "\nTop play: **$topPlayBeatmap** ${topPlayBeatmap.url}")
                }.queue()
            }
        }
    }
    command("advice") {
        action {
            embed("\uD83D\uDCD6 ${webJson<Advice>("http://api.adviceslip.com/advice").await().slip!!.advice}").queue()
        }
    }
    command("cat", "cats") {
        val catMutex = Mutex()
        val validCats = mutableListOf<String>()
        val random = Random()
        action {
            val cat = try {
                val newCat = webJson<Cat>("http://aws.random.cat/meow", null).await().file!!
                catMutex.withLock {
                    if (!validCats.contains(newCat)) validCats.add(newCat)
                    if (validCats.size > 500) validCats.removeAt(0) // max of 500 cats in memory
                }
                newCat
            } catch (e: Throwable) {
                // idc
                catMutex.withLock {
                    if (validCats.isEmpty()) return@action
                    validCats.let { it[random.nextInt(it.size)] }
                }
            }
            message(cat).queue()
        }
    }
    command("catgirl", "neko", "catgirls") {
        action {
            message(webJson<Neko>("https://nekos.life/api/neko").await().neko!!).queue()
        }
    }
    command("coinflip", "flip", "coin") {
        val random = Random()
        action {
            val flipMessage = embed("Flipping a coin for you...").send().sendCached()
            flipMessage.editMessage(embed("Coin landed on **${if (random.nextBoolean()) "Heads" else "Tails"}**"), 1L)
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
    command("cyanideandhappiness", "cnh") {
        val random = Random()
        action {
            val r = random.nextInt(4665) + 1
            val imageUrl = Jsoup.parse(web("http://explosm.net/comics/$r/").await())
                    .select("#main-comic").first()
                    .attr("src")
                    .let { if (it.startsWith("//")) "https:$it" else it }
            embed {
                title("Cyanide and Happiness")
                image = imageUrl
            }.queue()
        }
    }
    command("dadjoke", "djoke", "dadjokes", "djokes") {
        action {
            embed("\uD83D\uDCD6 **Dadjoke:** ${webJson<DadJoke>("https://icanhazdadjoke.com/").await().joke!!}").queue()
        }
    }
    command("hug") {
        action {
            val selectedMember = memberSelectionBuilder(args).title("Hug Selection").execute() ?: return@action
            val image = application.weeb4J.imageProvider.getRandomImage("hug", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
            embed {
                description = "${event.author.asMention} has hugged ${selectedMember.asMention}"
                this.image = image.url
                footer("Powered by weeb.sh")
            }.queue()
        }
    }
    command("kiss") {
        action {
            val selectedMember = memberSelectionBuilder(args).title("Kiss Selection").execute() ?: return@action
            val image = application.weeb4J.imageProvider.getRandomImage("kiss", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
            embed {
                description = "${event.author.asMention} has kissed ${selectedMember.asMention}"
                this.image = image.url
                footer = "Powered by weeb.sh"
            }.queue()
        }
    }
    command("slap") {
        action {
            val selectedMember = memberSelectionBuilder(args).title("Slap Selection").execute() ?: return@action
            val image = application.weeb4J.imageProvider.getRandomImage("slap", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
            embed {
                description("${event.author.asMention} has slapped ${selectedMember.asMention}")
                this.image = image.url
                footer("Powered by weeb.sh")
            }.queue()
        }
    }
    command("game") {
        inheritedAction {
            val currentGame = GameHandler[event.channel.idLong, event.author.idLong]
            if (currentGame.game != null) {
                if (args.equals("stop", true)) {
                    currentGame.stopGame()
                    embed("Current game has stopped!").queue()
                } else {
                    errorEmbed("To stop the current game you're in, type `?game stop`").queue()
                }
                false
            } else true
        }
        action {
            embed {
                title("Astolfo Game Help")
                description("**snake**  -  starts a game of snake!\n" +
                        "**tetris** - starts a game of tetris!\n" +
                        "**shiritori [easy/normal/hard/impossible]** - starts a game of shiritori!\n\n" +
                        "**stop** - stops the current game your playing")
            }.queue()
        }
        command("snake") {
            action {
                embed("Starting the game of snake...").queue()
                startGame(SnakeGame(event.member, event.channel))
            }
        }
        command("tetris") {
            action {
                embed("Starting the game of tetris...").queue()
                startGame(TetrisGame(event.member, event.channel))
            }
        }
        command("akinator") {
            action {
                embed("Starting the akinator...").queue()
                startGame(AkinatorGame(event.member, event.channel))
            }
        }
        command("shiritori") {
            action {
                if (GameHandler.getAllInChannel(event.channel.idLong).any { it.game is ShiritoriGame }) {
                    errorEmbed("Only one game of Shiritori is allowed per channel!").queue()
                    return@action
                }
                val difficulty = if (args.isBlank()) ShiritoriGame.Difficulty.NORMAL
                else selectionBuilder<ShiritoriGame.Difficulty>()
                        .results(ShiritoriGame.Difficulty.values().filter { it.name.contains(args, true) })
                        .noResultsMessage("Unknown Difficulty!")
                        .resultsRenderer { it.name }
                        .description("Type the number of the difficulty you want.")
                        .execute() ?: return@action
                embed("Starting the game of Shiritori with difficulty **${difficulty.name.toLowerCase().capitalize()}**...").queue()
                startGame(ShiritoriGame(event.member, event.channel, difficulty))
            }
        }
    }
    command("group") {
        command("invite") {
            description("Invite people to a group and play games together!")
            usage("[user]")

            action {
                val group = GroupHandler.create(event.guild.idLong, event.author.idLong)
                if (group.leaderId != event.author.idLong) {
                    errorEmbed("You must be the leader of the group to invite!").queue()
                    return@action
                }
                val selectedUser = memberSelectionBuilder(args)
                        .description("Type the number of the member you want to invite")
                        .execute() ?: return@action

                if (selectedUser.user == event.author) {
                    errorEmbed("You cannot invite yourself!").queue()
                    return@action
                }

                if (GroupHandler[event.guild.idLong, selectedUser.user.idLong] != null) {
                    errorEmbed("That user is already in a group!").queue()
                    return@action
                }

                lateinit var invitedMessage: Message
                val inviterMessage = embed {
                    description = "**${selectedUser.effectiveName}** has been invited. Waiting for them to accept..."
                    color = Color.YELLOW
                }.sendCached()

                InviteHandler.invite(event.author.idLong, selectedUser.user.idLong, event.guild.idLong, {
                    runBlocking(SessionListener.commandContext + RequestedByElement(event.author)) {
                        inviterMessage.contentEmbed = embed("**${selectedUser.effectiveName}** has been invited. Accepted!")
                    }
                    invitedMessage.editMessage(embedRaw("Invite for group in **${event.guild.name}** accepted!")).queue()
                }) {
                    runBlocking(SessionListener.commandContext + RequestedByElement(event.author)) {
                        inviterMessage.contentEmbed = errorEmbed("**${selectedUser.effectiveName}** has been invited. Expired!")
                    }
                    invitedMessage.editMessage(embedRaw("Invite for group in **${event.guild.name}** has expired!")).queue()
                }
                selectedUser.user.openPrivateChannel().queue { privateChannel ->
                    privateChannel.sendMessage(embedRaw("**${event.member.effectiveName}** has invited you to join a group in the guild **${event.guild.name}**." +
                            " To accept, go to that guild and type **${guildSettings.getEffectiveGuildPrefix(application)}group accept ${event.member.effectiveName}**")).queue {
                        invitedMessage = it
                    }
                }
            }
        }
        command("accept") {
            action {
                if (GroupHandler[event.guild.idLong, event.author.idLong] != null) {
                    errorEmbed("You are already in a group!").queue()
                    return@action
                }

                val invites = InviteHandler[event.author.idLong, event.guild.idLong]
                val selectedUser = memberSelectionBuilder(args)
                        .results(FinderUtil.findMembers(args, event.guild).filter { member ->
                            invites.any { (key, _) -> key.inviterId == member.user.idLong }
                        })
                        .noResultsMessage("No invites found!")
                        .description("Type the number of the invite you want to accept")
                        .execute() ?: return@action

                val invite = invites.values.first { it.inviterId == selectedUser.user.idLong }!!
                InviteHandler.remove(selectedUser.user.idLong, event.author.idLong, event.guild.idLong)
                invite.acceptCallback()
                if (GroupHandler.join(event.guild.idLong, selectedUser.user.idLong, event.author.idLong))
                    embed("You accepted the invite from **${selectedUser.effectiveName}** and you have joined their group.").queue()
                else errorEmbed("Group **${selectedUser.effectiveName}** no longer exists!").queue()
            }
        }
        command("list") {
            action {
                val group = GroupHandler[event.guild.idLong, event.author.idLong]
                if (group == null) {
                    errorEmbed("You are not currently in a group!").queue()
                    return@action
                }
                embed {
                    title = "${event.guild.getMemberById(group.leaderId).effectiveName}'s Group"
                    description = group.members.joinToString("\n") { event.guild.getMemberById(it).effectiveName }
                }.queue()
            }
        }
        command("leave") {
            action {
                val group = GroupHandler[event.guild.idLong, event.author.idLong]
                if (group == null) {
                    errorEmbed("You are not currently in a group!").queue()
                    return@action
                }
                val originalLeader = group.leaderId
                GroupHandler.leave(event.guild.idLong, event.author.idLong)
                if (originalLeader != group.leaderId) {
                    if (group.leaderId == -1L) {
                        errorEmbed("You have left and the group was disbanded!").queue()
                    } else {
                        embed("You have left and the leadership went to **${event.guild.getMemberById(group.leaderId).effectiveName}**!").queue()
                    }
                } else {
                    embed("You have left the group **${event.guild.getMemberById(group.leaderId).effectiveName}**.").queue()
                }
            }
        }
    }
}

private suspend fun CommandScope.startGame(game: Game) = captureError<IllegalStateException, Unit> {
    GameHandler.startGame(event.channel.idLong, event.author.idLong, game)
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