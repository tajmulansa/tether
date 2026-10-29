package com.onlyneeded.ozmora

import android.app.Activity
import android.os.Bundle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback

/** Transparent activity in the ":ads" process that shows one already-loaded ad, then reports back. */
class AdHostActivity : Activity() {
    private var kind = "rewarded"
    private var reported = false

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
                override fun onAdDismissedFullScreenContent() { done(if (earned) "reward" else "closed") }
                override fun onAdFailedToShowFullScreenContent(e: AdError) { done("failed") }
            }
            ad.show(this) { earned = true }
        } else {
            val ad = AdStore.interstitial
            AdStore.interstitial = null
            if (ad == null) { done("failed"); return }
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() { done("closed") }
                override fun onAdFailedToShowFullScreenContent(e: AdError) { done("failed") }
            }
            ad.show(this)
        }
    }

    override fun onDestroy() {
        if (!reported) { reported = true; AdStore.report(kind, "closed") }
        super.onDestroy()
    }
}
