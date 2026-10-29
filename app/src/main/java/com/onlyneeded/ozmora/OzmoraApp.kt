package com.onlyneeded.ozmora

import android.app.Application
import android.content.Context
import android.webkit.WebView
import java.io.File

/**
 * Ads run in their own process (":ads") so the ad SDK and its web views never share a process with
 * the game. A second process needs its own WebView data directory.
 */
class OzmoraApp : Application() {
    // attachBaseContext runs before content providers start (the ad SDK's provider uses WebView).
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (isAdsProcess()) {
            try { WebView.setDataDirectorySuffix("ads") } catch (_: Exception) {}
        }
    }

    private fun isAdsProcess(): Boolean = try {
        File("/proc/self/cmdline").readText().trim('\u0000').endsWith(":ads")
    } catch (_: Exception) { false }
}
