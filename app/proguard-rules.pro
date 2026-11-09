# The game page calls these methods through window.AndroidAds
-keepclassmembers class com.onlyneeded.ozmora.AdBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
-keepclassmembers class com.onlyneeded.ozmora.MainActivity$NotifyBridge {
    @android.webkit.JavascriptInterface <methods>;
}
