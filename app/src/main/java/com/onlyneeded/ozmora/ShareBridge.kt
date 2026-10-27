package com.onlyneeded.ozmora

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface

/** Lets the game open the system share sheet (window.AndroidApp.share). */
class ShareBridge(private val activity: Activity) {
    @JavascriptInterface
    fun share(text: String) {
        activity.runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(send, null))
        }
    }
}
