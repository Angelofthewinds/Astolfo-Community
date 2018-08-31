package xyz.astolfo.astolfocommunity.lib.commands

import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.User
import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.requests.restaction.MessageAction
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.GuildSettings
import xyz.astolfo.astolfocommunity.commands.CommandArgs
import xyz.astolfo.astolfocommunity.commands.CommandSession
import xyz.astolfo.astolfocommunity.messages.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.AbstractCoroutineContextElement
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.coroutineContext

interface CommandData {
    val event: GuildMessageReceivedEvent
    val session: CommandSession
    val commandPath: String
    val args: CommandArgs
    val timeIssued: Long
}

class CommandDataImpl(
        override val event: GuildMessageReceivedEvent,
        override val session: CommandSession,
        override val commandPath: String,
        override val args: CommandArgs,
        override val timeIssued: Long
) : AbstractCoroutineContextElement(CommandDataImpl), CommandData {
    companion object Key : CoroutineContext.Key<CommandDataImpl>
}

interface RequestedBy {
    val author: User
}

class RequestedByElement(override val author: User) : RequestedBy, AbstractCoroutineContextElement(RequestedByElement) {
    companion object Key : CoroutineContext.Key<RequestedByElement>
}

inline fun <reified T : Event> CoroutineContext.jdaEventOrNull() = get(CommandDataImpl)?.event as? T
inline fun <reified T : Event> CoroutineContext.jdaEvent() = jdaEventOrNull<T>()!!
inline val CoroutineContext.requestedBy: User?
    get() = get(RequestedByElement)?.author ?: get(CommandDataImpl)?.event?.author

interface CommandScope : CommandData, RequestedBy {

    val application: AstolfoCommunityApplication

    // General Scope shortcuts

    var guildSettings
        get() = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
        set(value) {
            application.astolfoRepositories.guildSettingsRepository.save(value)
        }

    var profile
        get() = application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong)
        set(value) {
            application.astolfoRepositories.userProfileRepository.save(value)
        }

    // Extensions

    fun Message.send(): MessageAction = event.channel.sendMessage(this)
    fun MessageEmbed.send(): MessageAction = event.channel.sendMessage(this)

    fun Message.queue(success: ((Message) -> Unit)? = null) = queue(success, null)
    fun MessageEmbed.queue(success: ((Message) -> Unit)? = null) = queue(success, null)

    fun Message.queue(success: ((Message) -> Unit)?, failure: ((Throwable) -> Unit)? = null) = send().queue(success, failure)
    fun MessageEmbed.queue(success: ((Message) -> Unit)?, failure: ((Throwable) -> Unit)? = null) = send().queue(success, failure)

    // Some overridden scope specific methods

    suspend fun embed(text: String) = embedSuspend(text)
    suspend fun embed(builder: SuspendingEmbedBuilderBlock) = embedSuspend(builder)

    suspend fun errorEmbed(text: String) = errorEmbedSuspend(text)
    suspend fun errorEmbed(builder: SuspendingEmbedBuilderBlock) = errorEmbedSuspend(builder)

    // Message Listener

    fun responseListener(listener: suspend CommandScope.() -> CommandSession.ResponseAction) = object : CommandSession.SessionListener() {
        override suspend fun onMessageReceived(commandScope: CommandScope): CommandSession.ResponseAction = listener(commandScope)
    }

    // Temp Message

    suspend fun <T> tempMessage(embed: MessageEmbed, temp: suspend () -> T): T = tempMessage(message { setEmbed(embed) }, temp)
    suspend fun <T> tempMessage(message: Message, block: suspend () -> T): T = suspendCancellableCoroutine { cont ->
        val cachedMessage = message.send().sendCached()
        val job = async(cont.context) { block() }
        job.invokeOnCompletion { error ->
            if (error != null) cont.resumeWithException(error)
            else cont.resume(job.getCompleted())
        }
        cont.invokeOnCancellation {
            job.cancel()
            cachedMessage.delete()
        }
    }

    // TODO redo this system
    fun updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) = session.updatable(rate, unit, updater)

    companion object {
        suspend operator fun invoke(application: AstolfoCommunityApplication): CommandScope {
            val commandData = coroutineContext[CommandDataImpl]
                    ?: throw IllegalThreadStateException("Current coroutine doesn't have any command data!")
            return CommandScopeImpl(application, commandData)
        }
    }
}

suspend fun <E> CommandScope.withGuildSettings(block: suspend (GuildSettings) -> E): E = guildSettings.let {
    val result = block(it)
    guildSettings = it
    result
}

class CommandScopeImpl(
        override val application: AstolfoCommunityApplication,
        data: CommandData
) : CommandScope, CommandData by data {
    override val author: User = data.event.author
}
