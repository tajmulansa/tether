package com.onlyneeded.ozmora

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback

/** Transparent activity in the ":ads" process that shows one already-loaded ad, then reports back. */
class AdHostActivity : Activity() {
    private var kind = "rewarded"
    private var reported = false
    private var impression = false
    private val handler = Handler(Looper.getMainLooper())

    /** A blank "Test Ad" screen that never starts would trap the player: if no impression follows, give up and close everything. */
    private val watchdog = Runnable {
        if (!impression) {
            if (!reported) { reported = true; AdStore.report(kind, "failed") }
            finish()
            // closes the ad SDK's own screen too; the game sees the ads process restart and carries on
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, 400)
        }
    }

    private fun done(status: String) {
        if (!reported) { reported = true; AdStore.report(kind, status) }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra("kind") ?: "rewarded"
        if (kind == "rewarded") {
            val ad = AdStore.rewarded
            AdStore.rewarded = null
            if (ad == null) { done("failed"); return }
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdImpression() { impression = true }
                override fun onAdDismissedFullScreenContent() { done(if (earned) "reward" else "closed") }
                override fun onAdFailedToShowFullScreenContent(e: AdError) { done("failed") }
            }
            handler.postDelayed(watchdog, 9000)
            ad.show(this) { earned = true }
        } else {
            val ad = AdStore.interstitial
            AdStore.interstitial = null
            if (ad == null) { done("failed"); return }
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdImpression() { impression = true }
                override fun onAdDismissedFullScreenContent() { done("closed") }
                override fun onAdFailedToShowFullScreenContent(e: AdError) { done("failed") }
            }
            handler.postDelayed(watchdog, 9000)
            ad.show(this)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (!reported) { reported = true; AdStore.report(kind, "closed") }
        super.onDestroy()
    }
}
