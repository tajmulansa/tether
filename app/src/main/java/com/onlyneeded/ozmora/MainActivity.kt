package com.onlyneeded.ozmora

import android.annotation.SuppressLint
import android.graphics.Color
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts the Ozmora game (app/src/main/assets/index.html) in a full-screen WebView.
 * The page is served from local assets, so the game itself works offline; ads need internet.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var ads: AdBridge
    @Volatile private var pageReady = false
    private var fromToggle = false

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Reminder.setEnabled(this, granted)
        if (!granted && fromToggle) {
            // Blocked for good: send the player to the app's notification settings.
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        fromToggle = false
        pushRemind()
    }

    private fun pushRemind() {
        web.evaluateJavascript("window.tetherRemind&&window.tetherRemind(${Reminder.active(this)})", null)
    }

    private fun enableReminders() {
        Reminder.setEnabled(this, true)
        if (!Reminder.hasPermission(this)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else pushRemind()
    }

    /** window.AndroidNotify: the game's Reminders switch and the one-time question after the first level. */
    inner class NotifyBridge {
        @JavascriptInterface fun state(): Boolean = Reminder.active(this@MainActivity)

        @JavascriptInterface fun toggle() = runOnUiThread {
            if (Reminder.active(this@MainActivity)) { Reminder.setEnabled(this@MainActivity, false); pushRemind() }
            else { fromToggle = true; enableReminders() }
        }

        @JavascriptInterface fun ask() = runOnUiThread {
            if (Reminder.asked(this@MainActivity) || isFinishing) return@runOnUiThread
            Reminder.setAsked(this@MainActivity)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Daily reminder?")
                .setMessage("Get a gentle nudge when it has been a while. You can turn it off any time in Settings.")
                .setPositiveButton("Yes, remind me") { _, _ -> enableReminders() }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    /** The game page is stored encrypted in the app; it is decrypted here, in memory only. */
    private fun gameHtml(): ByteArray {
        val raw = assets.open("game.bin").use { it.readBytes() }
        val key = ByteArray(32) { i ->
            (BuildConfig.GK1.substring(i * 2, i * 2 + 2).toInt(16) xor BuildConfig.GK2.substring(i * 2, i * 2 + 2).toInt(16)).toByte()
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash (icon on dark) stays up until the page has loaded, so there is no blank screen.
        val splash = installSplashScreen()
        val startedAt = System.currentTimeMillis()
        splash.setKeepOnScreenCondition { !pageReady && System.currentTimeMillis() - startedAt < 4000 }
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask for a steady 60 Hz: the game is drawn at 60 fps, and a fixed rate avoids the
        // refresh-rate switching that makes 120 Hz phones stutter.
        window.attributes = window.attributes.also { it.preferredRefreshRate = 60f }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#05060F"))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isLongClickable = false
            setOnLongClickListener { true }
            isHapticFeedbackEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true // keeps scores and unlocks (localStorage)
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (request.url.path == "/assets/index.html") {
                        return WebResourceResponse(
                            "text/html", "utf-8", 200, "OK",
                            mapOf("Cache-Control" to "no-store"),
                            ByteArrayInputStream(gameHtml()),
                        )
                    }
                    return loader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    pageReady = true
                    // Set the ads up right away: the game's loading screen is still showing and
                    // waits for the first round, so any stutter happens there, not on the home screen.
                    if (BuildConfig.ADS_ENABLED) ads.start()
                }

                // Never navigate away from the game.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = true
            }
        }
        // Ads: the game talks to AdMob only through this small bridge (window.AndroidAds).
        ads = AdBridge(this, web)
        if (BuildConfig.ADS_ENABLED) web.addJavascriptInterface(ads, "AndroidAds")

        web.addJavascriptInterface(NotifyBridge(), "AndroidNotify")

        // Keep the page renderer at high priority so the system does not throttle it while we play.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        // Back button: let the game handle it (close sheet, pause, go home); exit from the home screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.tetherBack&&window.tetherBack())?'1':'0'") { result ->
                    if (result != "\"1\"") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        // A call or notification pop-up takes focus without pausing the app: pause the run.
        else web.evaluateJavascript("window.tetherPause&&window.tetherPause()", null)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        Reminder.cancel(this)
        ads.pollGame()
        hideSystemBars()
    }

    override fun onPause() {
        // Pause the run if the player leaves the app mid-game.
        web.evaluateJavascript("window.tetherPause&&window.tetherPause()", null)
        web.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Left the game: line up the reminders (24 h later, then twice a day).
        Reminder.schedule(this)
        super.onStop()
    }

    override fun onDestroy() {
        ads.stop()
        web.destroy()
        super.onDestroy()
    }
}
