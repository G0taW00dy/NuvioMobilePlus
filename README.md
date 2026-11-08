<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/G0taW00dy/NuvioMobilePlus/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Cybervulcan's personal build

> **Unofficial personal build.** Not made, endorsed or supported by the Nuvio team or by prneut. Please don't report problems with this build to them.

### Credits

This build exists thanks to:

- **[Nuvio](https://github.com/NuvioMedia/NuvioMobile)** by NuvioMedia: tapframe and all Nuvio contributors, who created and maintain the app. If you enjoy Nuvio, please [support the Nuvio team](https://nuvio.tv/support).
- **[Nuvio Mobile Plus](https://github.com/prneut/NuvioMobilePlus)** by **prneut**, who built the download features: batch season/episode downloads, the background download queue, the download size limit and more (details below).

### What this build adds

- Merges the latest official Nuvio automatically every week, so it stays current alongside the Plus download features.
- **Simkl sign-in updated to Simkl's AUTH V2** (required for new Simkl apps), with automatic token renewal so it stays connected.
- **Better handling of dropped connections** during playback (ExoPlayer): it keeps retrying for about two minutes, waits for the network to return, and resumes where it stopped instead of giving up.
- In-app updates come from this repository's [releases](https://github.com/G0taW00dy/NuvioMobilePlus/releases).

Licensed under the GNU GPL v3.0, like Nuvio. The full source for every release is in this repository.

## Nuvio Mobile Plus Fork

This fork (`Nuvio Mobile Plus`) brings additional enhancements on top of official Nuvio Mobile:
- **1-Click Movie & Episode Download**: Direct download trigger on the details screen, poster zoom overlay, and season episode list.
- **Autoplay Download Max File Size Limit**: Configurable max size slider in Stream Auto-Play Settings (default: 5 GB, customizable up to 20 GB or Unlimited) to prevent accidentally downloading huge 30GB+ remuxes.
- **Background Download Queue**: Downloads route directly to the native background scheduler without initiating playback.
- **Skip Next Controls**: Dedicated Next Episode player button in center player controls.
- **Custom In-App Updates**: Seamless update checks pointing to `prneut/NuvioMobilePlus`.

## Get Nuvio Mobile

- [Android APK (GitHub Releases)](https://github.com/prneut/NuvioMobilePlus/releases/latest)
- [Android on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- iOS via AltStore or SideStore: add [this source URL](https://raw.githubusercontent.com/NuvioMedia/NuvioMobile/cmp-rewrite/store.json) in the app's Sources section, then install Nuvio.

## Build from source

```bash
git clone https://github.com/prneut/NuvioMobilePlus.git
cd NuvioMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullRelease
```

### iOS

iOS development requires macOS and Xcode.

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

[GNU General Public License v3.0](./LICENSE)
