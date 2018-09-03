package xyz.astolfo.astolfocommunity.modules.`fun`

import com.jagrosh.jdautilities.commons.utils.FinderUtil
import kotlinx.coroutines.experimental.runBlocking
import net.dv8tion.jda.core.entities.Message
import xyz.astolfo.astolfocommunity.commands.SessionListener
import xyz.astolfo.astolfocommunity.games.*
import xyz.astolfo.astolfocommunity.games.shiritori.ShiritoriGame
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.lib.commands.RequestedByElement
import xyz.astolfo.astolfocommunity.lib.commands.captureError
import xyz.astolfo.astolfocommunity.lib.jda.embedRaw
import xyz.astolfo.astolfocommunity.menus.memberSelectionBuilder
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import java.awt.Color

internal fun ModuleBuilder.createGameCommands() {
    command("game") {
        inheritedAction {
            val currentGame = GameHandler[event.channel.idLong, event.author.idLong]
            val game = currentGame?.game ?: return@inheritedAction true
            if (args.equals("stop", true)) {
                if (game is GroupGame && game.players.size >= 2) {
                    // Instead of stopping the game, just leave
                    game.leave0(event.member)
                    embed("You have left the game!").queue()
                    return@inheritedAction false
                }
                // Since the game isnt a group based game and you are the last one, end it
                currentGame.stopGame()
                embed("Current game has stopped!").queue()
            } else {
                errorEmbed("To stop the current game you're in, type **?game stop**").queue()
            }
            false
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
