package xyz.astolfo.astolfocommunity.games

import com.google.common.cache.CacheBuilder
import java.util.concurrent.TimeUnit

object InviteHandler {

    private val inviteCache = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .removalListener<InviteKey, Invite> {
                if (it.wasEvicted()) it.value.expireCallback()
            }
            .build<InviteKey, Invite>()

    operator fun get(invitedId: Long, guildId: Long) = inviteCache.asMap().filterKeys {
        it.invitedId == invitedId && it.guildId == guildId
    }.also { it.forEach { key, _ -> inviteCache.getIfPresent(key) } }

    fun invite(inviterId: Long, invitedId: Long, guildId: Long, acceptCallback: () -> Unit, expireCallback: () -> Unit) {
        inviteCache.put(InviteKey(inviterId, invitedId, guildId), Invite(inviterId, invitedId, guildId, acceptCallback, expireCallback))
    }

    fun remove(inviterId: Long, invitedId: Long, guildId: Long) {
        inviteCache.invalidate(InviteKey(inviterId, invitedId, guildId))
    }

}

data class InviteKey(val inviterId: Long, val invitedId: Long, val guildId: Long)

data class Invite(val inviterId: Long, val invitedId: Long, val guildId: Long, val acceptCallback: () -> Unit, val expireCallback: () -> Unit)