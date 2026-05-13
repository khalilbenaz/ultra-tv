# Ultra TV

<p align="center">
  <img src="docs/logo.png" alt="Ultra TV" width="160" />
</p>

<p align="center">
  <strong>Native Android TV / Google TV IPTV player.</strong><br/>
  Kotlin · Jetpack Compose · Compose-TV · Media3 · Room · Hilt
</p>

<p align="center">
  <a href="https://github.com/khalilbenaz/ultra-tv/releases/latest/download/UltraTV-debug.apk">
    <img src="https://img.shields.io/badge/Download-Android%20TV%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" />
  </a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-0284c7?style=for-the-badge" alt="License" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin" />
  <img src="https://img.shields.io/badge/Compose--TV-1.0-4285F4?style=for-the-badge" />
  <img src="https://img.shields.io/badge/Media3-1.5-FF6F00?style=for-the-badge" />
</p>

---

## What is Ultra TV?

Ultra TV is a **fully native** Android TV IPTV client. D-pad navigation is handled by Compose-TV's focus tree (no WebView bridges), playback uses Media3 / ExoPlayer for native codec support, and the whole catalog (channels, movies, series, EPG, history, favorites) lives in a local Room database. It speaks **Xtream Codes**, **M3U / M3U8** (URL or local file), and **Stalker Portal** out of the box.

A companion **Cloudflare Worker** (in `cloudflare-config/`) provides a MAC-based remote-config dashboard so users can provision their providers from a web browser and have the TV pull them in one click.

## Features

### Catalog & providers
- 🎬 **Xtream Codes** · **M3U URL** · **M3U file from local storage** · **Stalker Portal** (MAC handshake + lazy `create_link` at play time)
- 🔁 **Multi-provider** — add as many as you want, pick the default in Settings (★ Default badge)
- 🚦 **De-duplication** — re-adding the same `(kind, url, username)` reuses the existing row instead of duplicating
- 🛰️ **Cloud sync via Cloudflare Worker** — paste your device MAC into the dashboard, add providers there, "Sync from cloud" pulls them all
- ⏱️ **Background sync via WorkManager** (every 6 / 12 / 24 h, or every launch)
- 📈 **Live sync progress banner** pinned to the top of every screen during sync

### Live TV
- 📺 **Tivimate-style two-pane layout**: categories on the left, channels of the selected category on the right
- 🔢 Channel position numbers, logos, focus highlight, **now-playing + next-up programme** under each name (from cached EPG)
- 🏷️ **Categories management** (search, bulk Hide / Show, "Hide adult" preset, 🔒 / 🔞 markers)
- 🧹 Cleans decorative wrappers (`### FRANCE ###` → `FRANCE`) for display while keeping DB intact

### Movies / Series
- 🎞️ **Netflix-style rails by category** (top 25 per rail), hero banner with the featured title
- 🟦 Focus scale animation (1.0 → 1.08, 160 ms tween)
- 🔍 Cross-content **search** (debounced 220 ms): channels + movies + series
- ★ **Favorites** (per kind, browsable from a dedicated screen)
- 📚 **Series episodes** loaded on demand via `get_series_info`, played through Media3

### Player
- ▶ **Media3 / ExoPlayer** — HLS, DASH, MPEG-TS, MP4 with hardware codec support
- 🎮 D-pad: directional keys = controller, OK = toggle play/pause, BACK = exit
- ⏸️ **Continue watching** (position recorded every 10 s + on dispose)
- 🚀 **Auto-play last watched on launch** option
- 🥷 **Open in external player** (VLC / MX / Just Player / Next Player) for codecs Media3 can't handle
- 💤 **Sleep timer** (15 min · 30 min · 1 h · 2 h · cancel) — pauses + exits player at the deadline
- 📊 **Stream stats overlay** — resolution / video & audio codec / frame rate / bitrate / buffer ahead, toggled from the player overlay

### Discovery / Home
- 🏠 Dynamic Home: **Continue watching**, **Recently watched**, **Movies**, **Series**, **Featured channels** rails
- 🆕 **First-time MAC card**: shows your device MAC + dashboard steps when no provider is configured
- 🗓 **TV Guide grid** (Tivimate-style): 12 h × N channels timeline with "now" indicator, refreshed from the provider's full `xmltv.php` feed (streaming pull-parser handles 50 MB+ feeds)
- ▦ **Multi-View**: up to 4 channels simultaneously in a 2×2 grid

### Personalization
- 🎨 **3 themes**: Dark · AMOLED · Blue
- 📐 **Menu position**: Sidebar (left) or Top bar — toggle in Settings
- 🌍 **Locale-aware** UI (follows system language)
- 🔄 **Boot autolaunch** — open Ultra TV automatically when the box finishes booting
- 🔢 Show / hide channel numbers, hide adult categories beyond PIN, resume playback toggle, auto-play next episode

### Backup & state
- 💾 **Export / restore** providers + favorites + watch history as a single JSON file (Storage Access Framework picker)

### Security
- 🔐 **Parental PIN** (SHA-256, DataStore-backed) — auto-locks adult categories on each sync when a PIN is set
- 🔒 **Per-channel lock** — Settings → Manage locked channels lets you flag individual channels; play prompts for the PIN
- 🆔 **Stable per-device MAC** derived from `ANDROID_ID` (hashed) — never the real WiFi MAC

