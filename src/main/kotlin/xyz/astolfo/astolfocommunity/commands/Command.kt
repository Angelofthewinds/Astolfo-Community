package xyz.astolfo.astolfocommunity.commands

import net.dv8tion.jda.core.Permission
import xyz.astolfo.astolfocommunity.AstolfoPermission
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.lib.levenshteinDistance
import xyz.astolfo.astolfocommunity.lib.splitFirst
import xyz.astolfo.astolfocommunity.lib.words

typealias CommandAction = suspend CommandScope.() -> Unit
typealias InheritedCommandAction = suspend CommandScope.() -> Boolean

class Command(
        val varient: CommandVariant,
        val subCommands: List<Command>,
        val permission: AstolfoPermission,
        val inheritedActions: List<InheritedCommandAction>
)

class CommandVariant(
        val name: String,
        val usage: List<String>,
        val alts: List<CommandVariant>,
        val description: String,
        val action: CommandAction
) {
    val variants: List<String> by lazy { alts.map { it.variants }.flatten() + name }
}

class CommandBuilder(
        val path: String,
        val name: String,
        val alts: List<String>
) {

    private val subCommands = mutableListOf<Command>()
    private var action: CommandAction = {
        val (commandName, commandContent) = args.splitFirst(" ")

        val bestMatch = subCommands.map { it.varient.variants }.flatten()
                .sortedBy { it.levenshteinDistance(commandName, true) }.firstOrNull()
        val guildPrefix = guildSettings.getEffectiveGuildPrefix(application)
        if (bestMatch == null) {
            errorEmbed("Unknown command! Type **$guildPrefix$commandPath help** for a list of commands.").queue()
        } else {
            val recreated = "$guildPrefix$commandPath $bestMatch $commandContent".trim()
            errorEmbed("Unknown command! Did you mean **$recreated**?").queue()
        }
    }
    private var inheritedActions = mutableListOf<InheritedCommandAction>()
    private var permission = AstolfoPermission(path, name)
    private var usage = listOf<String>()
    private var description = ""

    fun command(subName: String, vararg alts: String, builder: CommandBuilder.() -> Unit) = apply {
        subCommands += CommandBuilder(this.path, subName, alts.toList()).also(builder).build()
    }

    fun action(action: CommandAction) = apply { this.action = action }
    fun inheritedAction(inheritedAction: InheritedCommandAction) = apply { this.inheritedActions.add(inheritedAction) }
    fun permission(vararg permissionDefaults: Permission) = apply { permission(name, *permissionDefaults) }
    fun description(description: String) = apply { this.description = description }
    fun usage(vararg usage: String) = apply { this.usage = usage.toList() }
    fun permission(node: String, vararg permissionDefaults: Permission) = apply { permission = AstolfoPermission(path, node, *permissionDefaults) }

    // Stage based Actions
    @Deprecated("No longer used")
    inline fun <reified E> stageActions(block: StageAction<E>.() -> Unit) {
        val clazz = E::class.java
        val stageAction = StageAction { clazz.newInstance()!! }
        block(stageAction)
        action { stageAction.execute(this) }
    }

    fun build(): Command {
        if (subCommands.isNotEmpty()) {
            command("help") {
                action {
                    val guildPrefix = guildSettings.getEffectiveGuildPrefix(application)
                    embed {
                        title("Astolfo ${this@CommandBuilder.name.capitalize()} Help")
                        val baseCommand = guildPrefix + commandPath.substringBeforeLast(" ").trim()
                        description(this@CommandBuilder.subCommands.filterNot { it.varient.name == "help" }.joinToString(separator = "\n") { subCommand ->
                            val stringBuilder = StringBuffer("$baseCommand **${subCommand.varient.name}**")
                            if (subCommand.varient.description.isNotBlank()) stringBuilder.append(" - ${subCommand.varient.description}")
                            if (subCommand.varient.usage.isNotEmpty())
                                stringBuilder.append("\n*Usages:*\n" + subCommand.varient.usage.joinToString(separator = "\n") { usage -> "- *$usage*" } + "\n")
                            stringBuilder.toString()
                        })
                    }.queue()
                }
            }
        }
        return Command(CommandVariant(name = name,
                alts = alts.map {
                    CommandVariant(
                            name = it,
                            alts = listOf(),
                            usage = usage,
                            description = description,
                            action = action
                    )
                },
                usage = usage,
                description = description,
                action = action), subCommands, permission, inheritedActions)
    }
}

class StageAction<E>(private val newData: () -> E) {
    private val actions = mutableListOf<StageActionEntry<E>>()

    fun basicAction(block: suspend CommandScope.(E) -> Unit) = action {
        block(it)
        true
    }

    fun action(block: suspend CommandScope.(E) -> Boolean) = actions.add(StageActionEntry(block))

    suspend fun execute(event: CommandScope) {
        val data = newData.invoke()
        for (action in actions) {
            val response = action.block.invoke(event, data)
            if (!response) break
        }
    }

    class StageActionEntry<E>(val block: suspend CommandScope.(E) -> Boolean)
}

typealias CommandArgs = String
typealias ArgsIterator = ListIterator<String>

//TODO add support for strings ?command "arg one here" "arg two here"
fun CommandArgs.argsIterator(): ArgsIterator = words().listIterator()

fun ArgsIterator.next(default: String) = if (hasNext()) next() else default

fun Iterable<Command>.findByName(name: String) = find { command -> command.varient.variants.any { it.equals(name, ignoreCase = true) } }