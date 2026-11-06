# Pocket Folder Player

A small, offline, folder-based Android music player. Select a folder and play MP3 / MP4 audio files directly from it. No catalogue import, artwork or internet connection.

## Features

- Android folder picker, with **only files directly inside the selected folder** (no subfolders).
- Multiple saved favourite folders. Music is read in place, not copied or modified.
- Play/pause, next/previous, seek, shuffle, repeat off/all/one and alphabetical filename sorting.
- System light/dark theme; no album-art or artist metadata display.
- Equaliser with Flat, Bass, Rock, Pop and Classical presets, plus adjustable bands on supported devices.
- Background playback and standard Android lock-screen / Bluetooth media controls.
- Saves the last folder, track, seek position and playback settings, without autoplaying on launch.

Supported audio containers are `.mp3` and `.mp4`; playback depends on the codecs supported by the phone. MP4 video is not displayed.

## Build

The project uses **Kotlin 2.2.20**, **Jetpack Compose**, **Media3**, **Android Gradle Plugin 8.11.1**, **Gradle 8.13**, **JDK 17**, Android compile SDK **36**, min SDK **26**.

**GitHub Actions:** Open the **Actions** tab and run **Build Android APK**, or push a commit to `main`. The workflow builds an Android debug APK and publishes it as an artifact.

**Local build:** Install JDK 17, Android SDK 36, Android build-tools 36.0.0 and Gradle 8.13. Run:

```sh
gradle :app:assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

> This repository does not currently include the Gradle wrapper. The CI workflow installs Gradle 8.13 explicitly.

## Code layout

- `MainActivity.kt`: Compose screens, player controls and display state.
- `PlaybackService.kt`: Media3 playback and background media session.
- `FolderStore.kt`: Android Storage Access Framework folder access and saved favourites.
- `EqualizerEngine.kt`: Android audio-session equaliser and persisted presets.

Music files stay on the device, with no network permission. Folder access uses the system picker and Android's persisted read permissions. Some protected Android folders cannot be selected.

## Status

The previous single-file GitHub Actions workflow successfully produced a debug APK. This repository layout exposes the same compiled source as editable files. Device-level functionality should still be tested on a real Android handset.

No open-source licence has been selected; all rights are reserved by default.
