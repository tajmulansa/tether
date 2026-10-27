package com.onlyneeded.ozmora

import android.util.Log
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Connects AdMob to the game. The game (HTML) calls the @JavascriptInterface methods through
 * `window.AndroidAds` and gets answers back through `window.tetherAdEvent(kind, status)`.
 *
 *  - rewarded:     status "reward" (watched, give the reward), "closed" (skipped) or "failed"
 *  - interstitial: status "closed" or "failed"; the game continues in both cases
 *
 * Ads are loaded in advance, so showing one is instant. Nothing is requested before the
 * consent flow (UMP) says ads may be requested.
 */
class AdBridge(
    private val activity: MainActivity,
    private val web: WebView,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var interstitial: InterstitialAd? = null
    @Volatile private var rewarded: RewardedAd? = null
    @Volatile private var rewardedLoadedAt = 0L
    private var interstitialLoadedAt = 0L

    private var started = false
    private var sdkInitialised = false
    private var loadingInterstitial = false
    private var loadingRewarded = false
    private var interstitialFailures = 0
    private var rewardedFailures = 0
    private var showing = false
    private var firstInterstitial = false
    private var firstRewarded = false
    private var bootSent = false
    @Volatile private var playing = false   // a run is in progress: do not load ads now

    /** Ad status goes to the log only (adb logcat -s TetherAds); nothing is shown on screen. */
    private fun note(msg: String) {
        Log.d("TetherAds", msg)
    }

    // ---------------------------------------------------------------- startup + consent

    fun start() {
        if (started) return
        started = true
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()

        consent.requestConsentInfoUpdate(
            activity, params,
            {
                // Shows the consent form only when the user's region requires it.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    initIfAllowed(consent)
                }
            },
            { err -> note("consent error ${err.errorCode}: ${err.message}"); initIfAllowed(consent) },
        )
        // Returning users who already gave consent can start right away.
        initIfAllowed(consent)
    }

    private fun initIfAllowed(consent: ConsentInformation) {
        if (!consent.canRequestAds()) { note("waiting for consent"); return }
        if (sdkInitialised) return
        sdkInitialised = true
        // Initialise off the main thread, as Google recommends.
        Thread {
            MobileAds.initialize(activity) {
                note("SDK ready, loading ads")
                main.post {
                    loadInterstitial()
                    loadRewarded()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- loading

    private fun loadInterstitial() {
        if (!sdkInitialised || playing || loadingInterstitial || interstitial != null) return
        loadingInterstitial = true
        InterstitialAd.load(
            activity, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingInterstitial = false
                    interstitialFailures = 0
                    firstInterstitial = true; bootCheck()
                    interstitialLoadedAt = System.currentTimeMillis()
                    interstitial = ad
                    note("interstitial ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    interstitial = null
                    interstitialFailures++
                    firstInterstitial = true; bootCheck()
                    note("interstitial failed: code ${error.code} ${error.message}")
                    main.postDelayed({ loadInterstitial() }, backoff(interstitialFailures))
                }
            },
        )
    }

    private fun loadRewarded() {
        if (!sdkInitialised || playing || loadingRewarded || rewarded != null) return
        loadingRewarded = true
        RewardedAd.load(
            activity, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadingRewarded = false
                    rewardedFailures = 0
                    firstRewarded = true; bootCheck()
                    rewardedLoadedAt = System.currentTimeMillis()
                    rewarded = ad
                    note("rewarded ad loaded")
                    notifyGame("rewarded", "ready")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingRewarded = false
                    rewarded = null
                    rewardedFailures++
                    firstRewarded = true; bootCheck()
                    note("rewarded failed: code ${error.code} ${error.message}")
                    notifyGame("rewarded", "loadfailed")
                    main.postDelayed({ loadRewarded() }, backoff(rewardedFailures))
                }
            },
        )
    }

    /** Tells the loading screen that the first ad loading round is over (loaded or failed). */
    private fun bootCheck() {
        if (bootSent || !firstInterstitial || !firstRewarded) return
        bootSent = true
        main.post { web.evaluateJavascript("window.tetherAdsBoot&&window.tetherAdsBoot()", null) }
    }

    /** 15 s, 30 s, 60 s ... capped at 5 minutes. */
    private fun backoff(failures: Int): Long =
        minOf(300_000L, 15_000L * (1L shl minOf(failures - 1, 5)))

    /** Loaded ads expire after about an hour; treat older ones as gone. */
    private fun fresh(loadedAt: Long) = System.currentTimeMillis() - loadedAt < 50 * 60 * 1000L

    // ---------------------------------------------------------------- called from the game (JS thread)

    /** The game tells us when a run starts/ends. Ads are only requested while nobody is playing,
     *  because loading an ad does work on the main thread and can make the game stutter. */
    @JavascriptInterface
    fun setPlaying(p: Boolean) {
        playing = p
        if (!p) main.postDelayed({ if (!playing) { loadRewarded(); loadInterstitial() } }, 400)
    }

    @JavascriptInterface
    fun hasRewarded(): Boolean = !showing && rewarded != null && fresh(rewardedLoadedAt)

    /** The player tapped the revive-ad button but no ad was ready: try loading again right away. */
    @JavascriptInterface
    fun retryRewarded() {
        main.post { if (rewarded != null && !fresh(rewardedLoadedAt)) rewarded = null; loadRewarded() }
    }

    @JavascriptInterface
    fun showRewarded() {
        main.post {
            val ad = rewarded
            if (showing || ad == null || !fresh(rewardedLoadedAt)) {
                rewarded = null
                loadRewarded()
                notifyGame("rewarded", "failed")
                return@post
            }
            showing = true
            rewarded = null
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    showing = false
                    loadRewarded()
                    // Reward is given only after the ad is closed, so the game resumes cleanly.
                    notifyGame("rewarded", if (earned) "reward" else "closed")
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    showing = false
                    loadRewarded()
                    notifyGame("rewarded", "failed")
                }
            }
            ad.show(activity) { earned = true }
        }
    }

    @JavascriptInterface
    fun showInterstitial() {
        main.post {
            val ad = interstitial
            if (showing || ad == null || !fresh(interstitialLoadedAt)) {
                interstitial = null
                loadInterstitial()
                notifyGame("interstitial", "failed")
                return@post
            }
            showing = true
            interstitial = null
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    showing = false
                    loadInterstitial()
                    notifyGame("interstitial", "closed")
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    showing = false
                    loadInterstitial()
                    notifyGame("interstitial", "failed")
                }
            }
            ad.show(activity)
        }
    }

    // ---------------------------------------------------------------- answer to the game

    private fun notifyGame(kind: String, status: String) {
        main.post {
            web.evaluateJavascript(
                "window.tetherAdEvent&&window.tetherAdEvent('$kind','$status')", null
            )
        }
    }
}
