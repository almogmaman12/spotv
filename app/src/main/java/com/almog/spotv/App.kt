package com.almog.spotv

import android.app.Application
import com.almog.spotv.lyrics.mobile.MobileLyricsSources
import com.almog.spotv.lyrics.repository.LyricsRepository

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Where the lyrics engine keeps its disk cache, and its lyric sources.
        LyricsRepository.defaultDiskCacheDir = cacheDir
        MobileLyricsSources.init(this)
    }
}
