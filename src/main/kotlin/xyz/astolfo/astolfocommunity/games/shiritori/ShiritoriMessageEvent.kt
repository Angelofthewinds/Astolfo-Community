package xyz.astolfo.astolfocommunity.games.shiritori

import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.events.message.MessageReceivedEvent

interface ShiritoriMessageEvent {
    val message: String
    val member: Member
}

class ShiritoriJdaMessageEvent(event: MessageReceivedEvent) : ShiritoriMessageEvent {
    override val message = event.message.contentRaw
    override val member = event.member
}