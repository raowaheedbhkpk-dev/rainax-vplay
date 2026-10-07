# Rainax Vplay

A free, ad-free Android video player in the spirit of VLC, built with Kotlin and Media3 ExoPlayer.

## Features

- Browse all videos on the device, by folder or as one list, with thumbnails
- Search, sort (name / date / size / duration), resume progress bars
- Open network streams (HTTP, HLS `.m3u8`, DASH `.mpd`)
- Opens from other apps ("Open with" on video files and links)
- Player gestures
  - Swipe left side up/down: brightness
  - Swipe right side up/down: volume
  - Swipe horizontally: seek
  - Double-tap left / right: -10s / +10s, double-tap centre: play/pause
  - Press and hold: 2x speed while held
- Playback speed, audio track and subtitle track selection (gear icon in the controller)
- Load external subtitles (.srt, .vtt, .ass, .ssa, .ttml)
- Aspect ratio (fit / fill / zoom), rotate, picture-in-picture, screen lock
- Remembers where you stopped each video, plays the next video in the folder

## Build the APK on GitHub

1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab and run **Build Rainax Vplay APK** (it also runs on every push).
3. When it finishes, download the `RainaxVplay-release` (or `-debug`) artifact. Unzip it and install the `.apk`.

The workflow installs Gradle itself, so no Gradle wrapper files are needed.

## Build locally

Open the folder in Android Studio (Koala or newer) and press Run, or use `gradle assembleDebug`.

## Notes

- Playback uses Android's hardware decoders through ExoPlayer. Most MP4, MKV, WebM, TS, MOV and 3GP files work, but formats the phone itself cannot decode (some AVI/WMV/FLV files, certain audio codecs such as DTS) may not.
- The release APK is signed with the debug key so it installs directly. Use your own keystore before publishing to the Play Store.
- Package name: `com.rainax.vplay`. Minimum Android version: 8.0 (API 26).
