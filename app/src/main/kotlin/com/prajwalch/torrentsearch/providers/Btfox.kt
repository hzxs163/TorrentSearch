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

/**
 * BtFox.
 *
 * The listing page links only to the details page, which carries the magnet in
 * an input element, so every torrent needs a second request to resolve it.
 */
class Btfox(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource), MagnetUriProvider {
    override val id = "btfox"
    override val name = "BtFox"
    override val defaultDomains = listOf(
        "https://btfox.xyz",
        "https://btfox20.top",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = BtfoxResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val word = query.encodeBase64().trimEnd('=')
        val requestUrl = "$domain/s?wd=$word&sort=$SORT&page=$PAGE"
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
        // The site's own default ordering; the app re-sorts the results locally.
        private const val SORT = "time"
        private const val PAGE = "1"

        private val MAGNET_LINK =
            Regex("""<input[^>]+id="mag-link"[^>]+value="(magnet:\?xt=urn:btih:[a-fA-F0-9]{32,40})"""")
        private val MAGNET = Regex("""magnet:\?xt=urn:btih:[a-fA-F0-9]{32,40}""")
    }
}

private class BtfoxResultsPageParser(
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
        val end = block.indexOf("<div class=\"box_border\">")
        val segment = if (end > 0) block.substring(0, end) else block

        val linkMatch = LINK.find(segment) ?: return null

        val href = linkMatch.groupValues[1].trim()
        val detailsPageUrl = if (href.startsWith("http")) href else domain + href
        if (!detailsPageUrl.contains("/info/")) return null

        val name = linkMatch.groupValues[2].stripHtmlTags()
        if (name.isEmpty()) return null

        // Size and date live only inside the note line, nowhere else in the block.
        val note = NOTE.find(segment)?.groupValues?.get(1)

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, detailsPageUrl),
            name = name,
            size = parseProviderSize(note?.let { SIZE.find(it)?.groupValues?.get(1) }),
            uploadDate = parseProviderDate(note?.let { DATE.find(it)?.groupValues?.get(1) }),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.RequiresFetch(detailsPageUrl),
            detailsPageUrl = detailsPageUrl,
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<div class="item">""")
        private val LINK = Regex("<a[^>]+href=\"([^\"]+)\"[^>]*title=\"([^\"]*)\"")
        private val NOTE = Regex("""<div class="threadlist_note">([\s\S]*?)</div>""")
        private val SIZE = Regex(
            """length[：:][\s\S]*?([\d.]+\s*(?:B|KB|MB|GB|TB))""",
            RegexOption.IGNORE_CASE,
        )
        private val DATE = Regex("""date[：:][\s\S]*?(\d{4}-\d{2}-\d{2})""")
    }
}
