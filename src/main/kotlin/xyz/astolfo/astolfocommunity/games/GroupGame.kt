package xyz.astolfo.astolfocommunity.games

import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.lib.jda.errorEmbed

abstract class GroupGame(
        member: Member,
        channel: TextChannel,
        private val singlePlayerMode: SinglePlayerMode,
        val maxPlayers: Int = Int.MAX_VALUE
) : Game(member, channel) {

    private lateinit var _players: MutableList<Long>
    val players: List<Long>
        get() = _players
    val members: List<Member>
        get() = players.mapNotNull { member.guild.getMemberById(it) }

    override suspend fun start0() {
        val group = GroupHandler[member.guild.idLong, member.user.idLong]?.takeIf { it.allMembers.size >= 2 }

        _players = if (group == null) {
            when (singlePlayerMode) {
                SinglePlayerMode.NONE -> {
                    channel.sendMessage(errorEmbed("This game must be played with 2 or more people!")).queue()
                    endGame()
                    return
                }
                SinglePlayerMode.ALONE -> mutableListOf(member.user.idLong)
                SinglePlayerMode.BOT -> mutableListOf(member.user.idLong, member.guild.selfMember.user.idLong)
            }
        } else {
            val players = group.allMembers
            players.filter { member.guild.getMemberById(it) != null }.toMutableList()
        }

        if (_players.size > maxPlayers) {
            channel.sendMessage(errorEmbed("There are too many players, please remove someone from your group.")).queue()
            endGame()
            return
        }

        super.start0()
    }

    open suspend fun leave0(member: Member) {
        _players.remove(member.user.idLong)
        leave(member)
    }

    protected open suspend fun leave(member: Member) {}

    override suspend fun destroy0() {
        super.destroy0()
    }

}

enum class SinglePlayerMode {
    NONE,
    ALONE,
    BOT
}