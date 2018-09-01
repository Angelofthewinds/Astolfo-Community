package xyz.astolfo.astolfocommunity.lib.jda

import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.utils.Helpers

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