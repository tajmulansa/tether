package com.onlyneeded.ozmora

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/** Runs in the ":ads" process. Loads ads on request from the game process; shows happen in AdHostActivity. */
class AdsService : Service() {
    private var loadingR = false
    private var loadingI = false

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.replyTo != null) AdStore.replyTo = msg.replyTo
        when (msg.what) {
            AdStore.MSG_LOAD_REWARDED -> withSdk { loadRewarded() }
            AdStore.MSG_LOAD_INTERSTITIAL -> withSdk { loadInterstitial() }
        }
        true
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private var sdkState = 0            // 0 = not started, 1 = starting, 2 = ready
    private val pending = ArrayList<() -> Unit>()

    /** Runs [then] once the ad SDK is initialised (starting it on first use). */
    private fun withSdk(then: () -> Unit) {
        when (sdkState) {
            2 -> then()
            1 -> pending.add(then)
            else -> {
                sdkState = 1; pending.add(then)
                MobileAds.initialize(applicationContext) {
                    Handler(Looper.getMainLooper()).post {
                        sdkState = 2
                        val todo = ArrayList(pending); pending.clear()
                        todo.forEach { it() }
                    }
                }
            }
        }
    }

    private fun loadRewarded() {
        if (loadingR || AdStore.rewarded != null) { if (AdStore.rewarded != null) AdStore.report("rewarded", "loaded"); return }
        loadingR = true
        RewardedAd.load(applicationContext, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadingR = false; AdStore.rewarded = ad; AdStore.report("rewarded", "loaded")
                }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    loadingR = false; AdStore.rewarded = null
                    Log.d("OzmoraAds", "rewarded failed ${e.code} ${e.message}")
                    AdStore.report("rewarded", "loadfailed", "code ${e.code} ${e.message}")
                }
            })
    }

    private fun loadInterstitial() {
        if (loadingI || AdStore.interstitial != null) { if (AdStore.interstitial != null) AdStore.report("interstitial", "loaded"); return }
        loadingI = true
        InterstitialAd.load(applicationContext, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingI = false; AdStore.interstitial = ad; AdStore.report("interstitial", "loaded")
                }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    loadingI = false; AdStore.interstitial = null
                    Log.d("OzmoraAds", "interstitial failed ${e.code} ${e.message}")
                    AdStore.report("interstitial", "loadfailed", "code ${e.code} ${e.message}")
                }
            })
    }
}
