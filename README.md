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

Playback is **ExoPlayer / Media3** RTSP forced over TCP. It is a pure-Java stack (no live555),
which matters on this tablet: libVLC's live555 could not self-detect the local IP (returned
`0.0.0.0`) and failed every RTSP PLAY. Tested against a TP-Link Tapo C320WS.

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

## Hard-won notes (Lenovo Tab 2 A7-10F, MT8127, Android 5.0)

- **Use ExoPlayer, not libVLC, here.** libVLC's live555 returned `0.0.0.0` for the local
  source address on this device and failed every RTSP session. ExoPlayer's RTSP has no such
  self-IP lookup.
- **No `@` in camera credentials.** Android 5.0's `Uri` mis-parses `rtsp://user@x:pass@host`,
  reading the host as the text after the first `@`. Set an `@`-free Camera Account username.
- **Use `/stream2` (low-res).** The MT8127 hardware H264 decoder cannot initialize 2K
  (`/stream1`) — `ERROR_CODE_DECODER_INIT_FAILED`. The sub-stream decodes fine.
- **No boot-autostart.** Launching heavy video during this tablet's fragile, low-battery boot
  crashed `surfaceflinger` and hung the boot. Launch the app by hand after the system is up.

## Self-healing camera discovery (added after a DHCP move broke it)

The RTSP IP in `secrets.xml` is only the *fast path*. If the camera stops answering there —
e.g. DHCP hands it a new address — the app scans its own `/24` for hosts with the RTSP port
open, tries each (the Back camera is skipped because it rejects these credentials), and locks
onto whichever one actually renders frames. So a moved camera recovers on its own in a few
reconnect cycles, with no rebuild and no router config. See `CameraLocator.java`.
