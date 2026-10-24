# tether

A one-thumb swing game. Tap to let go of the anchor, catch the next one, and don't fall.

The game is a single offline HTML file shown full-screen inside a small Android WebView app.

## Get the APK

1. Open the **Actions** tab of this repo and pick the latest **Android build** run
   (or push to `main` / press **Run workflow**).
2. Download the **tether-debug-apk** artifact, unzip it, and install `app-debug.apk` on your phone.

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
(artifact **tether-release**). Bump `versionCode` in `app/build.gradle.kts` for every upload.

## Project notes

- Package ID: `com.onlyneeded.tether` (set in `app/build.gradle.kts`). It cannot change after the
  first Play Store upload, so change it before then if you want a different one.
- Min Android 8.1 (API 27), target API 35, portrait only.
- No internet permission: the game runs completely offline.
- Android back button: closes menus, pauses a run, or exits from the home screen.
- Keep your keystore safe and out of git. `*.keystore` is already in `.gitignore`.
