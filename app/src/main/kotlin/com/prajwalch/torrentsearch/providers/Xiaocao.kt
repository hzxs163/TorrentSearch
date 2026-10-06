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
 * 小草磁力.
 *
 * The source publishes a new `xccl<digits>.xyz` domain periodically, so the
 * bundled list is only a starting point and the live list drives the search.
 */
class Xiaocao(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "xiaocao"
    override val name = "小草磁力"
    override val defaultDomains = listOf(
        "https://www.xccl279.xyz",
        "https://www.xccl278.xyz",
        "https://www.xccl277.xyz",
        "https://www.xccl276.xyz",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = XiaocaoResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val requestUrl = "$domain/search/kw-${query.encodeURIComponent()}-$PAGE.html"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        if (!responseHtml.contains("search-item")) return emptyList()

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    private companion object {
        private const val PAGE = "1"
    }
}

private class XiaocaoResultsPageParser(
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
        val name = titleMatch.groupValues[3].stripHtmlTags()
        if (name.isEmpty()) return null

        val detailsPageUrl = "$domain${titleMatch.groupValues[1]}"
        val size = parseProviderSize(SIZE.find(block)?.groupValues?.get(1))
        val uploadDate = parseProviderDate(DATE.find(block)?.groupValues?.get(1))

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
        private val BLOCK_SEPARATOR = Regex("""<div class="search-item[^"]*">""")
        private val TITLE = Regex("""<a[^>]+href="(/hash/([a-fA-F0-9]{40})\.html)"[^>]*>([\s\S]*?)</a>""")
        private val SIZE = Regex("""文件大小:\s*<b[^>]*>([^<]+)</b>""")
        private val DATE = Regex("""创建时间:(?:&nbsp;|\s)*<b>([^<]+)</b>""")
    }
}
