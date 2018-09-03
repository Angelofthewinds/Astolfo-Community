package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.commands.Command
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import xyz.astolfo.astolfocommunity.commands.InheritedCommandAction
import xyz.astolfo.astolfocommunity.modules.`fun`.createFunModule
import xyz.astolfo.astolfocommunity.modules.admin.createAdminModule
import xyz.astolfo.astolfocommunity.modules.music.createMusicModule

internal object ModuleManager {
    var modules = listOf<Module>()
        private set

    fun registerModules() {
        modules = listOf(
                createInfoModule(),
                createFunModule(),
                createMusicModule(),
                createAdminModule(),
                createCasinoModule(),
                createStaffModule(),
                createNSFWModule()
        )
    }
}

class Module(
        val name: String,
        val hidden: Boolean,
        val nsfw: Boolean,
        val inheritedActions: List<InheritedCommandAction>,
        val commands: List<Command>
)

class ModuleBuilder(
        val name: String,
        val hidden: Boolean,
        val nsfw: Boolean
) {
    var commands = mutableListOf<Command>()
    val inheritedActions = mutableListOf<InheritedCommandAction>()

    fun inheritedAction(inheritedAction: InheritedCommandAction) = apply { this.inheritedActions.add(inheritedAction) }
    fun command(name: String, vararg alts: String, builder: CommandBuilder.() -> Unit) = apply {
        val commandBuilder = CommandBuilder(this.name, name, alts.toList())
        builder.invoke(commandBuilder)
        commands.add(commandBuilder.build())
    }

    fun build() = Module(name, hidden, nsfw, inheritedActions, commands)
}

inline fun module(
        name: String,
        hidden: Boolean = false,
        nsfw: Boolean = false,
        builder: ModuleBuilder.() -> Unit
): Module {
    val moduleBuilder = ModuleBuilder(name, hidden, nsfw)
    builder.invoke(moduleBuilder)
    return moduleBuilder.build()
}