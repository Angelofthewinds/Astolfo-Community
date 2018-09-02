package xyz.astolfo.astolfocommunity.messages

import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Message

// Helpers

fun Message.hasPermission(vararg permissions: Permission): Boolean = guild.selfMember.hasPermission(textChannel, *permissions)
