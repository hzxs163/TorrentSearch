package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.encodeURIComponent
import com.prajwalch.torrentsearch.extension.stripHtmlTags
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 磁力百科.
 *
 * The source is reachable under a subdomain which is derived from the current
 * time slot, therefore the domain list is refreshed whenever it got older than
 * [domainsFreshness].
 */
class Cilibaike(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "cilibaike"
    override val name = "磁力百科"
    override val defaultDomains = listOf(
        "https://nmn7ffoi.8881067.xyz",
        "https://bb04fe43.8881068.xyz",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true
    override val domainsFreshness: Duration = 10.minutes

    private val resultsPageParser = CilibaikeResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val requestUrl = "$domain/search-${query.encodeURIComponent()}-0-$ORDER-$PAGE.html?lang=zh_CN"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        if (!responseHtml.contains("resource-card")) return emptyList()

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    private companion object {
        // Search results ordered by relevance, as the app sorts them locally.
        private const val ORDER = "0"
        private const val PAGE = "1"
    }
}

/**
 * 磁力搜.
 *
 * Shares the engine and the page layout of [Cilibaike], only its domains and the
 * default homepage differ.
 */
class Ciliso(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "ciliso"
    override val name = "磁力搜"
    override val defaultDomains = listOf(
        "https://ol8p12x9.3030117.xyz",
        "https://2t1n0hrp.3030116.xyz",
        "https://jn7rrstk.cls116.buzz",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true
    override val domainsFreshness: Duration = 10.minutes

    private val resultsPageParser = CilibaikeResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val requestUrl = "$domain/search-${query.encodeURIComponent()}-0-$ORDER-$PAGE.html?lang=zh_CN"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        if (!responseHtml.contains("resource-card")) return emptyList()

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    private companion object {
        private const val ORDER = "0"
        private const val PAGE = "1"
    }
}

internal class CilibaikeResultsPageParser(
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
        val infoHash = titleMatch.groupValues[2].lowercase()
        val name = titleMatch.groupValues[3]
            .stripHtmlTags()
            .replace(CATEGORY_PREFIX, "")
            .trim()
        if (name.isEmpty()) return null

        val detailsPageUrl = "$domain${titleMatch.groupValues[1]}"
        val meta = META.find(block)?.groupValues?.get(1)
        val size = parseProviderSize(meta?.let { SIZE.find(it)?.groupValues?.get(1) })
        val uploadDate = parseProviderDate(meta?.let { UPLOAD_DATE.find(it)?.groupValues?.get(1) })

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, infoHash),
            name = name,
            size = size,
            uploadDate = uploadDate,
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
            detailsPageUrl = detailsPageUrl,
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<article class="resource resource-card"[^>]*>""")
        private val TITLE = Regex(
            """<h2><a[^>]+href="(/hash/([a-fA-F0-9]{40})\.html)"[^>]*>([\s\S]*?)</a></h2>"""
        )
        private val CATEGORY_PREFIX = Regex("^【[^】]*】\\s*")
        private val META = Regex("""<div class="meta resource-meta">([\s\S]*?)</div>""")
        private val SIZE = Regex("""大小：\s*<span>([^<]+)</span>""")
        private val UPLOAD_DATE = Regex("""添加时间：\s*<span>([^<]+)</span>""")
    }
}
