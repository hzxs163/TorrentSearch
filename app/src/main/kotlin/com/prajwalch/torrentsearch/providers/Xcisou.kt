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

/**
 * x磁搜.
 *
 * Every result link is the 40 character info hash itself, so the magnet link is
 * available directly from the listing page.
 */
class Xcisou(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "xcisou"
    override val name = "x磁搜"
    override val defaultDomains = listOf(
        "https://cili.cisoux.com",
        "https://xbtbt.com",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = XcisouResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val requestUrl = "$domain/search/${query.encodeURIComponent()}/all/relevance/any/$PAGE"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    private companion object {
        private const val PAGE = "1"
    }
}

private class XcisouResultsPageParser(
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
        val infoHash = titleMatch.groupValues[1].lowercase()

        val name = titleMatch.groupValues[2]
            .stripHtmlTags()
            .decodeNumericApostrophe()
        if (name.isEmpty()) return null

        val seeders = SEEDERS
            .find(block)
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
            ?.toUIntOrNull()

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, infoHash),
            name = name,
            size = parseProviderSize(SIZE.find(block)?.groupValues?.get(1)),
            seeders = seeders,
            uploadDate = parseProviderDate(DATE.find(block)?.groupValues?.get(1)),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
            detailsPageUrl = "$domain/$infoHash",
        )
    }

    // `stripHtmlTags` covers the named entities but not the numeric apostrophe.
    private fun String.decodeNumericApostrophe(): String =
        replace("&#039;", "'").replace("&#39;", "'")

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<article class="result-item">""")
        private val TITLE = Regex("""<h2[^>]*><a href="/([a-f0-9]{40})"[^>]*>(.*?)</a></h2>""")
        private val SIZE = Regex("""meta-accent[\s\S]*?<span class="meta-value">([^<]+)</span>""")
        private val DATE = Regex("""发现 </span><span class="meta-value">([\d-]+)</span>""")
        private val SEEDERS = Regex("""meta-warm[\s\S]*?<span class="meta-value">([\d,]+)</span>""")
    }
}
