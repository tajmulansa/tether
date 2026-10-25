package com.onlyneeded.tether

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts the Tether game (app/src/main/assets/index.html) in a full-screen WebView.
 * The page is served from local assets, so the game itself works offline; ads need internet.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var ads: AdBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
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
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                // Never navigate away from the game.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = true
            }
        }
        // Ads: the game talks to AdMob only through this small bridge (window.AndroidAds).
        ads = AdBridge(this, web)
        web.addJavascriptInterface(ads, "AndroidAds")

        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        ads.start()

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
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        // Pause the run if the player leaves the app mid-game.
        web.evaluateJavascript("window.tetherPause&&window.tetherPause()", null)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