### Performance
- 🖼️ **Coil ImageLoader** with 25 %-heap memory + 256 MB disk cache (no re-downloads on scroll)
- 📦 **Chunked DB inserts** (500 rows / batch) during sync — flat memory on huge catalogs
- ⚡ **DB indices** on `(providerId, categoryId)` for fast category filtering
- 🎯 SQL-level filtering for Live TV per category (only the visible subset materialises)
- 🧱 **R8 / ProGuard release build** with resource shrinking — **18 MB debug → 3.4 MB release** (latest APK shipped is the release variant)
- 📑 **Paging Room** for Movies / Series flat-grid (pages of 60, only ~120 items in memory regardless of catalog size)

### Distribution
- 🇩 **Downloader code `5248504`** — sideload via the [Downloader app](https://www.aftvnews.com/downloader/) on any Android TV box
- 🌐 GitHub Releases — latest APK at `releases/latest/download/UltraTV-debug.apk`

## Quick start

### Install on a TV box

1. Install the **Downloader** app on your Android TV box from Google Play.
2. Open Downloader, enter code **`5248504`**, press **Go**.
3. Allow install from unknown sources when prompted; install the APK.
4. On first launch, you'll see a **First-time setup** card with your device MAC.
5. Either:
   - Open **Settings** → tap **+ Xtream / + M3U URL / + M3U file / + Stalker portal** and fill in the form.
   - **Or** self-host the Cloudflare Worker, provision your MAC in its dashboard, then **Sync from cloud**.

### Build from source

```bash
git clone https://github.com/khalilbenaz/ultra-tv
cd ultra-tv/android-native

# JDK 17 is required (Android Gradle Plugin 8.7+)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS
./gradlew assembleDebug

# APK at app/build/outputs/apk/debug/app-debug.apk
```

For a smaller signed release build:

```bash
./gradlew assembleRelease
# ~8 MB APK at app/build/outputs/apk/release/app-release.apk
```

### Deploy the Cloudflare Worker (optional)

```bash
cd cloudflare-config
npm i -g wrangler

wrangler kv:namespace create CONFIG             # paste id/preview_id into wrangler.toml
wrangler kv:namespace create CONFIG --preview
wrangler secret put ADMIN_PASSWORD              # type a strong password
wrangler deploy
```

The Worker URL printed by wrangler is what you paste in the app's Settings → **Change** next to the Worker URL field.

## Architecture

```
android-native/
├── app/src/main/kotlin/com/ultratv/tv/nativeapp/
│   ├── MainActivity.kt         (entry point + nav host)
│   ├── UltraTvApp.kt           (Hilt + Coil + WorkManager Configuration.Provider)
│   ├── BootReceiver.kt         (BOOT_COMPLETED → MainActivity)
│   ├── data/
│   │   ├── db/                 (Room entities + DAOs)
│   │   ├── xtream/             (Xtream Codes player_api.php client)
│   │   ├── stalker/            (Stalker Portal handshake + create_link)
│   │   ├── m3u/                (M3U/M3U8 parser, URL or text input)
│   │   ├── repo/               (Provider / Catalog / History / PlaybackContext / SyncStatusBus)
│   │   ├── sync/               (WorkManager SyncWorker + SyncScheduler)
│   │   ├── parental/           (PIN store, SHA-256)
│   │   ├── prefs/              (UserPreferences, HiddenCategoriesStore)
│   │   └── config/             (DeviceMac, RemoteConfigImporter)
│   ├── di/                     (Hilt modules: DB / Network)
│   ├── nav/                    (Routes catalog)
│   └── ui/
│       ├── theme/              (Dark / AMOLED / Blue palettes)
│       ├── components/         (SidebarNav, TopBarNav)
│       ├── common/             (PosterCard, ContentRail, HeroBanner, …)
│       ├── home/               (rails + MAC onboarding card)
│       ├── live/               (Tivimate two-pane)
│       ├── movies/             (Rails view + Detail)
│       ├── series/             (Rails view + Detail with episodes)
│       ├── guide/              (EPG)
│       ├── search/             (cross-content)
│       ├── favorites/
│       ├── categories/         (Hide / Show + bulk)
│       ├── multiview/          (2×2 simultaneous ExoPlayers)
│       ├── player/             (Media3 PlayerView wrapper)
│       └── settings/           (Tabs + AddProviderDialogs + PreferencesSection)
└── cloudflare-config/          (Worker: KV-backed config per MAC + HTML dashboard)
```

## Roadmap

In active development / next iterations:

- 📊 **7-day xmltv** (current grid covers 12 h; longer window is a windowing change away)
- 🔍 **Full-text search index** (Room FTS4) — current LIKE is ok up to ~10k items
- 🌐 **Manual i18n** (FR / EN / ES / AR) — currently follows system locale
- 📻 **Chromecast** (Media3-cast)
- 🪟 **Picture-in-picture** when exiting the player
- 🎚️ **Subtitles + audio track selection** UI (Media3 already supports them)
- 📥 **Recording / DVR** via WorkManager + HLS download
- 🧪 **Stalker VOD / series** endpoints (only Live supported today)
- 🩹 **Onboarding wizard** for first launch (toast layer + dialog forms are done)

## Credits

Ultra TV — original work by [khalilbenaz](https://github.com/khalilbenaz). MIT-licensed.

The native Android TV codebase supersedes the earlier Capacitor WebView build (kept under `android-app/` and `web/` for historical reference) — Compose-TV's focus tree gave reliable D-pad navigation on every box we tested, including the Mecool KM7 Plus where the WebView bridge approach struggled.

If you fork / repackage, please keep the credit visible in the About screen.

## License

MIT. See [LICENSE](LICENSE).

## Disclaimer

Ultra TV is an IPTV **client**, not a content provider. It does not include, host or distribute any stream. Use only playlists, EPG sources and credentials you are authorized to access in your jurisdiction.
