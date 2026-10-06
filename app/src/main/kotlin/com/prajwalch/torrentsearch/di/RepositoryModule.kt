package com.prajwalch.torrentsearch.di

import com.prajwalch.torrentsearch.data.repository.BookmarkRepository
import com.prajwalch.torrentsearch.data.repository.ProviderDomainRepository
import com.prajwalch.torrentsearch.data.repository.SearchHistoryRepository
import com.prajwalch.torrentsearch.data.repository.SettingsRepository
import com.prajwalch.torrentsearch.data.repository.TorznabConfigRepository
import com.prajwalch.torrentsearch.data.repository.ViewedTorrentRepository
import com.prajwalch.torrentsearch.domain.ProviderDomainSource

import org.koin.dsl.module

val repositoryModule = module {
    single { BookmarkRepository(dao = get()) }
    single { SearchHistoryRepository(dao = get()) }
    single { SettingsRepository(dataStore = get()) }
    single { TorznabConfigRepository(dao = get()) }
    single { ViewedTorrentRepository(dao = get()) }
    single { ProviderDomainRepository(dataStore = get(), networkClient = get()) }
    single<ProviderDomainSource> { get<ProviderDomainRepository>() }
}
