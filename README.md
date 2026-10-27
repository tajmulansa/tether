# Ozmora

A one-thumb swing game. Tap to let go of the anchor, catch the next one, and don't fall.

The game is a single offline HTML file shown full-screen inside a small Android WebView app.

## Get the APK

1. Open the **Actions** tab of this repo and pick the latest **Android build** run
   (or push to `main` / press **Run workflow**).
2. Download the **ozmora-debug-apk** artifact, unzip it, and install `app-debug.apk` on your phone.

## Change the game

The whole game lives in `app/src/main/assets/index.html`. Edit it, push to `main`, and the
workflow builds a new APK. You can also open that file in any browser to test quickly.

## Signed release for Play Store

Add these repository secrets (Settings, Secrets and variables, Actions):

| Secret | What it is |
| --- | --- |
| `KEYSTORE_BASE64` | your upload keystore, base64-encoded (`base64 -w0 release.keystore`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

With those set, each run also produces a signed release APK and an `.aab` for Play Console
(artifact **ozmora-release**). Bump `versionCode` in `app/build.gradle.kts` for every upload.

## Ads (AdMob)

The app shows three kinds of ads, all through Google AdMob:

- **Rewarded: "Watch ad to revive"** gives a free revive after a run ends.
- **Rewarded: "Watch ad: +N shards"** doubles the shards earned in that run.
- **Interstitial:** one full-screen ad when leaving the game-over screen, at most once every
  4 finished runs and every 90 seconds, never in the first 3 runs, and never right after a
  rewarded ad.

The game talks to AdMob only through `AdBridge.kt` (`window.AndroidAds` in the page). In a
normal browser there is no bridge, so no ad buttons appear.

**Test ads vs real ads.** Debug builds always use Google's official test ad IDs, so you can
install and click them freely. Release builds also use test IDs until you add your real IDs
as repository secrets:

| Secret | Example format |
| --- | --- |
| `ADMOB_APP_ID` | `ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY` |
| `ADMOB_INTERSTITIAL_ID` | `ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ` |
| `ADMOB_REWARDED_ID` | `ca-app-pub-XXXXXXXXXXXXXXXX/WWWWWWWWWW` |

Create the app and the two ad units (Interstitial, Rewarded) in the AdMob console to get them.
Never tap your own real ads while testing.

**Before publishing on Google Play:** publish a privacy policy that mentions AdMob, fill in the
Data safety form (advertising ID, ads), and answer "Yes, contains ads" in Play Console. The app
shows Google's consent form automatically where the law requires it (EU/UK).

## Project notes

- Package ID: `com.onlyneeded.ozmora` (set in `app/build.gradle.kts`). It cannot change after the
  first Play Store upload, so change it before then if you want a different one.
- Min Android 8.1 (API 27), target API 35, portrait only.
- The game itself works offline. Internet is only used for ads (and to load the web font).
- Android back button: closes menus, pauses a run, or exits from the home screen.
- Keep your keystore safe and out of git. `*.keystore` is already in `.gitignore`.
