plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from environment variables (set by the GitHub Actions
// workflow from repository secrets). Without them, only the debug APK is signed.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

// AdMob IDs. Google's official TEST IDs are used unless real ones are provided through
// environment variables (GitHub secrets). Debug builds ALWAYS use the test IDs, so you can
// never click your own live ads while testing (that can get an AdMob account banned).
val testAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val testRewardedId = "ca-app-pub-3940256099942544/5224354917"
fun envOr(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "com.onlyneeded.tether"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onlyneeded.tether"
        minSdk = 27
        targetSdk = 35
        versionCode = 14
        versionName = "1.6"
    }

    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = testAppId
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$testInterstitialId\"")
            buildConfigField("String", "ADMOB_REWARDED_ID", "\"$testRewardedId\"")
        }
        release {
            manifestPlaceholders["admobAppId"] = envOr("ADMOB_APP_ID", testAppId)
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${envOr("ADMOB_INTERSTITIAL_ID", testInterstitialId)}\"")
            buildConfigField("String", "ADMOB_REWARDED_ID", "\"${envOr("ADMOB_REWARDED_ID", testRewardedId)}\"")
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}
