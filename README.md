# ancient-cam

A tiny, single-purpose **full-screen RTSP camera viewer** for very old Android tablets —
built to turn a **Lenovo Tab 2 A7-10F** (MediaTek MT8127, 32-bit ARMv7, 1 GB RAM, **Android 5.0**)
into a reliable wall-mounted security monitor.

It does exactly one thing and does it well:

- Plays **one** hardcoded RTSP camera stream **full-screen**, correct aspect ratio, **no crop/zoom**.
- **Auto-reconnects.** A progress watchdog restarts a *frozen* stream (which emits no error at all),
  and libVLC error/end events trigger an immediate retry — no more "back out of the app and in again".
- **Starts on boot**, keeps the screen on, stays immersive full-screen.
- **No ads, no accounts, no settings, no bloat.**

Playback is [libVLC](https://www.videolan.org/vlc/libvlc.html) 3.x — the same engine as the VLC app,
so it plays anything VLC plays (tested against a TP-Link Tapo C320WS over RTSP/TCP).

## Build

Requirements: JDK 17, Android SDK (build-tools 34, platform 34), no Android Studio needed.

```sh
# 1. Set your camera URL (kept out of git):
cp secrets.xml.example app/src/main/res/values/secrets.xml
#    edit secrets.xml -> rtsp://USER:PASS@CAMERA_IP:554/stream1   (percent-encode '@' in creds as %40)

# 2. Build a debug APK:
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk

# 3. Install on the tablet:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app **once by hand** after installing — Android will not deliver `BOOT_COMPLETED`
to an app that has never been launched.

## Design notes

- **minSdk 21 / targetSdk 28 / armeabi-v7a only.** targetSdk stays < 29 so `BOOT_COMPLETED`
  can launch an Activity directly (Android 10+ blocks that). ABI is filtered to armv7 to keep the
  APK small and match the tablet's only architecture.
- `extractNativeLibs=true` + `useLegacyPackaging` — without these the libVLC `.so` won't install
  on Lollipop (`INSTALL_FAILED_INVALID_APK`).
- **No crop:** `VLCVideoLayout` letterboxes to the source aspect ratio automatically.

Credentials live only in `secrets.xml`, which is git-ignored; the repo ships a template.
