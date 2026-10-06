package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.decodeBase64ToString
import com.prajwalch.torrentsearch.extension.encodeBase64
import com.prajwalch.torrentsearch.extension.stripHtmlTags
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream

/** 磁力猫. */
class Cilimao(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource) {
    override val id = "cilimao"
    override val name = "磁力猫"
    override val defaultDomains = listOf("https://clm10.vip")
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsPageParser = CilimaoResultsPageParser(id, name)

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val word = query.encodeBase64().trimEnd('=')
        val requestUrl = "$domain/search?word=$word&sort=$SORT&p=$PAGE"
        val responseHtml = networkClient.getText(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)

        val links = resultsPageParser.parseLinks(html = responseHtml, domain = domain)
        if (links.isEmpty()) return emptyList()

        return fetchTorrents(links)
    }

    private suspend fun fetchTorrents(links: List<CilimaoTorrentLink>): List<Torrent> = coroutineScope {
        // Size and upload date are only published on the details page of a result,
        // so every result costs a second request which is done with limits.
        val permits = Semaphore(DETAIL_FETCH_CONCURRENCY)

        links.map { link ->
            async { permits.withPermit { fetchTorrent(link) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchTorrent(link: CilimaoTorrentLink): Torrent? =
        try {
            val detailsPageHtml = networkClient.getText(
                url = link.detailsPageUrl,
                headers = CHINESE_PROVIDER_HEADERS,
            )

            resultsPageParser.parseDetails(html = detailsPageHtml, link = link)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A single unreadable details page must not drop the whole search.
            null
        }

    private companion object {
        // Results ordered by relevance, as the app sorts them locally.
        private const val SORT = "rele"
        private const val PAGE = "1"
        private const val DETAIL_FETCH_CONCURRENCY = 5
    }
}

/** A result of the search page, before its details page has been read. */
private data class CilimaoTorrentLink(
    val name: String,
    val detailsPageUrl: String,
)

private class CilimaoResultsPageParser(
    private val providerId: SearchProviderId,
    private val providerName: String,
) {
    suspend fun parseLinks(html: String, domain: String): List<CilimaoTorrentLink> {
        val decoded = decodeAtobPayload(html) ?: return emptyList()

        return withContext(Dispatchers.Default) {
            LINK.findAll(decoded)
                .map { match ->
                    CilimaoTorrentLink(
                        name = match.groupValues[2].stripHtmlTags(),
                        detailsPageUrl = "$domain${match.groupValues[1]}",
                    )
                }
                .filter { link -> link.name.isNotEmpty() }
                .toList()
        }
    }

    suspend fun parseDetails(html: String, link: CilimaoTorrentLink): Torrent? {
        val decoded = decodeAtobPayload(html) ?: return null

        val magnet = MAGNET.find(decoded)?.groupValues?.get(1) ?: return null
        val infoHash = TorrentUtils.getInfoHashFromMagnetUri(magnet)

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, infoHash),
            name = link.name,
            size = parseProviderSize(SIZE.find(decoded)?.groupValues?.get(1)),
            uploadDate = parseProviderDate(UPLOAD_DATE.find(decoded)?.groupValues?.get(1)),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.Available(TorrentUtils.createMagnetUri(infoHash)),
            detailsPageUrl = link.detailsPageUrl,
        )
    }

    private companion object {
        private val ATOB_PAYLOAD = Regex("""window\.atob\("([^"]+)"\)""")
        private val LINK = Regex("""<a[^>]+href="(/information/[a-zA-Z0-9]+)"[^>]*>([\s\S]*?)</a>""")
        private val MAGNET = Regex("href=\"(magnet:\\?xt=urn:btih:[a-fA-F0-9]{40}[^\"]*)\"")
        private val SIZE = Regex("""文件大小：</b>([^<]+)</b>""")
        private val UPLOAD_DATE = Regex("""收录时间：</b>\s*([^<]+)""")

        /**
         * The pages are shipped as `window.atob("<percent encoded html>")`, so the
         * payload has to be base64 decoded and percent decoded before it can be read.
         */
        private fun decodeAtobPayload(html: String): String? = ATOB_PAYLOAD
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.decodeBase64ToString()
            ?.percentDecoded()
    }
}

private const val HEX_RADIX = 16

/** Reverses what JavaScript's `decodeURIComponent` does to [this]. */
private fun String.percentDecoded(): String {
    val bytes = ByteArrayOutputStream()
    var index = 0

    while (index < length) {
        val char = this[index]

        if (char == '%' && index + 2 < length) {
            val high = Character.digit(this[index + 1], HEX_RADIX)
            val low = Character.digit(this[index + 2], HEX_RADIX)

            if (high >= 0 && low >= 0) {
                bytes.write(high * HEX_RADIX + low)
                index += 3
                continue
            }
        }

        char.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
            bytes.write(byte.toInt() and 0xFF)
        }
        index++
    }

    return String(bytes.toByteArray(), Charsets.UTF_8)
}
