package xyz.astolfo.astolfocommunity.cluster

import net.dv8tion.jda.core.Permission
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import xyz.astolfo.astolfocommunity.ASTOLFO_GSON
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication

@RestController
@RequestMapping("/api/v1")
class ClusterApiEndpoint(val application: AstolfoCommunityApplication) {

    @RequestMapping("/guilds", method = [RequestMethod.POST])
    fun guilds(@RequestBody guilds: GuildsRequest) = GuildsResponse(guilds.guilds.map { guildId ->
        val guild = application.shardManager.getGuildById(guildId)
        val member = guild?.getMemberById(guilds.user)
        GuildsResponse.GuildsEntry(
                guildId,
                member?.hasPermission(Permission.MANAGE_SERVER) ?: false,
                guild != null
        )
    }.toTypedArray())

    @RequestMapping("/settings/get", method = [RequestMethod.POST])
    fun getSettings(@RequestBody request: SettingGetRequest): ResponseEntity<SettingGetResponse> = guildContext(request.guildId, request.userId) {
        val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(request.guildId)
        // TODO add the rest of the settings
        return@guildContext ResponseEntity.ok(SettingGetResponse(arrayOf(
                SettingGetResponse.Setting("Prefix", guildSettings.getEffectiveGuildPrefix(application))
        )))
    }

    @RequestMapping("/settings/post", method = [RequestMethod.POST])
    fun postSettings(@RequestBody request: SettingPostRequest): ResponseEntity<Unit> = guildContext(request.guildId, request.userId) {
        //val body = ASTOLFO_GSON.fromJson<>()
        val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(request.guildId)

        // TODO add the rest of the settings
        return@guildContext ResponseEntity.ok().build<Unit>()
    }

    private fun <T> guildContext(guildId: Long, userId: Long, block: () -> ResponseEntity<T>): ResponseEntity<T> {
        val guild = application.shardManager.getGuildById(guildId)
        val member = guild?.getMemberById(userId)
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) return ResponseEntity(HttpStatus.UNAUTHORIZED)
        return block()
    }

    // Guilds

    class GuildsRequest(val user: Long = 0, val guilds: Array<Long> = emptyArray())

    @Suppress("unused")
    class GuildsResponse(val guildsBotIn: Array<GuildsEntry> = emptyArray()) {
        class GuildsEntry(val guildId: Long, val hasPermission: Boolean, val botIn: Boolean)
    }

    // Settings

    class SettingGetRequest(val userId: Long = 0L, val guildId: Long = 0L)
    class SettingPostRequest(val userId: Long = 0L, val guildId: Long = 0L, val body: String = "")

    class SettingGetResponse(val settings: Array<Setting<*>> = emptyArray()) {
        class Setting<D>(
                val name: String = "",
                val value: D? = null
        )
    }

}

