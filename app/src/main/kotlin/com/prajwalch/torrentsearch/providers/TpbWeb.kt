package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.R
import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.encodeURIComponent
import com.prajwalch.torrentsearch.extension.getArray
import com.prajwalch.torrentsearch.extension.getString
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.FileSizeUtils
import com.prajwalch.torrentsearch.util.TorrentDateParser
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 海盗湾（tpbweb）.
 *
 * A The Pirate Bay front end reached through its `api.php` server side proxy,
 * which returns the same torrent objects as `apibay.org`.
 */
class TpbWeb(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "tpbweb"
    override val name = "海盗湾"
    override val defaultDomains = listOf("https://tpb.re")
    override val supportedCategories = setOf(
        Category.Apps,
        Category.Books,
        Category.Games,
        Category.Movies,
        Category.Music,
        Category.Porn,
        Category.Series,
        Category.Other,
    )
    override val safety = SearchProviderSafety.Unsafe(
        reason = R.string.tpbweb_unsafe_reason,
    )
    override val enabledByDefault = false

    private val resultsJsonParser = TpbWebResultsJsonParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val keyword = query.encodeURIComponent().replace("%20", "+")
        val requestUrl = "$domain/api.php?url=/q.php?q=$keyword&cat=${categoryId(category)}"
        val responseJson = networkClient.getJson(url = requestUrl) ?: return emptyList()

        return resultsJsonParser.parse(json = responseJson, domain = domain)
    }

    /**
     * Returns the index of a given category, shared with The Pirate Bay layout.
     */
    private fun categoryId(category: Category): Int = when (category) {
        Category.All, Category.Anime -> 0
        Category.Apps -> 300
        Category.Books -> 601
        Category.Games -> 400
        Category.Movies, Category.Series -> 200
        Category.Music -> 101
        Category.Porn -> 500
        Category.Other -> 600
    }
}

private class TpbWebResultsJsonParser(
    private val providerId: SearchProviderId,
    private val providerName: String,
) {
    suspend fun parse(json: JsonElement, domain: String): List<Torrent> =
        withContext(Dispatchers.Default) {
            // The proxy returns either a bare array or an object with results.
            val items = when (json) {
                is JsonArray -> json
                is JsonObject -> json.getArray("results") ?: return@withContext emptyList()
                else -> return@withContext emptyList()
            }

            items.mapNotNull { item -> parseItem(item as? JsonObject ?: return@mapNotNull null, domain) }
        }

    /**
     * Fields returned by the endpoint:
     *
     *     id:         <string>  remote torrent id
     *     name:       <string>  name
     *     info_hash:  <string>  info hash
     *     size:       <string>  size in bytes
     *     added:      <string>  added as epoch second
     *     seeders:    <number>  seeders
     *     leechers:   <number>  leechers
     */
    private fun parseItem(item: JsonObject, domain: String): Torrent? {
        val infoHash = item.getString("info_hash")?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val name = item.getString("name")?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val size = item.getString("size")
            ?.toFloatOrNull()
            ?.takeIf { it > 0f }
            ?.let(FileSizeUtils::formatBytes)

        val uploadDate = item.getString("added")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let(TorrentDateParser::epochSecondToInstant)

        val torrentRemoteId = item.getString("id")?.takeIf { it.isNotEmpty() }
        val detailsPageUrl = torrentRemoteId?.let { "$domain/description.php?id=$it" }

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, torrentRemoteId ?: infoHash),
            name = name,
            size = size,
            seeders = item.getString("seeders")?.toUIntOrNull() ?: 0u,
            peers = item.getString("leechers")?.toUIntOrNull() ?: 0u,
            uploadDate = uploadDate,
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
            detailsPageUrl = detailsPageUrl,
        )
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
}
