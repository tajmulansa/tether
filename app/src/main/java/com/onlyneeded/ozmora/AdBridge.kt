package com.onlyneeded.ozmora

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Connects AdMob to the game. The game (HTML) calls the @JavascriptInterface methods through
 * `window.AndroidAds` and gets answers back through `window.tetherAdEvent(kind, status)`.
 *
 * The ad SDK itself runs in a separate process (":ads", see AdsService / AdHostActivity) so its web
 * views never compete with the game for CPU. This class only talks to that process.
 *
 *  - rewarded:     status "reward" (watched, give the reward), "closed" (skipped) or "failed"
 *  - interstitial: status "closed" or "failed"; the game continues in both cases
 */
class AdBridge(
    private val activity: MainActivity,
    private val web: WebView,
) {
    private val main = Handler(Looper.getMainLooper())

    private var started = false
    private var service: Messenger? = null
    private var bound = false
    private var rewardedLoaded = false
    private var interstitialLoaded = false
    private var rewardedLoadedAt = 0L
    private var interstitialLoadedAt = 0L
    @Volatile private var showing = false
    private var firstInterstitial = false
    private var firstRewarded = false
    private var bootSent = false
    @Volatile private var playing = false   // home screen or a run: do not load ads now

    private fun note(msg: String) { Log.d("OzmoraAds", msg) }

    // ---------------------------------------------------------------- startup + consent

    fun start() {
        if (started) return
        started = true
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(
            activity, params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { connectIfAllowed(consent) }
            },
            { err -> note("consent error ${err.errorCode}: ${err.message}"); connectIfAllowed(consent) },
        )
        connectIfAllowed(consent)
    }

    private fun connectIfAllowed(consent: ConsentInformation) {
        if (!consent.canRequestAds()) { note("waiting for consent"); return }
        if (bound) return
        bound = true
        // Give the consent choice a moment to reach disk before the ads process reads it.
        main.postDelayed({
            val ok = try {
                activity.bindService(Intent(activity, AdsService::class.java), conn, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) { false }
            note("binding ads process: $ok")
            if (!ok) { firstRewarded = true; firstInterstitial = true; bootCheck() }
        }, 300)
    }

    fun stop() {
        if (service != null || bound) { try { activity.unbindService(conn) } catch (_: Exception) {} }
        service = null; bound = false
    }

    // ---------------------------------------------------------------- ads process connection

    private val inbox = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == AdStore.MSG_EVENT) {
            onAdEvent(msg.data.getString("kind") ?: "", msg.data.getString("status") ?: "")
        }
        true
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            note("ads process connected")
            requestLoads()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; rewardedLoaded = false; interstitialLoaded = false; showing = false
        }
    }

    private fun send(what: Int) {
        try { val m = Message.obtain(null, what); m.replyTo = inbox; service?.send(m) }
        catch (e: Exception) { note("send failed: $e") }
    }

    /** Asks the ads process to load whatever is missing (only during loading and on the game-over screen). */
    private fun requestLoads() {
        if (service == null || playing) return
        if (!rewardedLoaded) send(AdStore.MSG_LOAD_REWARDED)
        if (!interstitialLoaded) send(AdStore.MSG_LOAD_INTERSTITIAL)
    }

    private fun onAdEvent(kind: String, status: String) {
        when (kind) {
            "rewarded" -> when (status) {
                "loaded" -> {
                    rewardedLoaded = true; rewardedLoadedAt = System.currentTimeMillis()
                    firstRewarded = true; bootCheck(); notifyGame("rewarded", "ready")
                }
                "loadfailed" -> { rewardedLoaded = false; firstRewarded = true; bootCheck(); notifyGame("rewarded", "loadfailed") }
                else -> { showing = false; rewardedLoaded = false; notifyGame("rewarded", status) }
            }
            "interstitial" -> when (status) {
                "loaded" -> {
                    interstitialLoaded = true; interstitialLoadedAt = System.currentTimeMillis()
                    firstInterstitial = true; bootCheck()
                }
                "loadfailed" -> { interstitialLoaded = false; firstInterstitial = true; bootCheck() }
                else -> { showing = false; interstitialLoaded = false; notifyGame("interstitial", status) }
            }
        }
    }

    /** Tells the loading screen that the first ad loading round is over (loaded or failed). */
    private fun bootCheck() {
        if (bootSent || !firstInterstitial || !firstRewarded) return
        bootSent = true
        main.post { web.evaluateJavascript("window.tetherAdsBoot&&window.tetherAdsBoot()", null) }
    }

    /** Loaded ads expire after about an hour; treat older ones as gone. */
    private fun fresh(loadedAt: Long) = System.currentTimeMillis() - loadedAt < 50 * 60 * 1000L

    // ---------------------------------------------------------------- called from the game (JS thread)

    /** true = home screen or a run (no ad loading); false = loading screen / game-over screen. */
    @JavascriptInterface
    fun setPlaying(p: Boolean) {
        playing = p
        if (!p) main.postDelayed({ if (!playing) requestLoads() }, 400)
    }

    @JavascriptInterface
    fun hasRewarded(): Boolean = !showing && rewardedLoaded && fresh(rewardedLoadedAt)

    /** The player tapped the revive-ad button but no ad was ready: load now. */
    @JavascriptInterface
    fun retryRewarded() {
        main.post {
            if (rewardedLoaded && !fresh(rewardedLoadedAt)) rewardedLoaded = false
            when {
                service == null -> notifyGame("rewarded", "loadfailed")
                rewardedLoaded -> notifyGame("rewarded", "ready")
                else -> send(AdStore.MSG_LOAD_REWARDED)
            }
        }
    }

    private fun launchHost(kind: String) {
        showing = true
        try {
            activity.startActivity(Intent(activity, AdHostActivity::class.java).putExtra("kind", kind))
        } catch (e: Exception) { showing = false; notifyGame(kind, "failed") }
    }

    @JavascriptInterface
    fun showRewarded() {
        main.post {
            if (showing || !rewardedLoaded || !fresh(rewardedLoadedAt)) { rewardedLoaded = false; notifyGame("rewarded", "failed"); return@post }
            rewardedLoaded = false
            launchHost("rewarded")
        }
    }

    @JavascriptInterface
    fun showInterstitial() {
        main.post {
            if (showing || !interstitialLoaded || !fresh(interstitialLoadedAt)) { interstitialLoaded = false; notifyGame("interstitial", "failed"); return@post }
            interstitialLoaded = false
            launchHost("interstitial")
        }
    }

    // ---------------------------------------------------------------- answer to the game

    private fun notifyGame(kind: String, status: String) {
        main.post {
            web.evaluateJavascript("window.tetherAdEvent&&window.tetherAdEvent('$kind','$status')", null)
        }
    }
}
