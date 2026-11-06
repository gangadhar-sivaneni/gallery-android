<div align="center">

<img src="docs/logo.svg" width="112" alt="Gallery logo">

# Gallery

**A fast, private, offline gallery for Android with a built-in photo and video editor.**

[![Download APK](https://img.shields.io/badge/Download-Gallery.apk-4387F6?style=for-the-badge&logo=android&logoColor=white)](https://github.com/gangadhar-sivaneni/gallery-android/releases/latest/download/Gallery.apk)

[![Release](https://img.shields.io/github/v/release/gangadhar-sivaneni/gallery-android?color=4387F6)](https://github.com/gangadhar-sivaneni/gallery-android/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/gangadhar-sivaneni/gallery-android/total?color=4387F6)](https://github.com/gangadhar-sivaneni/gallery-android/releases)
[![Build](https://github.com/gangadhar-sivaneni/gallery-android/actions/workflows/build.yml/badge.svg)](https://github.com/gangadhar-sivaneni/gallery-android/actions/workflows/build.yml)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey)](LICENSE)

</div>

---

Gallery works only with what is on your phone. It has **no internet permission, no ads, no accounts and no tracking**. It does nothing in the background, so it uses no battery when closed.

## Download and install

1. On your phone, tap **[Download Gallery.apk](https://github.com/gangadhar-sivaneni/gallery-android/releases/latest/download/Gallery.apk)**.
2. Open the downloaded file. If Android asks, allow **Install unknown apps** for your browser or file manager.
3. If Play Protect shows *"unknown developer"*, tap **More details → Install anyway**. Any app from outside the Play Store gets this warning.
4. Open **Gallery** and allow access to photos and videos.

**Requirements:** Android 11 or newer, about 15 MB of space.

## Features

### Browse
- **All**, **Photos**, **Videos** and **Albums** tabs, with smooth scrolling through thousands of items.
- Smart dates: camera shots are sorted by when they were taken, and downloads by when they arrived on the phone.
- Group by day, month or year. Sort by date, name, path, size or random. Pinch to change the column count.
- Drag-to-select many items with one finger. Fast scroller. Search by name.
- Smart collections: Camera, Screenshots, WhatsApp, Downloads, Last 30 days and Large files.
- Albums: rename, change cover, pin, hide, sort, and move or copy items between albums.
- Favourites and Trash (deleted items can be restored).

### View
- Zoom photos with pinch and double-tap. Slideshow. Opens JPEG, PNG, WebP, HEIC, GIF, SVG and more.
- Video player:
  - skip 10 seconds back or forward
  - hold the screen for **2x speed**
  - swipe the left side for **brightness** and the right side for **volume**
  - change speed and aspect ratio, and lock the screen against touches
  - resumes where you left off
- Photo details, including camera and EXIF information.

### Edit
- **Photo editor:** crop with aspect presets, rotate, flip, filters, brightness, contrast, saturation, warmth and resize. Saves a copy or replaces the original.
- **Video editor:**
  - trim, then split at the playhead and delete parts
  - canvas sizes 9:16, 1:1, 4:5, 16:9 and 4:3, with a blurred or black background
  - crop, speed (with pitch kept), filters, adjustments, volume and mute
  - rotate and mirror, and save any frame as a photo
  - smooth live preview; exports at full quality

### Private and secure
- **Private folder** with its own PIN, stored inside the app and invisible to other apps.
- **App lock** with a separate PIN and fingerprint support. Turning it off requires the PIN.
- Screenshots are blocked on private screens, and the app is excluded from cloud backup.

### Make it yours
- Light, Dark, Black (AMOLED) or **your own photo** as the app background.
- 12 accent colours, a custom colour picker, or your wallpaper's colours.
- Set any photo as wallpaper, with a crop step for the home or lock screen.

## Permissions

| Permission | Why |
|---|---|
| Photos and videos | To show your media |
| Media location | To show where a photo was taken in Properties |
| Set wallpaper | For **Set as wallpaper** |
| Manage media *(optional)* | Lets rotate, delete and move work without a confirmation popup each time |
| All files access *(optional)* | Shows media that other apps (for example WhatsApp) have hidden |

There is no internet permission, so nothing ever leaves your phone.

## Build from source

```bash
git clone https://github.com/gangadhar-sivaneni/gallery-android.git
cd gallery-android
./gradlew assembleRelease
```

You need JDK 17 or newer and the Android SDK. Open the project in Android Studio, or point `local.properties` at your SDK. The APK is written to `app/build/outputs/apk/release/`.

To run the unit tests:

```bash
./gradlew testDebugUnitTest
```

## Built with

Kotlin · Jetpack Compose (Material 3) · Media3 ExoPlayer and Transformer · Coil 3 · AndroidX Biometric and ExifInterface

## License

[MIT](LICENSE). Developed by **Gangadhar Sivaneni**.
