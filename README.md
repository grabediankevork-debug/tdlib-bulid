# Couchreel

An original Android TV app for watching the videos in your own Telegram channels, with the improvements
from your interview built in. It is **not** a copy of Echogram: it has its own name, design and code, and talks
to your own `tgbridge.py` server, which signs in to Telegram with your account.

```
Telegram  <->  tgbridge.py (your PC / Raspberry Pi)  <->  Couchreel (Android TV)
```

## What it does

| You asked for | Where |
|---|---|
| Continue Watching, Recently Added, Next Up rows | Home |
| Duplicates hidden behind a toggle | Movies -> "Duplicates hidden / All versions shown" |
| Series grouped, per-episode watched marks | Series -> a show -> hold OK on an episode |
| Remembers focus when you back out | every list and row |
| Recent searches, live search, little typing | Search |
| Hub-channel auto-join, only channels with video, paced | Join channels |
| Letterboxd watchlist, shows what you already have | Watchlist (CSV import) |
| Cable-style channels with a guide, lean-back | Channels |

Playback uses Media3 ExoPlayer (hardware decoding, MKV, audio-track and subtitle menu on the control bar,
resume position, auto-plays the next episode).

## Part 1: run the server (once, on the Pi or a PC)

```
pip install telethon
export TG_API_ID=...  TG_API_HASH=...                     # https://my.telegram.org -> API development tools
python tgbridge.py index --channels all                   # builds index.json (first run asks you to log in)
python tgbridge.py serve --host 0.0.0.0 --port 8765 --token CHOOSE_A_SECRET --base-url http://<server-ip>:8765
```

Keep it on your home network. Anyone who can reach it and has the token can stream your Telegram media.
After joining new channels or adding videos, stop the server, re-run `index`, and start it again.

## Part 2: build the app (Android Studio)

1. Install Android Studio (free): https://developer.android.com/studio
2. File -> Open -> choose this `couchreel` folder. Accept the prompts to install the Android SDK and let Gradle sync
   (first sync downloads a few hundred MB).
3. Build -> Build APK(s). The APK is in `app/build/outputs/apk/debug/app-debug.apk`.
4. Install on the TV, either:
   - Wi-Fi: on the TV enable Developer options -> USB/network debugging, then `adb connect <tv-ip>:5555` and
     `adb install -r app-debug.apk`; or
   - copy the APK to a USB stick / use a "Send files to TV" app and open it with a file manager.
   For the Raspberry Pi: `adb connect <pi-ip>:5555` then `adb install -r app-debug.apk`.

## Part 3: first run

Open Couchreel. Enter the server address (`192.168.1.50:8765`) and the token. The app tests the connection and saves it.

- **Hold OK** (about half a second) on any title or episode to toggle watched.
- **Letterboxd:** on a phone or PC open `http://<server-ip>:8765/watchlist-upload?token=<token>`, choose
  `watchlist.csv` from your Letterboxd export (Letterboxd -> Settings -> Data -> Export), upload. The Watchlist tab
  then shows which films you already have. This is a one-way import; two-way sync needs API access Letterboxd
  grants to approved developers.
- **Join channels:** type the hub channel (`@name` or a t.me link), Scan, review the list (channels with video are
  pre-ticked), Join selected. Joins are paced and capped at 20 per run to stay inside Telegram's limits. Re-index
  on the server afterwards.

## Honest status

- The server side (library logic, watched/resume, search, guide, watchlist matching, streaming with seeking, hub jobs)
  has 40 automated tests that pass, including HTTP tests against a fake Telegram client.
- The Android app was written without access to an Android SDK, so it has **never been compiled or run**. A separate
  read-through found and fixed compile problems, but expect a few more on first build; paste any Gradle error and it
  can be fixed quickly.
- The Telegram calls (login, indexing, downloads, joining) are untested against real Telegram.
- There are no posters: cards are coloured tiles with titles. Posters would need a metadata source such as TMDB
  (an API key) and are a natural next step.
- Playback of unusual codecs depends on the device's hardware decoders; on a Raspberry Pi, very high bitrate 4K or
  some HEVC files may stutter or fail. The app tells you when a file can't be played.
- NostalgiaTV is not supported: it reads only from Plex. The Channels tab covers the same lean-back idea inside
  this app.
