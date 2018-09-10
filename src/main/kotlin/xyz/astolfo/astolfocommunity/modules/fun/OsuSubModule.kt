package xyz.astolfo.astolfocommunity.modules.`fun`

import com.oopsjpeg.osu4j.backend.EndpointUsers
import com.oopsjpeg.osu4j.backend.Osu
import com.oopsjpeg.osu4j.exception.OsuAPIException
import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.message.BasicNameValuePair
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import xyz.astolfo.astolfocommunity.modules.SubModuleBase

class OsuSubModule(val application: AstolfoCommunityApplication) : SubModuleBase() {

    private val osu = Osu.getAPI(application.properties.osu_api_token)

    override fun ModuleBuilder.create() {
        command("osu") {
            /*action {
                embed {
                    val osuPicture = "https://upload.wikimedia.org/wikipedia/commons/d/d3/Osu%21Logo_%282015%29.png"
                    title("Astolfo Osu Integration")
                    description = "**sig**  -  generates an osu signature of the user" +
                            "\n**profile**  -  gets user data from the osu api"
                    thumbnail = osuPicture
                }.queue()
            }*/
            command("sig", "s") {
                action {
                    val osuUsername = URLEncodedUtils.format(listOf(BasicNameValuePair("name", args)), Charsets.UTF_8)
                    println(osuUsername)
                    val url = "http://lemmmy.pw/osusig/sig.php?colour=purple&u$osuUsername&pp=1"
                    embed {
                        title("Astolfo Osu Signature", url)
                        description = "$args\'s Osu Signature!"
                        image = url
                    }.queue()
                }
            }
            command("profile", "p", "user", "stats") {
                action {
                    val username = args
                    val user = try {
                        captureOsuError { osu.users.query(EndpointUsers.ArgumentsBuilder(args).build()) }
                    } catch (e: IndexOutOfBoundsException) {
                        errorEmbed("No profiles found for the username **$username**").queue()
                        return@action
                    }
                    val topScores = user.getTopScores(3).get()
                    val topBeatMaps = topScores.map { it.beatmap }.map { it.get() }

                    embed {
                        title("Osu - ${user.username}'s Stats", user.url.toString())
                        thumbnail = "https://a.ppy.sh/${user.id}"

                        description =
                                "**Profile Url:** *${user.url}*\n" +
                                "**Country:** *${user.country}*\n" +
                                "**Accuracy:** *${user.accuracy}%*\n" +
                                "**Play Count:** *${user.playCount}*"

                        field("Top Plays", false) {
                            topBeatMaps.withIndex().joinToString("\n") { (index, osuBeatMap) ->
                                "***${index + 1}.***  [$osuBeatMap](${osuBeatMap.url})"
                            }
                        }
                    }.queue()
                }
            }
        }
    }

    private suspend inline fun <T> CommandScope.captureOsuError(crossinline block: () -> T): T = try {
        block()
    } catch (e: OsuAPIException) {
        errorEmbed("Failed to execute Osu Api request, please inform the developers of this error").send()
        throw e
    }

}