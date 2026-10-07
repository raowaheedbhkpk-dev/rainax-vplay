# Rainax Vplay

A free video player for Android with no ads, built with Kotlin and Media3 (ExoPlayer).

## Features

**Library**
- Tabs: Folders, Videos, Favourites, History, Playlists
- Search, sort (name / date / size / duration), thumbnails, resume progress bars
- Long-press a video: favourite, add to playlist, info
- Open any file from storage or SD card ("Open file..."), open network streams (HTTP, HLS, DASH)
- Opens from other apps ("Open with" on video or audio files and links)
- List refreshes by itself when new videos appear

**Player**
- Gestures: brightness (left swipe), volume (right swipe), seek (horizontal swipe),
  double-tap to skip or pause, press and hold for 2x speed. Each can be turned off in Settings.
- Background audio with notification and lock-screen controls
- FFmpeg audio decoders (AC3, E-AC3, DTS, TrueHD, FLAC, Opus, Vorbis ...)
- Equalizer and volume boost up to 300%
- Subtitles: load .srt / .vtt / .ass / .ssa / .ttml, choose size, colour, style and position
- Audio and subtitle track selection and playback speed (gear icon on the controller)
- A-B repeat, sleep timer, frame-by-frame stepping, repeat one/all, shuffle
- Screenshot (saved to Pictures/RainaxVplay), video info, aspect ratio, rotate, picture-in-picture, screen lock
- Remembers where you stopped each video, plays the next video in the list

**Settings**: theme (dark / AMOLED black), default speed, skip length, autoplay next, remember position,
background audio, auto picture-in-picture, auto rotate, gesture switches, audio decoder mode, subtitle style.

## Build the APK on GitHub

1. Upload everything in this folder to a GitHub repository (keep the `.github` folder).
2. Every push to `main` builds the APK. Open the **Actions** tab, open the run, and download
   `RainaxVplay-release` from **Artifacts**.
3. Push a tag such as `v2.0.0` and the workflow also creates a **Release** with
   `RainaxVplay-release.apk` attached, which you can download directly on your phone.

The workflow installs Gradle itself, so no Gradle wrapper files are needed.

## Sign with your own key (recommended)

Without this, the APK is signed with a debug key. That installs fine, but if the key ever changes
Android will refuse to update over the older version. To use your own permanent key, in Termux:

    pkg install openjdk-17
    keytool -genkeypair -v -keystore ~/rainax.jks -alias rainax -keyalg RSA -keysize 2048 -validity 10000
    base64 -w0 ~/rainax.jks

Then in your GitHub repo go to Settings -> Secrets and variables -> Actions and add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the long text printed by the `base64` command |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `rainax` |
| `KEY_PASSWORD` | the key password you chose |

**Back up `rainax.jks` and its passwords.** If you lose them you can never update the app for existing users.

## Notes

- Video is decoded by the phone's hardware decoders. FFmpeg adds *audio* formats only, so a video codec
  your phone cannot decode (for example some old WMV files) will still not play.
- Not included: audio delay, online subtitle search, SMB/FTP/DLNA browsing, Chromecast.
- Package name `com.rainax.vplay`. Minimum Android 8.0 (API 26). Screenshots need Android 10+.
