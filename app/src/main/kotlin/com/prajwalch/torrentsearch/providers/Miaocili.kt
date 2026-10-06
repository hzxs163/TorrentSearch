package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.encodeHex
import com.prajwalch.torrentsearch.extension.stripHtmlTags
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 喵磁力.
 *
 * The keyword is hex encoded in the path and the info hash is carried by the
 * detail page URL, so the magnet link can be built without a second request.
 */
class Miaocili(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "miaocili"
    override val name = "喵磁力"
    override val defaultDomains = listOf("https://www.miaocili.org")
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = MiaociliResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val requestUrl = "$domain/search/${query.encodeHex()}_${PAGE}_$SORT.html"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        if (!responseHtml.contains("search-item")) return emptyList()

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    private companion object {
        private const val PAGE = "1"

        // Results sorted by id, the only ordering the app ever requests.
        private const val SORT = "id"
    }
}

private class MiaociliResultsPageParser(
    private val providerId: SearchProviderId,
    private val providerName: String,
) {
    suspend fun parse(html: String, domain: String): List<Torrent> =
        withContext(Dispatchers.Default) {
            html
                .split(BLOCK_SEPARATOR)
                .drop(1)
                .mapNotNull { block -> parseBlock(block, domain) }
        }

    private fun parseBlock(block: String, domain: String): Torrent? {
        val titleMatch = TITLE.find(block) ?: return null

        val detailsPagePath = titleMatch.groupValues[1]
        val infoHash = HASH.find(detailsPagePath)?.groupValues?.get(1)?.lowercase() ?: return null

        val name = titleMatch.groupValues[2].stripHtmlTags()
        if (name.isEmpty()) return null

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, infoHash),
            name = name,
            size = parseProviderSize(SIZE.find(block)?.groupValues?.get(1)),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
            detailsPageUrl = domain + detailsPagePath,
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<div class="search-item">""")
        private val TITLE = Regex("""<a href="(/detail/[a-f0-9]+\.html)"[^>]*>(.*?)</a>""")
        private val HASH = Regex("""/detail/([a-f0-9]+)\.html""")
        private val SIZE = Regex("""<span class="rrfs">([^<]+)</span>""")
    }
}
