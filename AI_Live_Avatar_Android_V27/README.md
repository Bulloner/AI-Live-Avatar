# AI Live Avatar V26

V19 melanjutkan V18 dengan fitur pemisahan latar foto avatar menggunakan ML Kit Selfie Segmentation.

## Fitur baru
- Tombol **Hapus Background Avatar** setelah memilih foto avatar.
- Segmentasi subjek foto dilakukan pada perangkat menggunakan ML Kit Selfie Segmentation.
- Foto hasil diproses menjadi PNG-like bitmap dengan kanal alpha, lalu ditampilkan di atas background LIVE pilihan.
- Pemrosesan foto dibatasi maksimal sisi 1024 px untuk mengurangi penggunaan memori.
- Ada status proses dan pesan jika foto gagal diproses.

## Fitur yang tetap ada
Face tracking (yaw/pitch, blink, smile), mic audio gate, framing avatar, background picker, mirror, idle motion, performance mode, dan Broadcast Preview 9:16.

## Build
Buka folder project ini dengan Android Studio, lakukan Gradle Sync, lalu Build > Build APK(s). Dependency ML Kit Selfie Segmentation diunduh Gradle saat sync, sehingga build pertama memerlukan internet.

## Batasan
Segmentasi bekerja pada foto avatar yang dipilih (single image), bukan pemotongan latar video/kamera secara real-time. Hasil bergantung pada pencahayaan, kontras, dan apakah tubuh/subjek terlihat jelas. Ini masih aplikasi preview; belum mengirimkan feed langsung ke TikTok LIVE.


## V20 additions
- Four quick expression presets: Natural, Senang, Kaget, and Tenang.
- Presets blend into the existing mic-driven mouth and face-tracking animation.
- All V19 features are retained.

Note: expression presets are lightweight 2D overlays/parameters, not a neural facial-animation model. Build and test on a physical Android device before using for a live broadcast.


## V21 additions
- Microphone sensitivity slider (0.1x–2.0x).
- Adjustable noise-gate threshold multiplier to help reduce room noise triggering mouth movement.
- Existing avatar, face tracking, segmentation, expression presets, and broadcast preview features retained.

Tips: Start with sensitivity at 1.0x and noise gate at 1.35x. If speech barely moves the mouth, raise sensitivity; if room noise triggers movement, raise the noise gate. Test with headphones and on the target phone before a live session.


## V22 additions — Avatar profile settings
- Save and load control profiles locally using Android SharedPreferences.
- Persists avatar framing (scale, X/Y, opacity), mouth/motion/smoothing values, microphone sensitivity/noise gate, and mirror/head-follow/performance/idle switches.
- Profile data stays on the device.

Important: the current profile save stores settings only. Avatar and background image files selected from the gallery are not copied into the profile yet; Android may require selecting those images again after reinstalling or clearing app data.


## V24 additions — Persistent avatar and background images
- Gallery selection now uses Android's document picker and requests a persistable read grant when supported.
- Selected avatar and background images are copied into the app's private storage, then restored automatically on the next app launch.
- The processed transparent avatar created by **Hapus Background Avatar** is also saved, so the cutout is retained after restarting the app.
- Existing V22 control-profile settings remain saved through SharedPreferences.

Notes: Images are stored inside the app's private data. They remain across ordinary app restarts and app updates, but are removed if the app is uninstalled or its data is cleared. The project is source code, not a prebuilt APK; build and test it with Android Studio on a physical Android device.


## V24 stability updates
- Added runtime permission handling for camera and microphone.
- Restarts face tracking and microphone metering when returning to the app.
- Releases AudioRecord when the app enters the background and handles initialization failures.
- Fixed face-detection completion handling and guards camera binding during pause.
- Closes ML Kit and executor resources when the Activity is destroyed.

This archive is Android source code, not a prebuilt APK. It has not yet been compiled or device-tested.


## V25 project handoff and verification
- Project metadata and build notes are normalized for the V25 handoff.
- Static check performed: AndroidManifest.xml parses successfully and every `R.id` referenced by MainActivity.kt exists in activity_main.xml.
- This static check is not a substitute for Gradle compilation, emulator testing, or testing on a physical Android phone.
- No Gradle Wrapper is bundled in this archive. Open the project with a recent Android Studio installation, allow Gradle sync, then select Build > Build Bundle(s) / APK(s) > Build APK(s).
- The app currently provides an in-app avatar preview; it does not inject the avatar as a virtual camera or guarantee direct TikTok LIVE integration. Use TikTok's supported screen-sharing workflow if available on the account/device.


## V26 metadata correction
- Android application metadata now uses versionCode 26 and versionName 26.0.
- This is a source-project update only. It has not been compiled or tested on a device.
- No Gradle Wrapper is included. The current working environment has Java but no detected Gradle executable or Android SDK, so an APK could not be built here.

## V27 handoff correction
- Root Gradle project name now matches the V27 archive.
- Android application metadata now uses versionCode 27 and versionName 27.0.
- This remains source code only; no APK is included and no successful Android Gradle compilation has been performed.
