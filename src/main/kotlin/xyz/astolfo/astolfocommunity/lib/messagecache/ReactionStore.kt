package xyz.astolfo.astolfocommunity.lib.messagecache

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.requests.RestAction
import java.util.concurrent.ConcurrentHashMap

interface ReactionStore : Iterable<MessageReaction> {
    operator fun plusAssign(unicode: String)
    operator fun plusAssign(emote: Emote)
    operator fun plusAssign(reactionEmote: MessageReaction.ReactionEmote) =
            if (reactionEmote.isEmote) plusAssign(reactionEmote.emote)
            else plusAssign(reactionEmote.name)

    operator fun minusAssign(unicode: String)
    operator fun minusAssign(emote: Emote)
    operator fun minusAssign(reactionEmote: MessageReaction.ReactionEmote) =
            if (reactionEmote.isEmote) minusAssign(reactionEmote.emote)
            else minusAssign(reactionEmote.name)

    fun clear()
    fun clear(member: Member)
    fun clear(unicode: String)
    fun clear(emote: Emote)
}

internal class ReactionStoreImpl(private val cachedMessage: CachedMessageImpl) : ReactionStore {

    private val modificationSync = Any()
    private val approvedReactionMap = ConcurrentHashMap<ReactionKey, ReactionValue>()
    private val reactionMap = ConcurrentHashMap<ReactionKey, ReactionValue>()

    private fun runTask(block: (channel: TextChannel, idLong: Long) -> Unit) = cachedMessage.runTask {
        block(cachedMessage.channel, cachedMessage.idLong)
    }

    override fun plusAssign(unicode: String) = runTask { channel, idLong ->
        internalPlusAssign(ReactionKey(unicode, 0, cachedMessage.jda), channel.addReactionById(idLong, unicode))
    }

    override fun plusAssign(emote: Emote) = runTask { channel, idLong ->
        internalPlusAssign(ReactionKey(emote), channel.addReactionById(idLong, emote))
    }

    private fun internalPlusAssign(reactionEmote: ReactionKey, restAction: RestAction<Void>) = synchronized(modificationSync) {
        val effectiveReaction = effectiveReactions[reactionEmote] ?: createDefaultReaction(reactionEmote)
        if (effectiveReaction.self) return@synchronized // Ignore if we already reacted
        reactionMap[reactionEmote] = effectiveReaction.copy(
                self = true,
                count = effectiveReaction.count + 1
        )
        restAction.submit().whenComplete { _, error ->
            reactionMap.remove(reactionEmote)
            if (error == null) synchronized(modificationSync) {
                approvedReactionMap[reactionEmote] = approvedReactionMap.computeIfAbsent(reactionEmote, ::createDefaultReaction).copy(
                        self = true,
                        count = effectiveReaction.count + 1
                )
            }
        }
    }

    private fun createDefaultReaction(reactionEmote: ReactionKey) = ReactionValue(cachedMessage.channel, reactionEmote, cachedMessage.idLong, false, 0)

    override fun minusAssign(unicode: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun minusAssign(emote: Emote) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun clear() = runTask { channel, idLong ->
        synchronized(modificationSync) {
            val modifications = effectiveReactions.mapValues {
                it.value.copy(
                        self = false,
                        count = 0
                )
            }
            if (modifications.isEmpty()) return@synchronized
            reactionMap += modifications
            channel.clearReactionsById(idLong).submit().whenComplete { _, error ->
                reactionMap -= modifications.keys
                if (error == null) synchronized(modificationSync) {
                    approvedReactionMap -= modifications.keys
                }
            }
        }
    }

    override fun clear(member: Member) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun clear(unicode: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun clear(emote: Emote) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private val effectiveReactions: Map<ReactionKey, ReactionValue>
        get() = synchronized(modificationSync) {
            val result = mutableMapOf<ReactionKey, ReactionValue>()
            reactionMap.forEach { result[it.key] = it.value }
            approvedReactionMap.filterKeys { !result.containsKey(it) }.forEach { result[it.key] = it.value }
            return result
        }

    override fun iterator(): Iterator<MessageReaction> = effectiveReactions.values.map { it.asMessageReaction }.iterator()

    fun update(reactions: MutableList<MessageReaction>) {

    }

    fun dispose(){

    }

    private val MessageReaction.ReactionEmote.asReactionKey
        get() = if (emote == null) ReactionKey(name, idLong, jda, emote)
        else ReactionKey(emote)

    class ReactionKey(
            val name: String,
            val id: Long,
            val api: JDA,
            val emote: Emote? = null
    ) {
        constructor(emote: Emote) : this(emote.name, emote.idLong, emote.jda, emote)

        val asReactionEmote by lazy {
            if (emote == null) MessageReaction.ReactionEmote(name, id, api)
            else MessageReaction.ReactionEmote(emote)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ReactionKey

            if (name != other.name) return false
            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + id.hashCode()
            return result
        }
    }

    private val MessageReaction.asReactionValue
        get() = ReactionValue(channel, reactionEmote.asReactionKey, messageIdLong, isSelf, count)

    data class ReactionValue(
            val channel: MessageChannel,
            val emote: ReactionKey,
            val messageId: Long,
            val self: Boolean,
            val count: Int
    ) {
        val asMessageReaction by lazy { MessageReaction(channel, emote.asReactionEmote, messageId, self, count) }
    }
}