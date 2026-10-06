package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.encodeBase64
import com.prajwalch.torrentsearch.extension.encodeURIComponent
import com.prajwalch.torrentsearch.extension.stripHtmlTags
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.MagnetUriProvider
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 磁力宝. */
class Cilibao(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource), MagnetUriProvider {
    override val id = "cilibao"
    override val name = "磁力宝"
    override val defaultDomains = listOf("https://clb21.vip")
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = CilibaoResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val word = query.encodeBase64().encodeURIComponent()
        val requestUrl = "$domain/s/$word?sort=$SORT&page=$PAGE"

        return resultsPageParser.parse(html = fetchHtml(url = requestUrl), domain = domain)
    }

    override suspend fun getMagnetUri(url: String): String {
        val detailsPageHtml = fetchHtml(url = url)

        val magnet = MAGNET_LINK.find(detailsPageHtml)?.groupValues?.get(1)
            ?: MAGNET.find(detailsPageHtml)?.value
            ?: error("Failed to retrieve magnet URI from '$url'")

        return TorrentUtils.createMagnetUri(TorrentUtils.getInfoHashFromMagnetUri(magnet))
    }

    /**
     * Reads a page of the site.
     *
     * Every path answers with a click verification page to the first request, and
     * only the request following an `act=challenge` submission on the same host
     * carries the content. The session cookie set by the verification page is kept
     * by [NetworkClient], so it is sent with both the submission and the retry.
     */
    private suspend fun fetchHtml(url: String): String {
        val responseHtml = networkClient.getText(url = url, headers = CHINESE_PROVIDER_HEADERS)
        if (!isChallengePage(responseHtml)) return responseHtml

        networkClient.submitForm(
            url = challengePageUrlOf(url),
            formData = mapOf("act" to "challenge"),
        )

        return networkClient.getText(url = url, headers = CHINESE_PROVIDER_HEADERS)
    }

    private fun isChallengePage(html: String): Boolean =
        html.contains("验证页面") || html.contains("cf-im-under-attack")

    private fun challengePageUrlOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url

        val pathStart = url.indexOf('/', startIndex = schemeEnd + 3)
        val originEnd = if (pathStart < 0) url.length else pathStart

        return url.substring(0, originEnd) + "/"
    }

    private companion object {
        // Results ordered by relevance, as the app sorts them locally.
        private const val SORT = "rel"
        private const val PAGE = "1"

        private val MAGNET_LINK =
            Regex("""<textarea[^>]*>\s*(magnet:\?xt=urn:btih:[a-fA-F0-9]{32,40})""")
        private val MAGNET = Regex("""magnet:\?xt=urn:btih:[a-fA-F0-9]{32,40}""")
    }
}

private class CilibaoResultsPageParser(
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

        val name = linkMatch.groupValues[2]
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .stripHtmlTags()
        if (name.isEmpty()) return null

        val detailsPageUrl = domain + linkMatch.groupValues[1]

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, detailsPageUrl),
            name = name,
            size = parseProviderSize(SIZE.find(block)?.groupValues?.get(1)),
            uploadDate = parseProviderDate(UPLOAD_DATE.find(block)?.groupValues?.get(1)),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.RequiresFetch(detailsPageUrl),
            detailsPageUrl = detailsPageUrl,
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<div class="search-item">""")
        private val LINK = Regex("""<h3><a href="(/detail/[A-Za-z0-9]+)"[^>]*>([\s\S]*?)</a></h3>""")
        private val UPLOAD_DATE = Regex("""创建时间[：:]\s*<b[^>]*>([^<]+)</b>""")
        private val SIZE = Regex("""文件大小[：:]\s*<b[^>]*>([^<]+)</b>""")
    }
}
