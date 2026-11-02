# Couchreel (direct mode): no server

The app talks to Telegram itself through TDLib, Telegram's official client library. Your Raspberry Pi
does not need anything else running. Server mode (the Python `tgbridge`) still works as a fallback.

## Status (read this first)

| Part | State |
|---|---|
| Release-name parser, matcher, catalog, watched/resume, search, series, watchlist import, channels and guide (`Release.kt`, `Catalog.kt`, `LocalState.kt`) | Compiled and covered by 22 unit tests that pass |
| `Backend.kt` interface | Compiles |
| `TdClient.kt`, `TdDataSource.kt`, `TdHub.kt`, `LocalBackend.kt`, UI, `PlayerActivity.kt` | **Never compiled or run.** Written against the TDLib 1.8.x Java API. Expect a few compile fixes. |
| Telegram login, scanning, streaming, hub join | **Untested against real Telegram** |

Likeliest places for a compile error: `TdClient.kt` (`clearCache` / `OptimizeStorage`,
`SetTdlibParameters` fields, `Function<*>` generics), then `TdDataSource.kt` and `TdHub.kt`.
Fixes are usually a renamed field or a changed constructor, and the compiler error names the line.

## 1. Telegram API keys

Create an app at https://my.telegram.org -> API development tools, then add to `~/.gradle/gradle.properties`
(not into the project):

```
tgApiId=1234567
tgApiHash=0123456789abcdef0123456789abcdef
```

If you leave them out, the app asks for them on first start.

## 2. Build TDLib once (from Telegram's source)

Easiest, with Docker:

```
git clone https://github.com/tdlib/td.git
cd td/example/android
docker build --output tdlib .
```

Optionally pin a version with `--build-arg COMMIT_HASH=<hash>`.
Without Docker, use Telegram's scripts in the same folder: `./check-environment.sh`, `./fetch-sdk.sh`,
`./build-openssl.sh`, `./build-tdlib.sh`.

The build must use the default Java interface (not JSON). The output folder has `java/` and `libs/<cpu>/libtdjni.so`.
A Raspberry Pi running Android TV is normally `arm64-v8a` or `armeabi-v7a`.

## 3. Add TDLib to the project

```
scripts/setup_tdlib.sh /path/to/td/example/android/tdlib
```

This copies the Java sources into `app/src/main/java` and each `libtdjni.so` into `app/src/main/jniLibs/<cpu>/`.

## 4. Build and install

1. Open this folder in Android Studio (it installs the Android SDK for you) and let Gradle sync.
2. Run Build -> Build APK, or from a terminal: `./gradlew assembleDebug`.
3. Install on the Pi: `adb connect <pi-ip>` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Unit tests (TDLib must be set up first, because the whole module compiles together): `./gradlew testDebugUnitTest`.

## 5. First start

1. Choose **Directly from Telegram (no server)**.
2. Log in by QR code: Telegram on your phone -> Settings -> Devices -> Link Desktop Device -> scan. (Phone number + code also works.)
3. Open Settings and choose **Rescan my channels**. It reads the video files from your chats (last 3000 messages per chat, files of at least 20 MB).
   The library fills in while it scans.

## Features in direct mode

- Library, series grouping, duplicates hidden behind a toggle, sort and search with recent queries.
- Long-press OK on an episode or title toggles watched. Resume and Continue Watching. Episodes auto-advance.
- **Hubs** (advanced): scans a channel for invite/t.me links, lists only the ones that contain video, and joins the ones you pick,
  paced 25-50 s apart. It stops if Telegram answers with a flood wait (429).
- **Watchlist**: pick your Letterboxd `watchlist.csv` with the file picker, or put it in
  `adb push watchlist.csv /sdcard/Android/data/com.couchreel.tv/files/watchlist.csv`.
- **Channels / guide**: cable-style channels (Movies: Mixed, by decade, Series: X, per Telegram channel) with a schedule.

## Things to know

- Playback streams from Telegram through TDLib. TDLib keeps downloaded parts on disk; the player deletes the file when you switch episode or leave.
  A crash can leave a partial file behind. Settings -> Clear download cache removes them.
- A Raspberry Pi's hardware decoder limits what plays: 1080p H.264 is usually fine; HEVC 10-bit and 4K may stutter or fail.
  Choose another version of the title when playback fails.
- No posters or TMDB metadata yet.
- NostalgiaTV only works with Plex servers, so it can't be supported without Plex.
