# Mallard — the Android remote for MusicD LMS Remote

Mallard is a native Android app (Jetpack Compose) for controlling a
[MusicD LMS Remote](../README.md) server, and through it your Lyrion Music
Server. Its screens, colours and type come from Mandarin Remote's app.
Its Settings follow this server's own Settings page.

What it does:

- **Home**: the rows the server's Home has, in the order set in Settings → Home
  Screen.
- **Library**: Focus filters, sorts and the name filter.
- **Album pages**: the heart, Play Now, Queue and ⋯, tracks you can select, and
  About.
- **Now Playing**: shuffle, repeat, seek and volume, plus the queue.
- **Smart Picks** and **Pitchfork**.
- **Players**: switch player, group (LMS sync) and power.
- **Lock screen and notification**: controls on both, and the volume keys move
  the player's volume.
- **Home-screen widget** and a **Quick Settings tile** for a random album.

## Installing a test build

Every push to `dev` that changes `android/` builds an APK and publishes it on
the rolling **mallard-test** pre-release:
<https://github.com/meltface-80/MusicD-LMS-Remote/releases/tag/mallard-test>.
Download `mallard-test.apk` on an Android 8.0+ phone and open it. You may need
to allow installs from your browser.

On first start, Mallard asks for the server. Leave the address empty and it
searches your Wi-Fi for a MusicD LMS Remote on port 3390, or type the
address yourself.

## Building

```
cd android
./gradlew :core:test :app:assembleRelease
```

- **`core`** is plain Kotlin/JVM, with no Android in it: the API client,
  models and view logic. Its tests run anywhere a JDK is installed.
- **`app`** needs the Android SDK (compileSdk 36).
- **CI**: `.github/workflows/mallard.yml` builds both.
- **Signing without secrets**: test builds use the public key committed at
  `app/mallard-debug.keystore`, so each one installs over the last.
- **Signing with your own key**: set the `MALLARD_KEYSTORE_BASE64` and
  `MALLARD_KEYSTORE_PASSWORD` repository secrets. An APK signed with one key
  will not install over one signed with the other.
