package xyz.astolfo.astolfocommunity.modules.music

import org.apache.http.NameValuePair
import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.message.BasicNameValuePair
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import xyz.astolfo.astolfocommunity.lib.jda.message
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import xyz.astolfo.astolfocommunity.modules.SubModuleBase

class RadioSubModule(musicManager: MusicManager) : SubModuleBase() {

    private val shoutcastRadio = ShoutcastRadio("")

    override fun ModuleBuilder.create() {
        command("radio") {
            musicAction {
                // Make the radio command work like the join command as well
                val musicSession = joinAction() ?: return@musicAction
                val results = tempMessage(embed("Searching radio stations for $args...")) {
                    shoutcastRadio.getTop500Stations()
                }

                val selectedRadio = selectionBuilder<ShoutcastRadio.Station>()
                        .title("\uD83D\uDD0E Radio Search Results:")
                        .results(results.stations)
                        .noResultsMessage("No results!")
                        .resultsRenderer { "**${it.name}**" }
                        .renderer {
                            message {
                                embedRaw {
                                    titleProvider.invoke()?.let { title(it) }
                                    val string = providedString
                                    description("Type the number of the station you want\n$string")
                                    footer("Page ${currentPage + 1}/${provider.pageCount}")
                                }
                            }
                        }.execute() ?: return@musicAction

                musicSession.musicLoader.loadAndQueue(this, results.tuneIn.getUrlForId(selectedRadio.id), false, false, true)
            }
        }
    }

}

class ShoutcastRadio(private val devId: String) {

    companion object {
        private val BASE_URL = "http://api.shoutcast.com/"
    }

    fun getTop500Stations(
            limit: Int? = null,
            bitrate: Int? = null,
            mediaType: MediaType? = null
    ): StationList {
        val params = mutableListOf<NameValuePair>()
        if (limit != null) params += BasicNameValuePair("limit", limit.toString())
        if (bitrate != null) params += BasicNameValuePair("br", bitrate.toString())
        if (mediaType != null) params += BasicNameValuePair("mt", mediaType.value)
        return StationList.parse(makeXMLRequest("legacy/Top500", params))
    }

    private fun makeXMLRequest(path: String, params: List<NameValuePair>): Document {
        val effectiveUrl = "$BASE_URL$path?${URLEncodedUtils.format(params + BasicNameValuePair("k", devId), Charsets.UTF_8)}"
        return Jsoup.connect(effectiveUrl).get()
    }

    enum class MediaType(val value: String) {
        MP3("audio/mpeg"), AAC("audio/aacp");

        companion object {
            fun fromValue(value: String) = values().find { it.value == value }
        }
    }

    data class StationList(
            val tuneIn: TuneIn,
            val stations: List<Station>
    ) {
        companion object {
            fun parse(element: Element) = StationList(
                    TuneIn.parse(element.getElementsByTag("tunein").first()),
                    element.getElementsByTag("station").map { Station.parse(it) }
            )
        }
    }

    data class TuneIn(val base: String) {
        fun getUrlForId(id: Int) = "http://yp.shoutcast.com$base?id=$id"

        companion object {
            fun parse(element: Element) = TuneIn(element.attr("base"))
        }
    }

    data class Station(
            val name: String,
            val mediaType: MediaType,
            val id: Int,
            val bitrate: Int,
            val genre: String,
            val currentTrack: String,
            val listenerCount: Int
    ) {
        companion object {
            fun parse(element: Element) = Station(
                    element.attr("name"),
                    MediaType.fromValue(element.attr("mt"))!!,
                    element.attr("id").toInt(),
                    element.attr("br").toInt(),
                    element.attr("genre"),
                    element.attr("ct"),
                    element.attr("lc").toInt()
            )
        }
    }

}