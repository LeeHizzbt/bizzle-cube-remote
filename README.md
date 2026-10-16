# Bizzle's Ultimate Cube Remote — v4.20

An Android remote for the **Amazon Fire TV Cube** (Fire OS 7 and later). The phone talks to the Cube over classic ADB on port 5555, on the same Wi-Fi. You don't need a PC, and nothing goes through the cloud apart from optional AI and title search.

**Download:** https://leehizzbt.github.io/bizzle-cube-remote/ (landing page with a direct APK link)

## Features
- **Connection:** one-time "Always allow" with a persistent ADB key, auto-discovery (NSD plus a /24 port-5555 scan), auto-reconnect watchdog, a connection doctor (Wi-Fi, subnet, port 5555, TV prompt, AP-isolation hint), and several Cubes you can switch between from the top bar.
- **Remote:** D-pad sent through a scrcpy control channel ("turbo", about 1 ms per key) with the `input keyevent` shell as fallback. Media and volume bar on every screen, hold-to-repeat, swipe trackpad, adjustable haptics, HDMI-CEC TV power/volume with a test button.
- **Keyboard:** live typing, phone clipboard paste, saved snippets. Text shared from other apps ("Send to Fire TV Cube") works too.
- **Apps:** installed-app grid, favorites, force-stop, and deep links into Netflix, Prime Video, YouTube, Disney+ and Hulu.
- **Where to watch:** TMDB (your key, entered in Settings) and TVmaze. Credits shown in the app.
- **Bizzy AI:** push-to-talk and plain-English commands, mapped **only** onto an allow-list (keys, open app, deep link, type, search, macro, wait, sleep timer). The AI is never given raw ADB/shell access. Providers are tried free-first: Gemini → Groq → OpenRouter free → offline rules. Also: "help me with my screen" (TV screenshot sent to a vision model), phone screenshot upload, memory, "what should I watch" (limited to installed apps), AI-made themes, a copy button on every reply, and Advanced AI Settings.
- **Routines:** macros, and a sleep timer shown in a notification with +10 min / Cancel. When it ends it fades the volume, pauses, then sleeps the Cube.
- **Screen:** real-time mirroring (scrcpy server → H.264 → MediaCodec → SurfaceView) where taps and swipes on the picture are sent to the Cube. A ~1 fps screencap viewer is kept as fallback, plus screenshot to phone. DRM video shows black.
- **Device care:** info dashboard, clear all caches, install an APK from the phone. Uninstall, clear data, disable and reboot sit under "Advanced" behind confirm dialogs.
- **Phone shortcuts:** lock-screen notification remote, Quick Settings tiles (Remote, Sleep timer, Bizzy voice), home-screen widget.
- An honest **"What can't it do"** screen, and a **Setup guide**.

## Keys and secrets
AI and TMDB keys are typed into the app's **Settings**. They're stored with `EncryptedSharedPreferences` on the phone and are never part of the source, the build or the repo. The release keystore lives in `keystore/` and is git-ignored.

## Build
```
export JAVA_HOME=/path/to/jdk17+  ANDROID_HOME=/path/to/android-sdk
./gradlew :core:test            # unit tests: AI parser, allow-list, offline mapper, executor, scrcpy protocol
./gradlew :app:assembleRelease  # signed if keystore/keystore.properties exists
```
Live integration tests against a real device or emulator (skipped unless the env vars are set):
`CUBE_IT_HOST=<ip> CUBE_IT_PORT=5555 CUBE_IT_KEYDIR=~/.android CUBE_IT_JAR=app/src/main/assets/scrcpy-server.jar ./gradlew :core:test --tests '*LiveAdbIT'`

Layout: `core/` (plain Kotlin: ADB client, scrcpy protocol, AI parser/allow-list, offline rules, search) and `app/` (Compose UI, services, tiles, widget).

## Credits and licenses
- **scrcpy** server v5.0.1 (`app/src/main/assets/scrcpy-server.jar`, unmodified). © Genymobile / Romain Vimont, Apache License 2.0. https://github.com/Genymobile/scrcpy
- **dadb** (mobile.dev, Apache-2.0), OkHttp, Gson, AndroidX / Jetpack Compose (Apache-2.0).
- Title data from **TMDB**. This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability by **JustWatch** (via TMDB). Show data from **TVmaze** (CC BY-SA).
- Not affiliated with Amazon. Fire TV is a trademark of Amazon.com, Inc.
