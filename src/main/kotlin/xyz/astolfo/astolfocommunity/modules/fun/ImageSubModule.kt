package xyz.astolfo.astolfocommunity.modules.`fun`

import com.github.natanbc.weeb4j.TokenType
import com.github.natanbc.weeb4j.Weeb4J
import com.github.natanbc.weeb4j.image.HiddenMode
import com.github.natanbc.weeb4j.image.NsfwFilter
import org.jsoup.Jsoup
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.lib.jda.message
import xyz.astolfo.astolfocommunity.lib.web
import xyz.astolfo.astolfocommunity.lib.webJson
import xyz.astolfo.astolfocommunity.menus.memberSelectionBuilder
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import xyz.astolfo.astolfocommunity.modules.SubModuleBase
import java.util.*

class ImageSubModule(application: AstolfoCommunityApplication) : SubModuleBase() {

    private val weeb4J = Weeb4J.Builder().setToken(TokenType.WOLKE, application.properties.weeb_token).build()

    override fun ModuleBuilder.create() {
        command("cat", "cats") {
            var hasNulls = true
            var index = 0
            val validCats = arrayOfNulls<String>(500)
            val random = Random()
            action {
                val cat = try {
                    val newCat = webJson<Cat>("http://aws.random.cat/meow", null).await().file!!
                    synchronized(validCats) {
                        if (!validCats.contains(newCat)) {
                            validCats[index] = newCat
                            index++
                            if (index >= validCats.size) {
                                index = 0
                                hasNulls = false
                            }
                        }
                    }
                    newCat
                } catch (e: Throwable) {
                    synchronized(validCats) {
                        val randomIndex = random.nextInt(if (hasNulls) index else validCats.size)
                        validCats[randomIndex]!!
                    }
                }
                message(cat).queue()
            }
        }
        command("catgirl", "neko", "catgirls") {
            action {
                message(webJson<Neko>("https://nekos.life/api/neko").await().neko!!).queue()
            }
        }
        command("cyanideandhappiness", "cnh") {
            val random = Random()
            action {
                val r = random.nextInt(4665) + 1
                val imageUrl = Jsoup.parse(web("http://explosm.net/comics/$r/").await())
                        .select("#main-comic").first()
                        .attr("src")
                        .let { if (it.startsWith("//")) "https:$it" else it }
                embed {
                    title("Cyanide and Happiness")
                    image = imageUrl
                }.queue()
            }
        }
        command("hug") {
            action {
                val selectedMember = memberSelectionBuilder(args).title("Hug Selection").execute() ?: return@action
                val image = weeb4J.imageProvider.getRandomImage("hug", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
                embed {
                    description = "${event.author.asMention} has hugged ${selectedMember.asMention}"
                    this.image = image.url
                    footer = "Powered by weeb.sh"
                }.queue()
            }
        }
        command("kiss") {
            action {
                val selectedMember = memberSelectionBuilder(args).title("Kiss Selection").execute() ?: return@action
                val image = weeb4J.imageProvider.getRandomImage("kiss", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
                embed {
                    description = "${event.author.asMention} has kissed ${selectedMember.asMention}"
                    this.image = image.url
                    footer = "Powered by weeb.sh"
                }.queue()
            }
        }
        command("slap") {
            action {
                val selectedMember = memberSelectionBuilder(args).title("Slap Selection").execute() ?: return@action
                val image = weeb4J.imageProvider.getRandomImage("slap", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
                embed {
                    description = "${event.author.asMention} has slapped ${selectedMember.asMention}"
                    this.image = image.url
                    footer = "Powered by weeb.sh"
                }.queue()
            }
        }
    }

}