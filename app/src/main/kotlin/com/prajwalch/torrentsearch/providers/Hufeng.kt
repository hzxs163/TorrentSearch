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

/** 虎风（蝴蝶）. */
class Hufeng(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "hufeng"
    override val name = "虎风"
    override val defaultDomains = listOf("https://huidie.sbs")
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = HufengResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val requestUrl = "$domain/search/${query.encodeURIComponent()}_ctime_$PAGE.html"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    private companion object {
        private const val PAGE = "1"
    }
}

private class HufengResultsPageParser(
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
        val linkMatch = LINK.find(block) ?: return null
        val infoHash = linkMatch.groupValues[1].lowercase()
        val name = linkMatch.groupValues[2].stripHtmlTags()
        if (name.isEmpty()) return null

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, infoHash),
            name = name,
            size = parseProviderSize(SIZE.find(block)?.groupValues?.get(1)),
            uploadDate = parseProviderDate(DATE.find(block)?.groupValues?.get(1)),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
            detailsPageUrl = "$domain/$infoHash.html",
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<div class="result">""")
        private val LINK = Regex("""<a[^>]+href="/([a-fA-F0-9]{40})\.html"[^>]*>([\s\S]*?)</a>""")
        private val DATE = Regex("""时间：\s*([^<]+)""")
        private val SIZE = Regex("""大小：\s*([^<]+)""")
    }
}
