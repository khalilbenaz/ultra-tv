# android-app — DEPRECATED

> **DEPRECATED — do not use for new work.**
> This is the **legacy Capacitor WebView shell** that wrapped the `web/` app into an
> Android TV / Google TV APK. It has been **superseded by [`../android-native`](../android-native)**,
> a fully native Jetpack **Compose-TV** application (Media3, Room, Hilt).
> This module is kept **for historical reference only**.

## Why it was replaced

The Capacitor/WebView bridge never gave reliable D-pad focus navigation across the
Android TV boxes we targeted (notably the Mecool KM7 Plus). The native Compose-TV
rewrite under `../android-native` uses Compose-TV's focus tree and Media3/ExoPlayer
directly, which solved the navigation and playback issues. All current features,
releases and documentation live there — see the root [`../README.md`](../README.md).

## Status

- **No releases are produced from this module.** The shipped APK comes exclusively
  from `../android-native`.
- No active development. Bug fixes and features go to `../android-native`.
- Firebase / `google-services` wiring was removed (there was never a
  `google-services.json` — it was dead boilerplate).
- The boilerplate `ExampleInstrumentedTest` / `ExampleUnitTest` were deleted
  (wrong package assertion, no real coverage).

## If you really insist on building it

This shell renders the web app, so the web bundle must exist first. The
`webDir` in `capacitor.config.json` points at `../web/dist`, which is **not**
checked in — you must build it:

```bash
# 1. Build the web app first (produces ../web/dist)
cd ../web && npm install && npm run build

# 2. Sync + build the Capacitor Android shell
cd ../android-app && npm install
npm run sync            # cap sync (rebuilds web + copies into android)
npm run build:apk       # cd android && ./gradlew assembleDebug
```

Again: this is for reference/experimentation only. Production lives in
`../android-native`.
