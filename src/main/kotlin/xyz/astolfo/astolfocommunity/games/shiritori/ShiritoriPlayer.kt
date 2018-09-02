package xyz.astolfo.astolfocommunity.games.shiritori

import kotlinx.coroutines.experimental.withContext
import net.dv8tion.jda.core.entities.Member
import xyz.astolfo.astolfocommunity.lib.commands.RequestedByElement
import xyz.astolfo.astolfocommunity.lib.jda.errorEmbed
import xyz.astolfo.astolfocommunity.lib.messagecache.CachedMessage
import xyz.astolfo.astolfocommunity.lib.messagecache.sendCached

class ShiritoriPlayer(
        val game: ShiritoriGame,
        val member: Member,
        var score: Double
) {

    companion object {
        private val wordFilter = Regex("[^a-zA-Z]+")
    }

    private val messagePrefix
        get() = "**${member.effectiveName}:**"

    private var lastWarning: CachedMessage? = null

    suspend fun onMessage(event: ShiritoriMessageEvent) {
        withContext(RequestedByElement(event.member.user)) {
            onMessageInternal(event)
        }
    }

    private suspend fun onMessageInternal(event: ShiritoriMessageEvent) {
        lastWarning?.delete()

        val currentTurn = game.currentTurn

        if (currentTurn != this) {
            lastWarning = game.channel.sendMessage(errorEmbed("$messagePrefix It is not your turn yet!")).sendCached()
            return
        }

        val wordInput = event.message.toLowerCase().replace(wordFilter, "")

        if (wordInput.length < 4) {
            lastWarning = game.channel.sendMessage(errorEmbed("$messagePrefix Word *must be at least* ***4 or more*** *letters long*! You said **$wordInput**")).sendCached()
            return
        }

        if (!wordInput.startsWith(game.startLetter)) {
            lastWarning = game.channel.sendMessage(errorEmbed("$messagePrefix Word must start with **${game.startLetter}** You said: **$wordInput**")).sendCached()
            return
        }

        if (!ShiritoriGame.validWordList.contains(wordInput)) {
            lastWarning = game.channel.sendMessage(errorEmbed("$messagePrefix Unknown word... Make sure its a noun or verb! You said: **$wordInput**")).sendCached()
            return
        }

        if (game.usedWords.contains(wordInput)) {
            lastWarning = game.channel.sendMessage(errorEmbed("$messagePrefix That word has already been used! You said: **$wordInput**")).sendCached()
            return
        }

        game.makeMove(wordInput)
    }

    fun dispose() {
        lastWarning?.delete()
    }

}