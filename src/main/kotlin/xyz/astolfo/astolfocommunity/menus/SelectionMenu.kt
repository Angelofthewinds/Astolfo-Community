package xyz.astolfo.astolfocommunity.menus

import com.jagrosh.jdautilities.commons.utils.FinderUtil
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.lib.jda.embedRaw
import xyz.astolfo.astolfocommunity.lib.jda.message
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached
import xyz.astolfo.astolfocommunity.messages.*

fun CommandScope.memberSelectionBuilder(query: String) = selectionBuilder<Member>()
        .results(FinderUtil.findMembers(query, event.guild))
        .noResultsMessage("Unknown Member!")
        .resultsRenderer { "**${it.effectiveName} (${it.user.name}#${it.user.discriminator})**" }
        .description("Type the number of the member you want.")

fun CommandScope.textChannelSelectionBuilder(query: String) = selectionBuilder<TextChannel>()
        .results(FinderUtil.findTextChannels(query, event.guild))
        .noResultsMessage("Unknown Text Channel!")
        .resultsRenderer { "**${it.name} (${it.id})**" }
        .description("Type the number of the text channel you want.")

fun CommandScope.roleSelectionBuilder(query: String) = selectionBuilder<Role>()
        .results(FinderUtil.findRoles(query, event.guild))
        .noResultsMessage("Unknown Role!")
        .resultsRenderer { "**${it.name} (${it.id})**" }
        .description("Type the number of the role you want.")

fun <E> CommandScope.selectionBuilder() = SelectionMenuBuilder<E>(this)

class SelectionMenuBuilder<E>(private val commandScope: CommandScope) {
    var title = "Selection Menu"
    var results = emptyList<E>()
    var noResultsMessage = "No results!"
    var resultsRenderer: (E) -> String = { it.toString() }
    var description = "Type the number of the selection you want"
    var renderer: Paginator.() -> Message = {
        message {
            embedRaw {
                titleProvider.invoke()?.let { title(it) }
                description("$description\n$providedString")
                footer("Page ${currentPage + 1}/${provider.pageCount}")
            }
        }
    }

    fun title(value: String) = apply { title = value }
    fun results(value: List<E>) = apply { results = value }
    fun noResultsMessage(value: String) = apply { noResultsMessage = value }
    fun resultsRenderer(value: (E) -> String) = apply { resultsRenderer = value }
    fun renderer(value: Paginator.() -> Message) = apply { renderer = value }
    fun description(value: String) = apply { description = value }

    suspend fun execute(): E? = with(commandScope) {
        if (results.isEmpty()) {
            errorEmbed(noResultsMessage).queue()
            return null
        }

        if (results.size == 1) return results.first()

        return suspendCancellableCoroutine { cont ->
            val menu = paginator(title) {
                provider(8, results.map { resultsRenderer.invoke(it) })
                renderer { this@SelectionMenuBuilder.renderer.invoke(this) }
            }

            var errorMessage: CachedMessage? = null
            // Waits for a follow up response for user selection
            val handle = session.responseListener {
                errorMessage?.delete()
                if (menu.isDestroyed) {
                    dispose()
                    return@responseListener
                }
                if (args.matches(Regex("\\d+"))) {
                    val numSelection = args.toBigInteger().toInt() - 1
                    if (numSelection !in results.indices) {
                        errorMessage = errorEmbed("Unknown Selection").sendCached()
                        shouldRunCommand = false
                        return@responseListener
                    }
                    menu.destroy()
                    cont.resume(results[numSelection])
                    dispose(false)
                    return@responseListener
                }
                if (event.message.contentRaw == args) {
                    errorMessage = errorEmbed("Response must be a number!").sendCached()
                    shouldRunCommand = false
                    return@responseListener
                } else dispose()
            }
            cont.invokeOnCancellation {
                errorMessage?.delete()
                menu.destroy()
                handle.dispose()
            }
        }
    }
}

fun CommandScope.chatInput(inputMessage: String) = ChatInputBuilder(this)
        .description(inputMessage)

class ChatInputBuilder(private val execution: CommandScope) {
    private var title: String = ""
    private var description: String = "Input = Output"
    private var responseValidator: suspend (String) -> Boolean = { true }

    fun title(value: String) = apply { title = value }
    fun description(value: String) = apply { description = value }
    fun responseValidator(value: suspend (String) -> Boolean) = apply { responseValidator = value }

    suspend fun execute(): String? = with(execution) {
        val message = embed {
            if (title.isNotBlank()) title(title)
            description(description)
        }.send().sendCached()
        // Waits for a follow up response for user selection
        return suspendCancellableCoroutine { cont ->
            val handle = session.responseListener {
                if (message.isDeleted) {
                    dispose()
                    return@responseListener
                }
                val result = responseValidator.invoke(args)
                if (result) {
                    // If the validator says its valid
                    cont.resume(args)
                    dispose(false)
                } else {
                    shouldRunCommand = false
                }
            }
            cont.invokeOnCancellation {
                message.delete()
                handle.dispose()
            }
        }
    }
}