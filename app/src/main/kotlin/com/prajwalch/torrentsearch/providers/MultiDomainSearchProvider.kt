package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.extension.stripHtmlTags
import com.prajwalch.torrentsearch.network.CloudflareChallengeException
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProvider
import com.prajwalch.torrentsearch.provider.SearchProviderId
import com.prajwalch.torrentsearch.util.FileSizeUtils
import com.prajwalch.torrentsearch.util.TorrentDateParser

import kotlinx.coroutines.CancellationException

import java.time.Instant

import kotlin.time.Duration

/** Request headers expected by the Chinese providers. */
internal val CHINESE_PROVIDER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "zh-CN,zh;q=0.9",
)

/**
 * A [SearchProvider] which is reachable under more than one domain.
 *
 * Every domain is tried in order until one of them returns results, so a source
 * staying available under a new domain keeps working. The live domain list is
 * refreshed at runtime by [ProviderDomainSource].
 */
abstract class MultiDomainSearchProvider(
    protected val networkClient: NetworkClient,
    private val domainSource: ProviderDomainSource,
) : SearchProvider {
    /** Domains bundled with the app, used when nothing fresher is known. */
    protected abstract val defaultDomains: List<String>

    /**
     * Keys under which this provider's domains are published.
     *
     * Defaults to [id], which is the name the domain list knows a provider by.
     */
    protected open val domainKeys: List<SearchProviderId> get() = listOf(id)

    /** Domains currently known for this provider, most trusted first. */
    protected val domains: List<String> get() = domainSource.domainsFor(domainKeys, defaultDomains)

    override val url: String get() = domains.firstOrNull() ?: defaultDomains.first()

    /**
     * Maximum accepted age of the downloaded domain list, for providers whose
     * domains are only valid for a limited time. `null` keeps the list until the
     * user or the app startup refreshes it.
     */
    protected open val domainsFreshness: Duration? get() = null

    override suspend fun search(query: String, category: Category): List<Torrent> {
        domainSource.ensureLoaded()
        domainsFreshness?.let { domainSource.updateIfOlderThan(it) }

        val candidates = (deriveDomains() + domains).filter { it.isNotBlank() }.distinct()
        var lastError: Throwable? = null

        for (domain in candidates) {
            try {
                val torrents = searchOn(domain, query, category)
                if (torrents.isNotEmpty()) return torrents
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                // The caller knows how to handle a challenged provider.
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Report the failure of the last domain when none of them worked, so the
        // search screen can show that this provider didn't answer.
        lastError?.let { throw it }

        return emptyList()
    }

    /**
     * Searches for [query] on the given [domain].
     */
    protected abstract suspend fun searchOn(
        domain: String,
        query: String,
        category: Category,
    ): List<Torrent>

    /**
     * Returns domains computed by the provider itself, preferred over the ones
     * known by [domainSource].
     *
     * Some sources publish their current subdomain for the ongoing time slot,
     * which can be derived locally instead of waiting for a download.
     */
    protected open suspend fun deriveDomains(): List<String> = emptyList()
}

/**
 * Parses a date string of a Chinese provider into an [Instant], accepting both
 * absolute and relative notations.
 */
internal fun parseProviderDate(raw: String?): Instant? {
    val value = raw?.stripHtmlTags()?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    val formats = listOf("yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd")

    return formats.firstNotNullOfOrNull { format ->
        runCatching { TorrentDateParser.parse(date = value, format = format) }.getOrNull()
    }
        ?: runCatching { TorrentDateParser.parseIso(value) }.getOrNull()
        ?: runCatching { TorrentDateParser.tryParseRelative(value) }.getOrNull()
}

/** Normalizes a size string of a Chinese provider, e.g. `1.20 GB`. */
internal fun parseProviderSize(raw: String?): String? = raw
    ?.stripHtmlTags()
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let(FileSizeUtils::normalizeSize)
