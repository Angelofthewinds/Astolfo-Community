package xyz.astolfo.astolfocommunity.games

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object GroupHandler {

    private val groupCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .removalListener<GroupKey, GameGroup> { (key, _) ->
                // Removes the mappings to the leaderKey
                memberMap.filterValues { it == key }.forEach {
                    memberMap.remove(it.key)
                }
            }
            .build(object : CacheLoader<GroupKey, GameGroup>() {
                override fun load(key: GroupKey): GameGroup = GameGroup(key.guildId, key.leaderId)
            })

    /**
     * Maps member key to the owner of the group
     */
    private val memberMap = ConcurrentHashMap<GroupKey, GroupKey>()

    operator fun get(guildId: Long, userId: Long): GameGroup? {
        val leaderKey = memberMap[GroupKey(guildId, userId)] ?: return null
        return groupCache.getIfPresent(leaderKey)
    }

    fun create(guildId: Long, leaderId: Long): GameGroup {
        val group = get(guildId, leaderId)
        if (group != null) return group
        val key = GroupKey(guildId, leaderId)
        memberMap[key] = key
        return groupCache[key]
    }

    fun join(guildId: Long, leaderId: Long, userId: Long): Boolean {
        val group = get(guildId, leaderId) ?: return false
        memberMap[GroupKey(guildId, userId)] = GroupKey(guildId, leaderId)
        group.add(userId)
        return true
    }

    fun leave(guildId: Long, userId: Long): Boolean {
        val group = get(guildId, userId) ?: return false
        val originalLeader = group.leaderId
        group.remove(userId)
        memberMap.remove(GroupKey(guildId, userId))
        if(group.leaderId != originalLeader) {
            // Leader change
            groupCache.invalidate(GroupKey(guildId, originalLeader))
            if(group.leaderId != -1L){
                // There are members still left in the group
                val key = GroupKey(guildId, group.leaderId)
                groupCache.put(key, group)
                memberMap[GroupKey(guildId, group.leaderId)] = key
                group.members.forEach {
                    memberMap[GroupKey(guildId, it)] = key
                }
            }
        }
        return true
    }

    data class GroupKey(val guildId: Long, val leaderId: Long)

}


class GameGroup(val guildId: Long, var leaderId: Long) {
    private val _members = mutableListOf<Long>()
    val members: List<Long> = _members

    val allMembers
        get() = members + leaderId

    fun add(member: Long) {
        _members += member
    }

    fun remove(member: Long) {
        if (leaderId == member) {
            leaderId = if (members.isNotEmpty())
                _members.removeAt(0)
            else -1L
        } else {
            _members -= member
        }
    }

}