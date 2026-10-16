# Test report — v4.20 (2026-10-16)

The test device was an Android 11 emulator (AVD `kief30`, x86_64, no hardware acceleration) standing in for the Fire TV Cube. Fire OS 7 is based on Android 9 and uses the same `input`, `am`, `pm`, `screencap` and scrcpy paths, but **nothing was run on a real Cube**.

## Unit tests (`./gradlew :core:test`): 21/21 pass
- AI reply parser: JSON extraction from code fences, every allow-listed action type, rejection of unknown types, POWER, apps that aren't installed, non-official deep-link hosts, links containing quotes or whitespace, and over-long text, repeat counts and waits.
- Offline rules mapper: keys, volume with counts, open/launch, "search X for Y", "play Y on X", type, wait, sleep timer and cancel, TV on/off, macros, and all-or-nothing chains. POWER is never produced.
- Action executor (with a fake device), macro nesting limit, shell quoting / `input text` escaping, scrcpy control message byte layouts, and the video stream parser.

## Live integration tests against the emulator over ADB/TCP (`LiveAdbIT`): 4/4 pass
| Test | Result |
|---|---|
| dadb connect plus persistent key; keyevents (HOME changed the foreground app settings → launcher) | ✅ |
| Launchable app list (15 apps), device info dashboard data, screencap (606 KB PNG) | ✅ |
| Text input lands in the focused field (checked with a uiautomator dump) | ✅ |
| scrcpy v5.0.1 mirroring: session 324×720, H.264 config plus 29–30 frames; **ffmpeg decoded the stream into real frames** (`test-artifacts/it/mirror-frame-*.png`); control messages sent | ✅ |
| "Turbo" control-only scrcpy session: a key send took about 1.3 ms and actually changed the foreground app | ✅ |

## Live network tests (`LiveNetIT`, run with real free keys from the environment; no keys are stored in the repo)
- TVmaze search ✅
- Gemini → allow-listed actions ✅. `gemini-flash-latest` returned 503 (overloaded), and the automatic fallback to `gemini-flash-lite-latest` worked.
- AI theme generation passed the contrast checks ✅

## Signed release APK on the emulator (driven with adb taps; screenshots in `test-shots/`)
- Installs and launches; home screen renders (01) ✅
- **Discovery** found the device by NSD (`_adb._tcp`) and by the /24 port-5555 scan (02) ✅
- **In-app ADB connection** (the app's own generated key → adbd) plus turbo session started (03) ✅
- **Auto-reconnect** to the saved device after the app restarts ✅
- Media bar volume keys sent through turbo reached Android's input dispatcher (logcat `dispatchVolumeKeyEvent KEYCODE_VOLUME_UP/DOWN`) ✅
- **In-app real-time mirroring**: MediaCodec decode onto the SurfaceView works (05; the emulator mirrors itself, so you see a recursive "tunnel") ✅. Bug found and fixed: the emulator decoder rejects `KEY_LOW_LATENCY`, so the app now falls back to normal decode.
- **Touch through the mirror**: tapping the mirrored Home icon navigated the real UI (06) ✅
- Apps grid loads the installed apps live (08) ✅. Bizzy screen renders (07) ✅
- Bug found and fixed: the on-screen keyboard covered text inputs (edge-to-edge without IME padding).

## Built but not verified on hardware
- The Fire TV "Allow USB debugging? / Always allow" prompt. The emulator didn't show one, so the waiting-for-TV flow was never exercised.
- HDMI-CEC TV power/volume (KEYCODE_TV_POWER, WAKE/SLEEP). The emulator has no CEC.
- Fire OS deep links (Netflix `com.netflix.ninja`, Prime `com.amazon.firebat`, Fire TV YouTube, Disney+, Hulu). Those apps aren't on the emulator.
- Mirroring performance and resolution on the Cube's real 1080p/4K display; how it behaves on Fire OS 7 (Android 9) MediaCodec/scrcpy. DRM video shows black by design.
- Voice push-to-talk (the emulator has no mic input); lock-screen notification remote, QS tiles and the widget (built and declared, not interacted with); sleep timer countdown to the end.
- `pm trim-caches`, uninstall/disable/reboot, and installing an APK from the phone on Fire OS.
- TMDB "where to watch" (needs the user's TMDB key); Groq and OpenRouter providers; "help me with my screen" vision call from the app.
