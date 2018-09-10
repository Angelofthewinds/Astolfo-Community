package xyz.astolfo.astolfocommunity.commands

import io.sentry.Sentry
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.AstolfoPermissionUtils
import xyz.astolfo.astolfocommunity.lib.commands.CommandDataImpl
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.lib.hasPermission
import xyz.astolfo.astolfocommunity.lib.jda.errorEmbed
import xyz.astolfo.astolfocommunity.lib.splitFirst
import xyz.astolfo.astolfocommunity.modules.Module
import java.util.concurrent.TimeUnit

class SessionListener(
        val application: AstolfoCommunityApplication,
        val channelListener: ChannelListener
) {

    companion object {
        internal val sessionContext = newFixedThreadPoolContext(10, "Session Processor")
        internal val commandContext = newFixedThreadPoolContext(20, "Command Processor")
    }

    private var destroyed = false

    suspend fun addMessage(guildMessageData: GuildListener.GuildMessageData) = sessionActor.send(SessionEvent.MessageEvent(guildMessageData))
    suspend fun addCommand(guildMessageData: GuildListener.GuildMessageData) = sessionActor.send(SessionEvent.CommandEvent(guildMessageData))

    private sealed class SessionEvent {
        class MessageEvent(val guildMessageData: GuildListener.GuildMessageData) : SessionEvent()
        class CommandEvent(val guildMessageData: GuildListener.GuildMessageData) : SessionEvent()
        object CleanUp : SessionEvent()
    }

    private val sessionActor = actor<SessionEvent>(context = sessionContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            if (destroyed) continue
            try {
                handleEvent(event)
            } catch (e: Throwable) {
                e.printStackTrace()
                Sentry.capture(e)
            }
        }
        handleEvent(SessionEvent.CleanUp)
    }

    private var currentSession: CommandSession? = null
    private var sessionJob: Job? = null

    private suspend fun handleEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.CleanUp -> {
                currentSession?.destroy()
                sessionJob?.cancelAndJoin()
                currentSession = null
                sessionJob = null
            }
            is SessionEvent.MessageEvent -> {
                val currentSession = this.currentSession ?: return
                // TODO add rate limit
                //if (!processRateLimit(event)) return@launch
                val guildMessageData = event.guildMessageData
                val jdaEvent = guildMessageData.messageReceivedEvent
                withContext(CommandDataImpl(
                        jdaEvent,
                        currentSession,
                        currentSession.commandPath,
                        jdaEvent.message.contentRaw,
                        guildMessageData.timeIssued
                )) {
                    val commandScope = CommandScope(application)
                    if (currentSession.onMessageReceived(commandScope)) {
                        // If all the response listeners allowed the "command" to run
                        handleEvent(SessionEvent.CleanUp)
                    }
                }
            }
            is SessionEvent.CommandEvent -> {
                val guildMessageData = event.guildMessageData
                val jdaEvent = guildMessageData.messageReceivedEvent
                val member = jdaEvent.member
                val channel = jdaEvent.channel

                val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(jdaEvent.guild.idLong)
                val channelBlacklisted = guildSettings.blacklistedChannels.contains(channel.idLong)

                val rawContent = jdaEvent.message.contentRaw!!
                val prefixMatched = guildMessageData.prefixMatched
                val isMention = prefixMatched.startsWith("<@")

                var commandMessage = rawContent.substring(prefixMatched.length).trim()

                var commandNodes = resolvePath(commandMessage)

                var checkedRateLimit = false

                if (commandNodes == null) {
                    if (channelBlacklisted) return // Ignore chat bot if channel is blacklisted
                    if (!isMention) return
                    if (!checkPatreonBot(guildMessageData)) return
                    if (!processRateLimit(jdaEvent)) return
                    checkedRateLimit = true
                    // Not a command but rather a chat bot message
                    if (commandMessage.isEmpty()) {
                        channel.sendMessage("Hi :D").queue()
                        return
                    }
                    if (commandMessage.contains("prefix", true)) {
                        channel.sendMessage("Yahoo! My prefix in this guild is **${guildSettings.getEffectiveGuildPrefix(application)}**!").queue()
                        return
                    }
                    val chatBotManager = channelListener.guildListener.messageListener.chatBotManager

                    val response = chatBotManager.process(member, commandMessage)
                    if (response.type == ChatResponse.ResponseType.COMMAND) {
                        commandMessage = response.response
                        commandNodes = resolvePath(commandMessage)
                        if (commandNodes == null) return // cancel the command
                    } else {
                        channel.sendMessage(response.response).queue()
                        return
                    }
                } else {
                    if (!checkPatreonBot(guildMessageData)) return
                }
                // Only allow Admin module if blacklisted
                if (channelBlacklisted) {
                    val module = commandNodes.first
                    if (!module.name.equals("Admin", true)) return
                }

                if (!checkedRateLimit && !processRateLimit(jdaEvent)) return

                // Process Command
                application.statsDClient.incrementCounter("commands_executed")

                if (!channel.hasPermission(Permission.MESSAGE_EMBED_LINKS)) {
                    channel.sendMessage("Please enable **embed links** to use Astolfo commands.").queue()
                    return
                }

                val module = commandNodes.first

                if (guildMessageData.withCommandScope(InheritedCommandSession(commandMessage), "", commandMessage) { scope ->
                            !module.inheritedActions.all { it.invoke(scope) }
                        }) return

                // Go through all the nodes in the command path and check permissions/actions
                for ((command, commandPath, commandContent) in commandNodes.second) {
                    // PERMISSIONS
                    val permission = command.permission

                    var hasPermission: Boolean? = if (member.hasPermission(Permission.ADMINISTRATOR)) true else null
                    // Check discord permission if the member isn't a admin already
                    if (hasPermission != true && permission.permissionDefaults.isNotEmpty())
                        hasPermission = member.hasPermission(channel, *permission.permissionDefaults)
                    // Check Astolfo permission if discord permission didn't already grant permissions
                    if (hasPermission != true)
                        AstolfoPermissionUtils.hasPermission(member, channel, application.astolfoRepositories.getEffectiveGuildSettings(jdaEvent.guild.idLong).permissions, permission)?.let { hasPermission = it }

                    if (hasPermission == false) {
                        channel.sendMessage(errorEmbed("You are missing the astolfo **${permission.path}**${if (permission.permissionDefaults.isNotEmpty())
                            " or discord ${permission.permissionDefaults.joinToString(", ") { "**${it.getName()}**" }}" else ""} permission(s)"))
                                .queue()
                        return
                    }

                    // INHERITED ACTIONS
                    if (guildMessageData.withCommandScope(InheritedCommandSession(commandPath), commandPath, commandContent) { scope ->
                                !command.inheritedActions.all { it.invoke(scope) }
                            }) return
                }
                // COMMAND ENDPOINT
                val (command, commandPath, commandContent) = commandNodes.second.last()

                suspend fun runNewSession() {
                    handleEvent(SessionEvent.CleanUp)
                    application.statsDClient.incrementCounter("commandExecuteCount", "command:$commandPath")
                    currentSession = CommandSessionImpl(commandPath)
                    sessionJob = launch(commandContext) {
                        guildMessageData.withCommandScope(currentSession!!, commandPath, commandContent) { scope ->
                            withTimeout(1, TimeUnit.MINUTES) {
                                command.varient.action(scope)
                            }
                        }
                    }
                }

                val currentSession = this.currentSession

                // Checks if command is the same as the previous, if so, check if its a follow up response
                if (currentSession != null && currentSession.commandPath.equals(commandPath, true)) {
                    if (guildMessageData.withCommandScope(currentSession, commandPath, commandContent) {
                                currentSession.onMessageReceived(it)
                            }) runNewSession()
                } else {
                    runNewSession()
                }
            }
        }
    }

    private suspend fun <T> GuildListener.GuildMessageData.withCommandScope(session: CommandSession, commandPath: String, commandContent: String, block: suspend (CommandScope) -> T): T =
            withContext(CommandDataImpl(
                    messageReceivedEvent,
                    session,
                    commandPath,
                    commandContent,
                    timeIssued
            )) {
                val commandScope = CommandScope(application)
                block(commandScope)
            }

    private suspend fun checkPatreonBot(data: GuildListener.GuildMessageData): Boolean {
        if (!application.properties.patreon_bot) return true
        val staffIds = application.staffMemberIds
        if (staffIds.contains(data.messageReceivedEvent.author.idLong)) return true
        val donorGuild = application.donationManager.getByMember(data.messageReceivedEvent.guild.owner)
        if (!donorGuild.patreonBot) {
            data.messageReceivedEvent.channel.sendMessage(
                    errorEmbed("In order to use the high quality patreon bot, the owner of your guild must pledge at least $10 on [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)")
            ).queue()
            return false
        }
        return true
    }

    private suspend fun processRateLimit(event: GuildMessageReceivedEvent): Boolean {
        val rateLimiter = channelListener.guildListener.messageListener.commandRateLimiter
        val user = event.author.idLong
        val wasLimited = rateLimiter.isLimited(user)
        rateLimiter.add(user)
        if (wasLimited) return false
        if (rateLimiter.isLimited(user)) {
            event.channel.sendMessage("${event.member.asMention} You have been ratelimited! Please wait a little and try again!").queue()
            return false
        }
        return true
    }

    private fun resolvePath(commandMessage: String): Pair<Module, List<PathNode>>? {
        for (module in application.modules) return module to (resolvePath(module.commands, "", commandMessage)
                ?: continue)
        return null
    }

    private fun resolvePath(commands: List<Command>, commandPath: String, commandMessage: String): List<PathNode>? {
        val (commandName, commandContent) = commandMessage.splitFirst(" ")

        val command = commands.findByName(commandName) ?: return null

        val newCommandPath = "$commandPath ${command.varient.name}".trim()
        val commandNode = PathNode(command, newCommandPath, commandContent)

        if (commandContent.isBlank()) return listOf(commandNode)

        val subPath = resolvePath(command.subCommands, newCommandPath, commandContent) ?: listOf(commandNode)
        return listOf(commandNode, *subPath.toTypedArray())
    }

    data class PathNode(val command: Command, val commandPath: String, val commandContent: String)

    fun dispose() {
        destroyed = true
        sessionActor.close()
    }

}