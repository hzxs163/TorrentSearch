package com.prajwalch.torrentsearch.data.repository

import android.util.Base64
import android.util.Log

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.domain.ProviderDomainsUpdateResult
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProviderId

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import java.time.Instant
import java.time.OffsetDateTime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Stores and refreshes the live domains of the search providers.
 *
 * The list is published by the Magnet-search repository, whose continuous
 * integration job probes every source periodically and rewrites it. Keeping a
 * copy of it in the app allows a provider to transparently follow a source when
 * it moves to a new domain.
 */
class ProviderDomainRepository(
    private val dataStore: DataStore<Preferences>,
    private val networkClient: NetworkClient,
) : ProviderDomainSource {
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var domainsByProviderId: Map<SearchProviderId, List<String>> = emptyMap()

    @Volatile
    private var isLoaded = false

    @Volatile
    private var lastUpdateAttemptAt: Instant? = null

    override val lastUpdatedAt: Flow<Instant?> = dataStore.data
        .map { preferences -> parseTimestamp(preferences[DOMAINS_UPDATED_AT]) }

    override fun domainsFor(
        providerIds: List<SearchProviderId>,
        defaultDomains: List<String>,
    ): List<String> {
        val defaults = normalizeDomains(defaultDomains)
        val remote = providerIds
            .flatMap { providerId -> domainsByProviderId[providerId].orEmpty() }
            .let(::normalizeDomains)

        // Known-good addresses of the source come first, the ones bundled with
        // the app are kept as a fallback.
        return (remote + defaults).distinct()
    }

    override suspend fun ensureLoaded() {
        if (isLoaded) return

        val preferences = dataStore.data.first()

        domainsByProviderId = preferences[DOMAINS_SNAPSHOT]?.let(::toSnapshot)
            ?.domainsByProviderId
            .orEmpty()
        isLoaded = true
    }

    override suspend fun update(): ProviderDomainsUpdateResult {
        for (sourceUrl in DOMAIN_LIST_URLS) {
            val rawText = try {
                networkClient.getText(
                    url = sourceUrl,
                    headers = mapOf("Accept" to "application/json, text/plain, */*"),
                )
            } catch (e: Exception) {
                Log.i(TAG, "Domain list unavailable from $sourceUrl", e)
                continue
            }

            val snapshot = toSnapshot(rawText)
            if (snapshot == null || snapshot.domainsByProviderId.isEmpty()) {
                Log.w(TAG, "Domain list from $sourceUrl is not usable")
                continue
            }

            val updatedAt = snapshot.publishedAt ?: Instant.now()

            dataStore.edit { preferences ->
                preferences[DOMAINS_SNAPSHOT] = snapshot.json
                preferences[DOMAINS_UPDATED_AT] = updatedAt.toString()
            }

            domainsByProviderId = snapshot.domainsByProviderId
            isLoaded = true

            Log.i(TAG, "Loaded domains of ${snapshot.domainsByProviderId.size} providers from $sourceUrl")

            return ProviderDomainsUpdateResult.Success(
                numProviders = snapshot.domainsByProviderId.size,
                numDomains = snapshot.domainsByProviderId.values.sumOf { it.size },
                updatedAt = updatedAt,
            )
        }

        return ProviderDomainsUpdateResult.Error
    }

    override suspend fun updateIfOlderThan(maxAge: Duration) {
        val updatedAt = lastUpdatedAt.first()
        val ageMillis = Instant.now().toEpochMilli() - (updatedAt?.toEpochMilli() ?: 0L)
        if (updatedAt != null && ageMillis < maxAge.inWholeMilliseconds) return

        // Avoid hammering every mirror when none of them is reachable.
        val lastAttemptAt = lastUpdateAttemptAt
        if (lastAttemptAt != null &&
            Instant.now().toEpochMilli() - lastAttemptAt.toEpochMilli() < UPDATE_RETRY_COOLDOWN.inWholeMilliseconds
        ) {
            return
        }

        lastUpdateAttemptAt = Instant.now()
        update()
    }

    private fun normalizeDomains(domains: List<String>): List<String> = domains
        .map { domain -> domain.trim().removeSuffix("/") }
        .filter { domain -> domain.startsWith("http://") || domain.startsWith("https://") }
        .distinct()

    private fun toSnapshot(rawText: String): Snapshot? {
        val root = runCatching { jsonParser.parseToJsonElement(rawText) }.getOrNull()
        val obj = unwrapGitHubApiContent(root) ?: return null

        val publishedAt = obj["updated_at"]?.jsonPrimitive?.contentOrNull?.let(::parseTimestamp)

        val domains = obj.entries
            .mapNotNull { (key, value) ->
                val providerDomains = (value as? JsonArray)
                    ?.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
                    ?.let(::normalizeDomains)
                    .orEmpty()

                if (providerDomains.isEmpty()) null else key to providerDomains
            }
            .toMap()

        if (domains.isEmpty()) return null

        return Snapshot(json = obj.toString(), publishedAt = publishedAt, domainsByProviderId = domains)
    }

    /**
     * Returns the given JSON object, decoding its base64 payload first when the
     * response comes from the GitHub contents API.
     */
    private fun unwrapGitHubApiContent(element: JsonElement?): JsonObject? {
        val obj = element as? JsonObject ?: return null

        val encodedContent = obj["content"]?.jsonPrimitive?.contentOrNull ?: return obj

        val decoded = runCatching {
            String(
                Base64.decode(encodedContent.replace("\n", ""), Base64.DEFAULT),
                Charsets.UTF_8,
            )
        }.getOrNull() ?: return null

        return runCatching { jsonParser.parseToJsonElement(decoded).jsonObject }.getOrNull()
    }

    private data class Snapshot(
        val json: String,
        val publishedAt: Instant?,
        val domainsByProviderId: Map<SearchProviderId, List<String>>,
    )

    private companion object {
        private const val TAG = "ProviderDomainRepository"

        /**
         * Mirrors of the published domain list, ordered by how well they are
         * reachable from the networks these providers are usually used on.
         */
        private val DOMAIN_LIST_URLS = listOf(
            "https://soubt.pages.dev/domains.json",
            "https://raw.githubusercontent.com/hzxs163/Magnet-search/main/domains.json",
            "https://api.github.com/repos/hzxs163/Magnet-search/contents/domains.json",
            "https://cdn.jsdelivr.net/gh/hzxs163/Magnet-search@main/domains.json",
            "https://fastly.jsdelivr.net/gh/hzxs163/Magnet-search@main/domains.json",
        )

        private val DOMAINS_SNAPSHOT = stringPreferencesKey("provider_domains_snapshot")
        private val DOMAINS_UPDATED_AT = stringPreferencesKey("provider_domains_updated_at")

        /** How long to wait before contacting the mirrors again after a failure. */
        private val UPDATE_RETRY_COOLDOWN = 5.minutes

        private fun parseTimestamp(raw: String?): Instant? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            return runCatching { OffsetDateTime.parse(value).toInstant() }
                .recoverCatching { Instant.parse(value) }
                .getOrNull()
        }
    }
}
