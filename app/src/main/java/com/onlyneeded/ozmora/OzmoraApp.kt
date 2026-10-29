package com.onlyneeded.ozmora

import android.app.Application
import android.webkit.WebView
import java.io.File

/**
 * Ads run in their own process (":ads") so the ad SDK and its web views never share a process with
 * the game. A second process needs its own WebView data directory.
 */
class OzmoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isAdsProcess()) {
            try { WebView.setDataDirectorySuffix("ads") } catch (_: Exception) {}
        }
    }

    private fun isAdsProcess(): Boolean = try {
        File("/proc/self/cmdline").readText().trim('\u0000').endsWith(":ads")
    } catch (_: Exception) { false }
}
