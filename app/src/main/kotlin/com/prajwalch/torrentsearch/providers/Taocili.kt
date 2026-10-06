package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.MagnetUri
import com.prajwalch.torrentsearch.domain.model.SearchProviderSafety
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.asObject
import com.prajwalch.torrentsearch.extension.encodeBase64
import com.prajwalch.torrentsearch.extension.encodeURIComponent
import com.prajwalch.torrentsearch.extension.getArray
import com.prajwalch.torrentsearch.extension.getLong
import com.prajwalch.torrentsearch.extension.getString
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.MagnetUriProvider
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.FileSizeUtils
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

import java.time.Instant

/** 淘磁力. */
class Taocili(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
) : MultiDomainSearchProvider(networkClient, domainSource), MagnetUriProvider {
    override val id = "taocili"
    override val name = "淘磁力"
    override val defaultDomains = listOf(
        "https://taocili12.shop",
        "https://taocili10.shop",
    )
    override val supportedCategories = setOf(Category.Other)
    override val safety = SearchProviderSafety.Safe
    override val enabledByDefault = true

    private val resultsJsonParser = TaociliResultsJsonParser(id, name)

    override suspend fun deriveDomains(): List<String> = runCatching {
        networkClient.getText(url = PUBLISH_PAGE_URL, headers = CHINESE_PROVIDER_HEADERS)
    }.getOrNull()
        ?.let { page -> PUBLISH_PAGE_ENTRY.find(page)?.value }
        ?.let { entry -> PUBLISH_PAGE_DOMAINS.findAll(entry).map { it.groupValues[1] }.distinct().toList() }
        .orEmpty()

    override suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent> {
        val keyword = query.encodeBase64().encodeURIComponent()
        val requestUrl = "$domain/apis/search?keyword=$keyword&base64=1&detail=1" +
            "&start=$START&count=$COUNT&type=all&sort=$SORT"

        // The site answers some requests with an error page instead of JSON, so a
        // second attempt is made with plain browser headers.
        val responseJson = runCatching {
            networkClient.getJson(url = requestUrl, headers = CHINESE_PROVIDER_HEADERS)
        }.getOrNull()
            ?: networkClient.getJson(url = requestUrl, headers = BROWSER_HEADERS)
            ?: return emptyList()

        return resultsJsonParser.parse(responseJson = responseJson, domain = domain)
    }

    override suspend fun getMagnetUri(url: String): String {
        val detailsPageHtml = networkClient.getText(url = url, headers = detailsPageHeaders(url))
        val magnet = MAGNET.find(detailsPageHtml)?.value
            ?: error("Failed to retrieve magnet URI from '$url'")

        return TorrentUtils.createMagnetUri(TorrentUtils.getInfoHashFromMagnetUri(magnet))
    }

    private fun detailsPageHeaders(url: String): Map<String, String> = mapOf(
        "Referer" to originOf(url = url),
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url

        val pathStart = url.indexOf('/', startIndex = schemeEnd + 3)
        val originEnd = if (pathStart < 0) url.length else pathStart

        return url.substring(0, originEnd) + "/"
    }

    private companion object {
        /** Publish page listing the current domains of the source. */
        private const val PUBLISH_PAGE_URL = "https://wangzhi.icu/config.js"

        private const val START = "0"
        private const val COUNT = "20"

        // Results ordered by relevance, as the app sorts them locally.
        private const val SORT = "default"

        private val PUBLISH_PAGE_ENTRY = Regex("""\{[^{}]*淘磁力[^{}]*\}""")
        private val PUBLISH_PAGE_DOMAINS = Regex("""['"](https?://[^'"]+)['"]""")
        private val MAGNET = Regex("""magnet:\?xt=urn:btih:[a-fA-F0-9]{40}""")

        private val BROWSER_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
    }
}

private data class TaociliRow(
    val sourceId: String,
    val name: String,
    val sizeInBytes: Long?,
    val uploadTimeMillis: Long?,
)

private class TaociliResultsJsonParser(
    private val providerId: SearchProviderId,
    private val providerName: String,
) {
    suspend fun parse(responseJson: JsonElement, domain: String): List<Torrent> =
        withContext(Dispatchers.Default) {
            val response = responseJson.asObject()

            // The API responds with a code even when it has nothing to offer.
            if (response.getString("code") != "0") return@withContext emptyList()

            response.getArray("items")
                ?.mapNotNull(::toRowOrNull)
                ?.take(MAX_ROWS)
                ?.map { row -> toTorrent(row, domain) }
                .orEmpty()
        }

    private fun toRowOrNull(item: JsonElement): TaociliRow? {
        return runCatching {
            val row = item.asObject()
            val name = row.getString("name")?.takeIf { it.isNotBlank() } ?: return null
            val sourceId = row.getString("_id")?.takeIf { it.isNotBlank() } ?: return null

            TaociliRow(
                sourceId = sourceId,
                name = name,
                sizeInBytes = row.getLong("len"),
                uploadTimeMillis = row.getLong("atime"),
            )
        }.getOrNull()
    }

    private fun toTorrent(row: TaociliRow, domain: String): Torrent {
        val detailsPageUrl = "$domain/magnet/${row.sourceId}"

        return Torrent(
            id = TorrentUtils.createTorrentId(providerId, row.sourceId),
            name = row.name,
            size = row.sizeInBytes?.takeIf { it > 0 }?.toFloat()?.let { bytes ->
                FileSizeUtils.formatBytes(bytes)
            },
            uploadDate = row.uploadTimeMillis?.let(Instant::ofEpochMilli),
            category = Category.Other,
            providerName = providerName,
            magnetUri = MagnetUri.RequiresFetch(detailsPageUrl),
            detailsPageUrl = detailsPageUrl,
        )
    }

    private companion object {
        private const val MAX_ROWS = 20
    }
}
