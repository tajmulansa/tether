# The game page calls these methods through window.AndroidAds
-keepclassmembers class com.onlyneeded.ozmora.AdBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
