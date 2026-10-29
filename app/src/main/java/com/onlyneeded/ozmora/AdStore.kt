package com.onlyneeded.ozmora

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.rewarded.RewardedAd

/** Lives in the ":ads" process: holds loaded ads and the way back to the game process. */
object AdStore {
    const val MSG_LOAD_REWARDED = 1
    const val MSG_LOAD_INTERSTITIAL = 2
    const val MSG_EVENT = 10

    @Volatile var rewarded: RewardedAd? = null
    @Volatile var interstitial: InterstitialAd? = null
    @Volatile var replyTo: Messenger? = null

    /** kind: "rewarded" | "interstitial"; status: loaded, loadfailed, reward, closed, failed */
    fun report(kind: String, status: String, info: String = "") {
        val m = Message.obtain(null, MSG_EVENT)
        m.data = Bundle().apply { putString("kind", kind); putString("status", status); putString("info", info) }
        try { replyTo?.send(m) } catch (_: Exception) {}
    }
}
