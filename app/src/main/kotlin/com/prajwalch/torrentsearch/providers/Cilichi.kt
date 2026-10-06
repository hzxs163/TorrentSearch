package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.encodeHex
import com.prajwalch.torrentsearch.extension.stripHtmlTags
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.MagnetUriProvider
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 磁力池.
 *
 * The listing page carries the title and size but never the magnet link, which
 * only appears on the detail page. That is expressed as [MagnetUri.RequiresFetch]
 * so the magnet is fetched lazily when the user opens a torrent instead of once
 * per result during the search.
 */
class Cilichi(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource), MagnetUriProvider {
    override val id = "cilichi"
    override val name = "磁力池"
    override val defaultDomains = listOf(
        "https://cilichi.com",
        "https://www.cilichi.net",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = CilichiResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val requestUrl = "$domain/cilichi/${query.encodeHex()}_${PAGE}$SORT_SUFFIX.html"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    override suspend fun getMagnetUri(url: String): String {
        val responseHtml = networkClient.getText(url = url, headers = CHINESE_PROVIDER_HEADERS)

        val infoHash = MAGNET.find(responseHtml)?.groupValues?.get(1)
            ?: FALLBACK_MAGNET.find(responseHtml)?.groupValues?.get(1)
            ?: error("Failed to retrieve magnet URI from '$url'")

        return TorrentUtils.createMagnetUri(infoHash.lowercase())
    }

    private companion object {
        private const val PAGE = "1"

        // The listing is sorted by id, the only ordering the app ever requests.
        private const val SORT_SUFFIX = "_id"

        private val MAGNET = Regex("""magnet:\?xt=urn:btih:([a-fA-F0-9]{32,40})""")

        // Some detail pages expose the hash without the `btih:` label.
        private val FALLBACK_MAGNET = Regex("""magnet:\?xt=urn:([a-fA-F0-9]{40})""")
    }
}

private class CilichiResultsPageParser(
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

        val detailsPageUrl = titleMatch.groupValues[1].trim().let { href ->
            if (href.startsWith("http")) href else domain + href
        }
        if (!detailsPageUrl.contains("/btcililianjie/")) return null

        val name = titleMatch.groupValues[2].stripHtmlTags()
        if (name.isEmpty()) return null

        val size = parseProviderSize(SIZE.find(block)?.groupValues?.get(1))

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, detailsPageUrl),
            name = name,
            size = size,
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.RequiresFetch(detailsPageUrl),
            detailsPageUrl = detailsPageUrl,
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<div class="card border-dashed border-2 mb-2">""")
        private val TITLE = Regex("""<a[^>]+href="([^"]*/btcililianjie/[^"]+)"[^>]*>([\s\S]*?)</a>""")
        private val SIZE = Regex(
            """文件[：:]\s*<span[^>]*>\s*([\d.]+\s*(?:B|KB|MB|GB|TB))""",
            RegexOption.IGNORE_CASE,
        )
    }
}
