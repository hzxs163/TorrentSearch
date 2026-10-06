package com.prajwalch.torrentsearch.domain

import com.prajwalch.torrentsearch.provider.SearchProviderId

import java.time.Instant

import kotlinx.coroutines.flow.Flow

import kotlin.time.Duration

/**
 * Provides the currently known homepage of every search provider.
 *
 * Providers can be reachable under multiple domains which rotate over time.
 * A source keeps that list fresh by downloading it at runtime, so a provider
 * which moved to a new domain keeps working without an app update.
 */
interface ProviderDomainSource {
    /** Timestamp of the last successful update of the remote domain list. */
    val lastUpdatedAt: Flow<Instant?>

    /**
     * Returns the domains of the provider matching [providerId], ordered from
     * the most to the least currently trusted one.
     *
     * [defaultDomains] are the domains bundled with the app and are always kept
     * as a fallback when the remote list doesn't know the provider.
     */
    fun domainsFor(providerId: SearchProviderId, defaultDomains: List<String>): List<String>

    /** Reads the persisted domain list into memory, if not done already. */
    suspend fun ensureLoaded()

    /** Downloads and stores the latest domain list. */
    suspend fun update(): ProviderDomainsUpdateResult

    /**
     * Downloads the domain list when the stored one is older than [maxAge].
     *
     * Sources which rotate their subdomain on a fixed schedule need this, since
     * a stored domain stops resolving as soon as the slot changes.
     */
    suspend fun updateIfOlderThan(maxAge: Duration)
}

sealed interface ProviderDomainsUpdateResult {
    data object Error : ProviderDomainsUpdateResult

    data class Success(
        val numProviders: Int,
        val numDomains: Int,
        val updatedAt: Instant?,
    ) : ProviderDomainsUpdateResult
}
