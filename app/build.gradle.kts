import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from environment variables (set by the GitHub Actions
// workflow from repository secrets). Without them, only the debug APK is signed.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

// AdMob IDs. Release builds use the live IDs below (or ones given through environment variables).
// Debug builds ALWAYS use Google's official TEST IDs, so you can
// never click your own live ads while testing (that can get an AdMob account banned).
val testAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val testRewardedId = "ca-app-pub-3940256099942544/5224354917"
// Test build with the ad SDK switched off completely (gradle -PnoAds): installs next to the normal app.
val noAds = project.hasProperty("noAds")
// Real (live) AdMob IDs. These are public identifiers (they ship inside the app), not secrets.
val liveAppId = "ca-app-pub-4812493783151469~8071645170"
val liveInterstitialId = "ca-app-pub-4812493783151469/7720503413"
val liveRewardedId = "ca-app-pub-4812493783151469/4004619688"
fun envOr(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

// The game file is encrypted at build time (AES-256-GCM) and only decrypted in memory inside the app,
// so it cannot simply be unzipped from the APK/AAB. The key comes from the upload keystore password when
// building for release, otherwise from a fixed test value.
val gameKey: ByteArray = MessageDigest.getInstance("SHA-256")
    .digest(("ozmora-game:" + (System.getenv("KEYSTORE_PASSWORD") ?: "debug-only")).toByteArray())
val keyMask: ByteArray = MessageDigest.getInstance("SHA-256").digest("ozmora-mask".toByteArray())
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
val gameSrc = layout.projectDirectory.file("game/index.html").asFile
val gameOut = layout.buildDirectory.dir("generated/gameAssets")
val encryptGame by tasks.registering {
    inputs.file(gameSrc)
    inputs.property("k", hex(gameKey))
    outputs.dir(gameOut)
    doLast {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(gameKey, "AES"), GCMParameterSpec(128, nonce))
        val out = gameOut.get().asFile.apply { mkdirs() }
        File(out, "game.bin").writeBytes(nonce + c.doFinal(gameSrc.readBytes()))
    }
}

android {
    namespace = "com.onlyneeded.ozmora"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onlyneeded.ozmora"
        minSdk = 27
        targetSdk = 36
        versionCode = 55
        versionName = "5.1"
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
    sourceSets["main"].assets.srcDir(encryptGame.map { gameOut.get() })
    defaultConfig.buildConfigField("String", "GK1", "\"${hex(ByteArray(32) { (gameKey[it].toInt() xor keyMask[it].toInt()).toByte() })}\"")
    defaultConfig.buildConfigField("String", "GK2", "\"${hex(keyMask)}\"")
    defaultConfig.buildConfigField("boolean", "ADS_ENABLED", if (noAds) "false" else "true")

    buildTypes {
        debug {
            if (noAds) applicationIdSuffix = ".noads"
            manifestPlaceholders["admobAppId"] = testAppId
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$testInterstitialId\"")
            buildConfigField("String", "ADMOB_REWARDED_ID", "\"$testRewardedId\"")
        }
        release {
            manifestPlaceholders["admobAppId"] = envOr("ADMOB_APP_ID", liveAppId)
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${envOr("ADMOB_INTERSTITIAL_ID", liveInterstitialId)}\"")
            buildConfigField("String", "ADMOB_REWARDED_ID", "\"${envOr("ADMOB_REWARDED_ID", liveRewardedId)}\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the upload keystore the release build is signed with the debug key: it installs
            // for testing with the live ad IDs, but it is not the file to upload to Google Play.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}

tasks.matching { it.name != "encryptGame" && (it.name.endsWith("Assets") || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(encryptGame) }
