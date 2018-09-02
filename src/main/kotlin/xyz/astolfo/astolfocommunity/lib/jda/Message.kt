package xyz.astolfo.astolfocommunity.lib.jda

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.utils.Helpers
import xyz.astolfo.astolfocommunity.lib.commands.requestedBy
import java.awt.Color
import java.util.*
import kotlin.coroutines.experimental.coroutineContext

/**
 * This only cares about the content/embeds and nothing else
 */
fun Message.contentEquals(other: Message): Boolean {
    if (contentRaw != other.contentRaw) return false
    return embeds.all { one -> other.embeds.any { two -> one.roughEquals(two) } }
}

/**
 * This only cares about the content and not where it came from
 */
fun MessageEmbed.roughEquals(other: MessageEmbed): Boolean {
    if (this == other) return true
    return (url == other.url
            && title == other.title
            && description == other.description
            && type == other.type
            && thumbnail == other.thumbnail
            //&& siteProvider == other.siteProvider
            && author == other.author
            && videoInfo == other.videoInfo
            && footer == other.footer
            && image?.url == other.image?.url
            && color == other.color
            && timestamp == other.timestamp
            && Helpers.deepEquals(fields, other.fields))
}

// Message Builders

typealias MessageBuilderBlock = AstolfoMessageBuilder.() -> Unit

fun message(content: String) = message { this.content = content }
fun message(contentEmbed: MessageEmbed) = message { this.embed = contentEmbed }
inline fun message(block: MessageBuilderBlock): MessageQueue = MessageQueueImpl(AstolfoMessageBuilder().also(block).buildAll(MessageBuilder.SplitPolicy.NEWLINE))

// Embed Builders

val DEFAULT_EMBED_COLOR = Color(64, 156, 217)

typealias EmbedBuilderBlock = AstolfoEmbedBuilder.() -> Unit

// Raw - no need for coroutines

fun embedRaw(content: String) = embedRaw0(content)
inline fun embedRaw(block: EmbedBuilderBlock) = embedRaw0(block)

fun embedRaw0(content: String) = embedRaw { description = content }
inline fun embedRaw0(block: EmbedBuilderBlock): MessageEmbed = AstolfoEmbedBuilder().also {
    it.color = DEFAULT_EMBED_COLOR
    block(it)
}.build()

// Normal - requires coroutine context

suspend fun embed(content: String) = embed0(content)
suspend inline fun embed(block: EmbedBuilderBlock) = embed0(block)

suspend inline fun embed0(content: String) = embed0 { description = content }
suspend inline fun embed0(block: EmbedBuilderBlock) = embedRaw {
    coroutineContext.requestedBy?.let { author -> footer = "Requested by ${author.name}" }
    block(this)
}

// Error

suspend fun errorEmbed(content: String) = errorEmbed0(content)
suspend inline fun errorEmbed(block: EmbedBuilderBlock) = errorEmbed0(block)

suspend fun errorEmbed0(content: String) = errorEmbed0 { description = content }
suspend inline fun errorEmbed0(block: EmbedBuilderBlock) = embed0 {
    color = Color.RED
    block(this)
}

class AstolfoMessageBuilder : MessageBuilder() {
    var tts: Boolean
        get() = super.isTTS
        set(value) {
            super.isTTS = value
        }
    var embed: MessageEmbed?
        get() = super.embed
        set(value) {
            super.embed = value
        }
    var nounce: String?
        get() = super.nonce
        set(value) {
            super.nonce = value
        }
    var content: String
        get() = builder.toString()
        set(value) {
            setContent(value)
        }

    fun embedRaw(content: String) = apply { super.embed = embedRaw0(content) }
    fun embedRaw(block: EmbedBuilderBlock) = apply { super.embed = embedRaw0(block) }

    suspend fun embed(content: String) = apply { super.embed = embed0(content) }
    suspend fun embed(block: EmbedBuilderBlock) = apply { super.embed = embed0(block) }
}

class AstolfoEmbedBuilder : EmbedBuilder() {
    var description: String
        get() = descriptionBuilder.toString()
        set(value) {
            setDescription(value)
        }
    var title = ""
        set(value) {
            setTitle(value, titleUrl)
            field = value
        }
    var titleUrl: String? = null
        set(value) {
            setTitle(title, value)
            field = value
        }
    var image: String? = null
        set(value) {
            setImage(value)
            field = value
        }
    var thumbnail: String? = null
        set(value) {
            setThumbnail(value)
            field = value
        }
    var color = DEFAULT_EMBED_COLOR
        set(value) {
            setColor(value)
            field = value
        }
    var footer: String? = null
        set(value) {
            setFooter(value, footerIcon)
            field = value
        }
    var footerIcon: String? = null
        set(value) {
            setFooter(footer, value)
            field = value
        }

    fun title(title: String, url: String? = null) = also {
        this.title = title
        this.titleUrl = url
    }

    @Deprecated("use property instead", ReplaceWith("this.description = content"))
    fun description(content: String) = also { this.description = content }

    fun author(name: String, url: String? = null, iconUrl: String? = null) = also { setAuthor(name, url, iconUrl) }

    fun field(name: String, inline: Boolean, value: () -> String) = also { field(name, value(), inline) }
    fun field(name: String, value: String, inline: Boolean) = also { addField(name, value, inline) }

    @Deprecated("use property instead", ReplaceWith("this.footer = text"))
    fun footer(text: String) = also { footer = text }

    fun footer(text: String, icon: String) = also {
        footer = text
        footerIcon = icon
    }
}

interface MessageQueue : Message {
    val queue: Queue<Message>

    companion object {
        operator fun invoke(message: Message): MessageQueue = message as? MessageQueue
                ?: MessageQueueImpl(LinkedList<Message>().apply { add(message) })
    }
}

class MessageQueueImpl(override val queue: Queue<Message>) : MessageQueue, Message by queue.element()