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
import xyz.astolfo.astolfocommunity.lib.cancelQuietly
import xyz.astolfo.astolfocommunity.lib.jda.*
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.AbstractCoroutineContextElement
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.coroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

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

    fun Message.sendCached(): CachedMessage = send().sendCached()
    fun MessageEmbed.sendCached(): CachedMessage = send().sendCached()

    fun Message.send(): MessageAction = event.channel.sendMessage(this)
    fun MessageEmbed.send(): MessageAction = event.channel.sendMessage(this)

    fun Message.queue(success: ((Message) -> Unit)? = null) = queue(success, null)
    fun MessageEmbed.queue(success: ((Message) -> Unit)? = null) = queue(success, null)

    fun Message.queue(success: ((Message) -> Unit)?, failure: ((Throwable) -> Unit)? = null) {
        if (this is MessageQueue) {
            this.queue.forEach {
                it.send().queue(success, failure)
            }
        } else {
            send().queue(success, failure)
        }
    }

    fun MessageEmbed.queue(success: ((Message) -> Unit)?, failure: ((Throwable) -> Unit)? = null) = send().queue(success, failure)

    // Some overridden scope specific methods

    suspend fun embed(text: String) = embed0(text)
    suspend fun embed(builder: EmbedBuilderBlock) = embed0(builder)

    suspend fun errorEmbed(text: String) = errorEmbed0(text)
    suspend fun errorEmbed(builder: EmbedBuilderBlock) = errorEmbed0(builder)

    // Temp Message

    suspend fun <T> tempMessage(embed: MessageEmbed, temp: suspend () -> T): T = tempMessage(message(embed), temp)
    suspend fun <T> tempMessage(message: Message, block: suspend () -> T): T = suspendCancellableCoroutine { cont ->
        val cachedMessage = message.send().sendCached()
        val job = async(cont.context) { block() }
        job.invokeOnCompletion(onCancelling = true) { error ->
            if (error != null) cont.resumeWithException(error)
            else cont.resume(job.getCompleted())
            cachedMessage.delete()
        }
        cont.invokeOnCancellation { job.cancelQuietly(it) }
    }

    suspend fun <T> captureErrors(errors: List<KClass<out Throwable>>, block: suspend () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        e.printStackTrace()
        val exceptionClass = e::class
        if (errors.any { exceptionClass.isSubclassOf(it::class) }) {
            errorEmbed {
                description("Internal Error occurred")
            }.send()
        } else {
            throw e
        }
        null
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

suspend inline fun <reified T : Throwable, R> CommandScope.captureError(noinline block: suspend () -> R): R? = captureErrors(listOf(T::class), block)

class CommandScopeImpl(
        override val application: AstolfoCommunityApplication,
        data: CommandData
) : CommandScope, CommandData by data {
    override val author: User = data.event.author
}
