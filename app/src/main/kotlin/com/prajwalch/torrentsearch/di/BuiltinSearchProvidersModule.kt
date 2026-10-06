package com.prajwalch.torrentsearch.di

import com.prajwalch.torrentsearch.domain.ProviderDomainSource
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.provider.SearchProvider
import com.prajwalch.torrentsearch.providers.AniLibria
import com.prajwalch.torrentsearch.providers.AniRena
import com.prajwalch.torrentsearch.providers.AnimeTosho
import com.prajwalch.torrentsearch.providers.AudioBookBay
import com.prajwalch.torrentsearch.providers.BTDigg
import com.prajwalch.torrentsearch.providers.BangumiMoe
import com.prajwalch.torrentsearch.providers.BitSearch
import com.prajwalch.torrentsearch.providers.BlueRoms
import com.prajwalch.torrentsearch.providers.Bt4g
import com.prajwalch.torrentsearch.providers.Btsow
import com.prajwalch.torrentsearch.providers.Cctv10
import com.prajwalch.torrentsearch.providers.Cilibaike
import com.prajwalch.torrentsearch.providers.Cilibao
import com.prajwalch.torrentsearch.providers.Cilichi
import com.prajwalch.torrentsearch.providers.Cilimao
import com.prajwalch.torrentsearch.providers.Ciliso
import com.prajwalch.torrentsearch.providers.Dmhy
import com.prajwalch.torrentsearch.providers.EpubLibre
import com.prajwalch.torrentsearch.providers.Ext
import com.prajwalch.torrentsearch.providers.Eztv
import com.prajwalch.torrentsearch.providers.FileMood
import com.prajwalch.torrentsearch.providers.FitGirlRepacks
import com.prajwalch.torrentsearch.providers.Hufeng
import com.prajwalch.torrentsearch.providers.InternetArchive
import com.prajwalch.torrentsearch.providers.Knaben
import com.prajwalch.torrentsearch.providers.LimeTorrents
import com.prajwalch.torrentsearch.providers.LinuxTracker
import com.prajwalch.torrentsearch.providers.MegaPeer
import com.prajwalch.torrentsearch.providers.Miaocili
import com.prajwalch.torrentsearch.providers.Mikan
import com.prajwalch.torrentsearch.providers.MyPornClub
import com.prajwalch.torrentsearch.providers.NekoBT
import com.prajwalch.torrentsearch.providers.NoNameClub
import com.prajwalch.torrentsearch.providers.Nyaa
import com.prajwalch.torrentsearch.providers.OxTorrent
import com.prajwalch.torrentsearch.providers.Rutor
import com.prajwalch.torrentsearch.providers.SubsPlease
import com.prajwalch.torrentsearch.providers.Sukebei
import com.prajwalch.torrentsearch.providers.Taocili
import com.prajwalch.torrentsearch.providers.ThePirateBay
import com.prajwalch.torrentsearch.providers.TheRarBg
import com.prajwalch.torrentsearch.providers.ThirteenThirtySevenX
import com.prajwalch.torrentsearch.providers.TokyoToshokan
import com.prajwalch.torrentsearch.providers.Torrent9
import com.prajwalch.torrentsearch.providers.TorrentDatabase
import com.prajwalch.torrentsearch.providers.TorrentDownload
import com.prajwalch.torrentsearch.providers.TorrentDownloads
import com.prajwalch.torrentsearch.providers.TorrentGalaxy
import com.prajwalch.torrentsearch.providers.TorrentKitty
import com.prajwalch.torrentsearch.providers.TorrentsCSV
import com.prajwalch.torrentsearch.providers.Torrentz
import com.prajwalch.torrentsearch.providers.TpbWeb
import com.prajwalch.torrentsearch.providers.UIndex
import com.prajwalch.torrentsearch.providers.XXXClub
import com.prajwalch.torrentsearch.providers.XXXTracker
import com.prajwalch.torrentsearch.providers.Xcisou
import com.prajwalch.torrentsearch.providers.Xiaocao
import com.prajwalch.torrentsearch.providers.Yts
import com.prajwalch.torrentsearch.providers.Yuhuage
import com.prajwalch.torrentsearch.providers.ZeroMagnet
import com.prajwalch.torrentsearch.providers.Zhongziba

import org.koin.dsl.module

private fun provideBuiltinSearchProviders(
    networkClient: NetworkClient,
    domainSource: ProviderDomainSource,
): List<SearchProvider> =
    listOf(
        AniLibria(networkClient),
        AniRena(networkClient),
        AnimeTosho(networkClient),
        AudioBookBay(networkClient),
        BTDigg(networkClient),
        BangumiMoe(networkClient),
        BitSearch(networkClient),
        BlueRoms(networkClient),
        Bt4g(networkClient),
        Btsow(networkClient),
        Cctv10(networkClient, domainSource),
        Cilibaike(networkClient, domainSource),
        Cilibao(networkClient, domainSource),
        Cilichi(networkClient, domainSource),
        Cilimao(networkClient, domainSource),
        Ciliso(networkClient, domainSource),
        Dmhy(networkClient),
        EpubLibre(networkClient),
        Ext(networkClient),
        Eztv(networkClient),
        FileMood(networkClient),
        FitGirlRepacks(networkClient),
        Hufeng(networkClient, domainSource),
        InternetArchive(networkClient),
        Knaben(networkClient),
        LimeTorrents(networkClient),
        LinuxTracker(networkClient),
        MegaPeer(networkClient),
        Miaocili(networkClient, domainSource),
        Mikan(networkClient),
        MyPornClub(networkClient),
        NekoBT(networkClient),
        NoNameClub(networkClient),
        Nyaa(networkClient),
        OxTorrent(networkClient),
        Rutor(networkClient),
        SubsPlease(networkClient),
        Sukebei(networkClient),
        ThePirateBay(networkClient),
        TheRarBg(networkClient),
        ThirteenThirtySevenX(networkClient),
        TokyoToshokan(networkClient),
        Torrent9(networkClient),
        TorrentDatabase(networkClient),
        TorrentDownload(networkClient),
        TorrentDownloads(networkClient),
        TorrentGalaxy(networkClient, domainSource),
        TorrentKitty(networkClient),
        TorrentsCSV(networkClient),
        Torrentz(networkClient),
        TpbWeb(networkClient, domainSource),
        UIndex(networkClient),
        XXXClub(networkClient),
        XXXTracker(networkClient),
        Xcisou(networkClient, domainSource),
        Xiaocao(networkClient, domainSource),
        Yts(networkClient),
        Yuhuage(networkClient, domainSource),
        ZeroMagnet(networkClient),
        Zhongziba(networkClient, domainSource),
    )

val builtinSearchProvidersModule = module {
    single<List<SearchProvider>> {
        provideBuiltinSearchProviders(networkClient = get(), domainSource = get())
    }
}