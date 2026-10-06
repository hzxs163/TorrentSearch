package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.encodeBase64
import com.prajwalch.torrentsearch.extension.stripHtmlTags
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.MagnetUriProvider
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 种子吧. */
class Zhongziba(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource), MagnetUriProvider {
    override val id = "zhongziba"
    override val name = "种子吧"
    override val defaultDomains = listOf(
        "https://seed8.org",
        "https://zhongziba.cc",
        "https://zzb10.vip",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = ZhongzibaResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val word = query.encodeBase64().trimEnd('=')
        val requestUrl = "$domain/search?wd=$word&sort=$SORT&page=$PAGE"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    override suspend fun getMagnetUri(url: String): String {
        val detailsPageHtml = networkClient.getText(url = url, headers = CHINESE_PROVIDER_HEADERS)

        val magnet = MAGNET_LINK.find(detailsPageHtml)?.groupValues?.get(1)
            ?: MAGNET.find(detailsPageHtml)?.value
            ?: error("Failed to retrieve magnet URI from '$url'")

        return TorrentUtils.createMagnetUri(TorrentUtils.getInfoHashFromMagnetUri(magnet))
    }

    private companion object {
        // Results ordered by relevance, as the app sorts them locally.
        private const val SORT = "rel"
        private const val PAGE = "1"

        private val MAGNET_LINK =
            Regex("""<textarea[^>]+id="magnetLink"[^>]*>\s*(magnet:\?xt=urn:btih:[a-fA-F0-9]{32,40})""")
        private val MAGNET = Regex("""magnet:\?xt=urn:btih:[a-fA-F0-9]{32,40}""")
    }
}

private class ZhongzibaResultsPageParser(
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
        val end = block.indexOf("</li>")
        val segment = if (end > 0) block.substring(0, end) else block

        val linkMatch = LINK.find(segment) ?: return null

        val href = linkMatch.groupValues[1].trim()
        val detailsPageUrl = if (href.startsWith("http")) href else domain + href
        if (!detailsPageUrl.contains("/seed/")) return null

        val name = linkMatch.groupValues[2].stripHtmlTags()
        if (name.isEmpty()) return null

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, detailsPageUrl),
            name = name,
            size = parseProviderSize(SIZE.find(segment)?.groupValues?.get(1)),
            uploadDate = parseProviderDate(UPLOAD_DATE.find(segment)?.groupValues?.get(1)),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.RequiresFetch(detailsPageUrl),
            detailsPageUrl = detailsPageUrl,
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<li class="media">""")
        private val LINK = Regex("<a[^>]+href=\"([^\"]+)\"[^>]*title=\"([^\"]*)\"")
        private val UPLOAD_DATE = Regex("""日期[：:]\s*<span[^>]*>(\d{4}-\d{2}-\d{2})</span>""")
        private val SIZE = Regex(
            """大小[：:]\s*<span[^>]*>([\d.]+\s*(?:B|KB|MB|GB|TB))</span>""",
            RegexOption.IGNORE_CASE,
        )
    }
}
