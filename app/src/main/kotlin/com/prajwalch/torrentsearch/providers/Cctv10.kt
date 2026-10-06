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

import java.time.Instant

/** U3C3. */
class Cctv10(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "cctv10"
    override val name = "U3C3"
    override val defaultDomains = listOf("https://u3c3u3c3.u3c3u3c3u3c3.com")
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = Cctv10ResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        // The site announces the token accepted by its search endpoint on the
        // homepage, and a domain without one is only a landing page.
        val homePageHtml = networkClient.getText(url = "$domain/", headers = CHINESE_PROVIDER_HEADERS)
        val searchToken = extractSearchToken(homePageHtml) ?: return emptyList()

        val requestUrl = "$domain/?search2=$searchToken&search=${query.encodeURIComponent()}"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        if (!responseHtml.contains("torrent-list")) return emptyList()

        return resultsPageParser.parse(html = responseHtml, domain = domain)
    }

    private fun extractSearchToken(homePageHtml: String): String? =
        SEARCH_TOKEN.findAll(homePageHtml).lastOrNull()?.groupValues?.get(1)
            ?: FALLBACK_SEARCH_TOKEN.find(homePageHtml)?.groupValues?.get(1)

    private companion object {
        // The last occurrence is the one of the ongoing page, the earlier ones
        // belong to already expired tokens.
        private val SEARCH_TOKEN = Regex("""nmefafej\s*=\s*["']([a-zA-Z0-9]+)["']""")
        private val FALLBACK_SEARCH_TOKEN = Regex("""search2=([a-zA-Z0-9]+)""")
    }
}

private class Cctv10ResultsPageParser(
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
        val magnetMatch = MAGNET.find(block) ?: return null
        val infoHash = magnetMatch.groupValues[2].lowercase()

        val titleMatch = TITLE.find(block) ?: return null
        val name = titleMatch.groupValues[1].stripHtmlTags()
        if (name.isEmpty()) return null

        var size: String? = null
        var uploadDate: Instant? = null

        // The page has no labelled cells, so the columns are recognized by the
        // shape of their content.
        for (cell in CELL.findAll(block)) {
            val content = cell.groupValues[1].stripHtmlTags()

            if (size == null && SIZE.matches(content)) {
                size = parseProviderSize(content)
            }
            if (uploadDate == null && UPLOAD_DATE.matches(content)) {
                uploadDate = parseProviderDate(content)
            }
        }

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, infoHash),
            name = name,
            size = size,
            uploadDate = uploadDate,
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
            detailsPageUrl = "$domain/view?id=$infoHash",
        )
    }

    private companion object {
        private val BLOCK_SEPARATOR = Regex("""<tr class="default">""")
        private val MAGNET = Regex("href=\"(magnet:\\?xt=urn:btih:([a-fA-F0-9]{40})[^\"]*)\"")
        private val TITLE = Regex("""<a href="/view\?id=[^"]+"[^>]*>([\s\S]*?)</a>""")
        private val CELL = Regex("""<td[^>]*>([\s\S]*?)</td>""")
        private val SIZE = Regex("^\\d+(\\.\\d+)?\\s*(B|KB|MB|GB|TB)\$", RegexOption.IGNORE_CASE)
        private val UPLOAD_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\$")
    }
}
