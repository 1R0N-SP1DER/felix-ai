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

## Patch 29: always-on listening and the new screens

- `ListenService`: a foreground service of type `microphone`. It listens for the wake phrase (default "hey faze", set under
  Settings) with a small offline Vosk model, beeps, records one request until you stop talking, and sends it through the same
  path as typed text. Start it from Settings > Always listening while the app is on screen (Android 14+ refuses a microphone
  service started from the background). The app brings it back by itself the next time you open it.
- The wake model (about 40 MB) is downloaded once, over HTTPS, from Settings. It is unzipped with a path check.
- `Permissions.kt`: the approval gate. Same tiers as `core/permission_policy.py` (clipboard read/write ask first, unknown tools
  are blocked). Allow/Deny on the approval card is the only way a gated tool runs; no answer in 60 s means no.
- Saved chats live in the app's private folder (`filesDir/chats`). New screens: home with the core, chat with tool chips,
  approval card and live bar, a chats drawer with search, and Settings.

## Patch 30: overlay, volume buttons, better wake word

- **Overlay** (`Overlay.kt`): a panel at the bottom of the screen over a darkened, blurred view of any app, with the core, the live reply, a text box with a
  working keyboard, a mic button and the Allow/Deny approval card. Replaces the Tasker scene.
- **Volume buttons** (`FazeAccessibilityService.kt`): hold both for 0.6 s to open it; with it open, hold volume up for 0.7 s to
  force-close it (stops recording, speech, the request and any pending approval). A quick volume press works as usual. The service
  only sees volume keys; it does not read the screen. Enable it (and "display over other apps") in one step with root:
  `su -c "sh $HOME/felix-ai/scripts/faze_enable_android.sh"`.
- **Wake word**: offline Vosk. It needs a finished recognition with every word right and confident, lasting about as long as
  the phrase, with look-alike decoys in the grammar, so coughs, sneezes and similar words are rejected. (Picovoice was tried in
  patch 30 and dropped in patch 31: it is aimed at companies and is not free.)
- **Quiet notification**: minimum importance, own icon, no status-bar icon.
- **Microphone dot (root, off by default)**: `RootCommands.kt` holds the only three commands the app can run as root, all fixed
  `cmd device_config ... privacy camera_mic_icons_enabled`. While only waiting, the dot is hidden; while recording a request it is shown.
  It is restored when listening stops.
