package xyz.astolfo.astolfocommunity.modules.music

import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.utils.PermissionUtil
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.Emotes
import xyz.astolfo.astolfocommunity.commands.CommandAction
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.modules.ModuleBase
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder

class MusicModule(application: AstolfoCommunityApplication) : ModuleBase("Music") {

    val musicManager = MusicManager(application)

    private val musicGenericSubModule = MusicGenericSubModule(musicManager)
    private val musicGuildPlaylistSubModule = GuildPlaylistSubModule(musicManager)
    private val radioSubModule = RadioSubModule(musicManager)

    override fun ModuleBuilder.create() {
        musicGenericSubModule.createHelper(this)
        musicGuildPlaylistSubModule.createHelper(this)
        radioSubModule.createHelper(this)
    }

}

fun volumeIcon(volume: Int) = when {
    volume == 0 -> Emotes.MUTE
    volume < 30 -> Emotes.SPEAKER
    volume < 70 -> Emotes.SPEAKER_1
    else -> Emotes.SPEAKER_2
}

fun CommandBuilder.musicAction(
        mustBeInVoice: Boolean = true,
        mustBeInSameVoice: Boolean = true,
        needsSession: Boolean = false,
        block: CommandAction
) {
    action {
        val guild = event.guild
        val member = event.member
        val currentSession = application.musicModule.musicManager.getIfPresent(guild.idLong)
        if (needsSession && currentSession == null) {
            errorEmbed("I am not currently in a Voice Channel, please invite me to one first before using this command.").queue()
            return@action
        }
        if (mustBeInVoice && !member.voiceState.inVoiceChannel()) {
            errorEmbed("You are currently not in a Voice Channel, please join one first before using this command.").queue()
            return@action
        }
        if (mustBeInSameVoice && currentSession != null && guild.selfMember.voiceState.inVoiceChannel()) {
            if (guild.selfMember.voiceState.channel != member.voiceState.channel) {
                errorEmbed("You are currently in a different Voice Channel than me. Please join my Voice Channel before using this command.").queue()
                return@action
            }
        }
        block()
    }
}

suspend fun CommandScope.joinAction(forceMessage: Boolean = false): MusicSession? {
    val member = event.member
    val guild = event.guild
    val voiceChannel = member.voiceState.channel!!
    val musicSession = application.musicModule.musicManager[guild.idLong]

    if (voiceChannel != guild.selfMember.voiceState.channel) {
        if (guild.afkChannel == voiceChannel) {
            errorEmbed("I cannot join an **AFK channel**. Please try a normal Voice Channel.").queue()
            return null
        }
        if (!PermissionUtil.checkPermission(voiceChannel, guild.selfMember, Permission.VOICE_MOVE_OTHERS) && voiceChannel.userLimit != 0 && voiceChannel.members.size >= voiceChannel.userLimit) {
            errorEmbed("I cannot join a **Full Voice Channel**, either give me the **${Permission.VOICE_MOVE_OTHERS.name}** permission or use a different Voice Channel.").queue()
            return null
        }
        if (!PermissionUtil.checkPermission(voiceChannel, guild.selfMember, Permission.VOICE_CONNECT)) {
            if (guild.selfMember.hasPermission(Permission.VOICE_CONNECT)) {
                errorEmbed("I do not have permission to join **${voiceChannel.name}**. Either give me the **${Permission.VOICE_CONNECT.name}** permission or use a different Voice Channel.").queue()
            } else {
                errorEmbed("I do not have permission to join Voice Channels in this guild. In order to use the Music Feature I need the permission **${Permission.VOICE_CONNECT.name}**.").queue()
            }
            return null
        }
        if (!PermissionUtil.checkPermission(voiceChannel, guild.selfMember, Permission.VOICE_SPEAK)) {
            if (guild.selfMember.hasPermission(Permission.VOICE_SPEAK)) {
                errorEmbed("I do not have permission to speak in **${voiceChannel.name}**. Either give me the **${Permission.VOICE_SPEAK.name}** permission or use a different Voice Channel.").queue()
            } else {
                errorEmbed("I do not have permission to speak in Voice Channels in this guild. In order to use the Music Feature I need the permission **${Permission.VOICE_SPEAK.name}**.").queue()
            }
            return null
        }
    } else {
        // Ignore the rest since its all about moving and messages
        if (!forceMessage) return musicSession
    }
    musicSession.musicTextChannel.textChannelId = event.channel.idLong
    musicSession.link.connect(voiceChannel)
    embed("I have join the Voice Channel **${voiceChannel.name}**").queue()
    return musicSession
}

suspend fun CommandScope.playAction(queueTop: Boolean, skipCurrentSong: Boolean) {
    val musicSession = joinAction() ?: return
    if (args.isEmpty()) {
        errorEmbed("Give me something to search! I support youtube, soundcloud, vimeo, etc.").queue()
        return
    }
    musicSession.musicLoader.loadAndQueue(
            this,
            args,
            queueTop,
            skipCurrentSong
    )
    musicSession.musicTextChannel.textChannelId = event.channel.idLong
}