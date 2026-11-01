# Faze for Android

The standalone app: no Termux, no Tasker. The brain (Groq model, tools, weather, search, voice) runs inside the app.

This first version is a chat screen with push-to-talk voice. Not in it yet: the wake word, the floating overlay, Live voice,
local models and the redesigned UI.

## Build

The APK is built by GitHub Actions (`.github/workflows/android.yml`) on every push that touches `android/`.
Download it with `gh run download -n faze-debug-apk` or from the run page on GitHub.

The build runs the unit tests first (`gradle testDebugUnitTest`): the tool loop, the Groq client, weather, search, the
sentence-by-sentence speech and the chat engine are plain Kotlin and are tested without a phone.

To build on a PC instead: install JDK 17, the Android SDK (platform 35) and Gradle 8.11.1, then `cd android && gradle assembleDebug`.

## Install (rooted)

    su -c "pm install -r /sdcard/Download/app-debug.apk"

Or open the APK from the Files app and allow installs from that app.

## Keys

Open the app, tap Keys, and paste your Groq key (console.groq.com/keys) and, for web search, your Tavily key.
Keys are stored in the app's private storage on the phone and never leave it except in requests to their own services.

## The debug key

`app/debug.keystore` is the well-known Android debug key (password `android`). It is committed on purpose: without it every
CI build is signed with a fresh random key and Android refuses to install one build over the previous one. It is for
personal debug builds only; do not publish anything signed with it.
