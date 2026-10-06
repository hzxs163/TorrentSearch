package com.prajwalch.torrentsearch.providers

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * TorrentGalaxy.
 *
 * Reached through its WordPress JSON API, which already carries the complete
 * info hash, so no second request is needed to build the magnet link.
 */
class TorrentGalaxy(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "torrentgalaxy"
    override val name = "TorrentGalaxy"
    override val defaultDomains = listOf("https://torrentgalaxy.info")
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsJsonParser = TorrentGalaxyResultsJsonParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val keyword = query.encodeURIComponent().replace("+", "%20")
        val requestUrl = "$domain/get-posts/keywords:$keyword?format=json"
        val responseJson = networkClient.getJson(url = requestUrl) ?: return emptyList()

        return resultsJsonParser.parse(json = responseJson, domain = domain)
    }
}

private class TorrentGalaxyResultsJsonParser(
    private val providerId: SearchProviderId,
    private val providerName: String,
) {
    suspend fun parse(json: JsonElement, domain: String): List<Torrent> =
        withContext(Dispatchers.Default) {
            val results = (json as? JsonObject)?.getArray("results") ?: return@withContext emptyList()

            results
                .mapNotNull { item -> parseItem(item as? JsonObject ?: return@mapNotNull null, domain) }
        }

    /**
     * Fields returned by the API:
     *
     *     h:  <string>  info hash
     *     n:  <string>  name
     *     s:  <number>  size in bytes
     *     a:  <number>  added as epoch second
     *     pk: <number>  post id
     *     se: <number>  seeders
     *     le: <number>  leechers
     */
    private fun parseItem(item: JsonObject, domain: String): Torrent? {
        val infoHash = item.getString("h")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

        val name = item.getString("n")?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val size = item.getString("s")
            ?.toFloatOrNull()
            ?.takeIf { it > 0f }
            ?.let(FileSizeUtils::formatBytes)

        val uploadDate = item.getString("a")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let(TorrentDateParser::epochSecondToInstant)

        val detailsPageUrl = item.getString("pk")
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$domain/post-detail/$it/" }

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, infoHash),
            name = name,
            size = size,
            seeders = item.getString("se")?.toLongOrNull()?.toUIntOrNull() ?: 0u,
            peers = item.getString("le")?.toLongOrNull()?.toUIntOrNull() ?: 0u,
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
