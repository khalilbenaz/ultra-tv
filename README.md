# Ultra TV

<p align="center">
  <img src="docs/logo.png" alt="Ultra TV" width="160" />
</p>

<p align="center">
  <strong>A modern, multi-platform IPTV player.</strong><br/>
  Web · Desktop (macOS / Windows / Linux) · Android TV / Google TV / Phone
</p>

<p align="center">
  <a href="#-quick-start"><img src="https://img.shields.io/badge/Quick%20start-30%20sec-2ea44f?style=for-the-badge" alt="Quick start" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-0284c7?style=for-the-badge" alt="License" /></a>
  <img src="https://img.shields.io/badge/Web-React%2018-61dafb?style=for-the-badge&logo=react" />
  <img src="https://img.shields.io/badge/Desktop-Electron-47848f?style=for-the-badge&logo=electron" />
  <img src="https://img.shields.io/badge/Android%20TV-Capacitor-119eff?style=for-the-badge&logo=android" />
</p>

---

## What is Ultra TV?

Ultra TV is a fast, polished IPTV client that runs everywhere: your browser, your desktop, your Android phone, and **Android TV / Google TV**. One UI, one set of features, one IndexedDB-backed local catalog.

It speaks **Xtream Codes**, **M3U/M3U8** and **Stalker Portal**. It plays **HLS, DASH, MPEG-TS, MP4** through the right adapter (hls.js, shaka-player, mpegts.js, native `<video>`). When a codec the browser can't decode shows up (HEVC, AC3, MKV…), Ultra TV hands the stream off to **VLC** or **ExoPlayer** with a single click on Android, or `vlc://` on desktop.

## Highlights

- 🎬 **Movies, Series, Live TV, EPG Guide, DVR (browser MediaRecorder + OPFS), Multi-View (up to 4 tiles)**
- 🌍 **Multi-language UI** — English · Français · العربية (RTL native), pluggable
- 🔍 **Cmd/Ctrl-K command palette** for instant navigation across the whole catalog
- 🎲 **Random / "I'm feeling lucky"**, full **sort + genre + year filters** on Movies & Series
- ★ **Favorites with drag-drop reorder**, separate **Watchlist** tab
- 📺 **Continue Watching** with auto-resume, **Top Rated**, **Recently Added** rails
- 🇫🇷 **Category filter grouped by detected language** (FR / EN / AR / ES / DE / IT / PT / TR) — pre-sync whitelist saves bandwidth on huge providers
- 🔐 **Parental controls** (PIN + auto-lock adult categories)
- 📡 **Chromecast + AirPlay** sender support
- ▶ **VLC / ExoPlayer / MX / Just Player / Next Player** handoff on Android for codecs the WebView can't decode
- 💾 **Backup / Restore** the full local state (settings, history, favorites, …) as a single JSON file
- 🛰️ **Catch-up** for live channels that advertise it; **reminders** for upcoming programs (browser notifications)
- ⌨️ **Keyboard shortcuts** (space, ←/→, F, P, M, [ ], 0-9, /, Cmd-K, Shift-D)
- 🧮 **Picture-in-Picture, fullscreen, playback speed, audio/subtitle track selection, external `.srt`/`.vtt` upload**
- 📊 **Per-stream stats overlay** (resolution, bitrate, dropped frames, buffer) — Shift+D
- 📱 **Installable PWA**, **offline shell**, **dark + light themes**, **collapsible sidebar**

## Download

<p align="center">
  <a href="https://github.com/khalilbenaz/ultra-tv/releases/latest/download/UltraTV-debug.apk">
    <img src="https://img.shields.io/badge/Download-Android%20TV%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Download Android TV APK" />
  </a>
  <a href="https://github.com/khalilbenaz/ultra-tv/releases/latest">
    <img src="https://img.shields.io/github/v/release/khalilbenaz/ultra-tv?style=for-the-badge&color=8b5cf6" alt="Latest release" />
  </a>
  <a href="https://github.com/khalilbenaz/ultra-tv/releases">
    <img src="https://img.shields.io/github/downloads/khalilbenaz/ultra-tv/total?style=for-the-badge&color=ff5f5f" alt="Total downloads" />
  </a>
</p>

- **Android / Android TV / Google TV** → [latest APK](https://github.com/khalilbenaz/ultra-tv/releases/latest/download/UltraTV-debug.apk)

  <a href="https://www.aftvnews.com/downloader/"><img src="docs/downloader-logo.png" alt="Downloader app" width="420" /></a>

  - **Easiest on a TV box** — install the free [**Downloader**](https://www.aftvnews.com/downloader/) app from the Play Store / Amazon Appstore, open it and type the short code **`5248504`** to fetch the APK directly.
  - Or sideload over USB / network: `adb install UltraTV-debug.apk`
  - Or copy the APK to a USB stick / cloud drive and open it with any file manager on the TV
- **Desktop** → build via `electron/` (macOS `.dmg`, Windows `.exe`, Linux `.AppImage` / `.deb`)
- **Web** → host the static `web/dist/` anywhere; or run `npm run dev` for local

## Quick start

### Web (instant)

```bash
cd web
npm install
npm run dev          # http://localhost:5173
npm run build        # production bundle in dist/
```

For production deployment the static `dist/` folder is hostable anywhere (Cloudflare Pages, Vercel, Netlify, S3+CloudFront, a plain Nginx…).

> **Cross-origin note:** IPTV servers rarely send CORS headers and almost always serve cleartext HTTP. To reach them from an https:// page the web build routes requests through a small proxy. A reference implementation is provided in `web/vercel-proxy/`, `web/cloudflare/` and `web/deno-proxy/` — deploy whichever you prefer with a single command.

### Desktop (macOS / Windows / Linux)

```bash
cd electron
npm install
npm run dev                 # launches Electron pointing at the dev server
npm run package:mac         # produces .dmg + .zip in electron/release/
npm run package:win         # .exe / portable
npm run package:linux       # AppImage + .deb
```

Desktop wraps the web UI in an Electron BrowserWindow with `webSecurity: false` and stripped response headers — no proxy needed, native codecs through Chromium.

### Android / Android TV / Google TV

```bash
cd android-app
npm install
npm run sync                # builds web/dist + copies into android/
npm run build:apk           # debug APK
# Output: android/app/build/outputs/apk/debug/app-debug.apk

# Or for a release build (requires signing):
npm run build:release
```

The Android manifest declares `LEANBACK_LAUNCHER` so the app appears on Android TV and Google TV launchers. A custom Capacitor plugin (`ExternalPlayerPlugin`) lets the UI hand a stream off to **VLC**, **MX Player**, **Just Player**, **Next Player** or the system chooser — perfect for HEVC / AC3 / MKV that the WebView can't decode.

## Architecture

```
ultra-tv/
├── web/                    React + TypeScript + Vite, IndexedDB (Dexie), hls.js,
│                           shaka-player, mpegts.js, zustand stores
├── electron/               Desktop shell (main.cjs, preload.cjs, electron-builder)
├── android-app/            Capacitor wrapper; native ExternalPlayer plugin
│   └── android/            Generated Gradle project
├── web/cloudflare/         CORS/UA proxy as a Cloudflare Worker
├── web/vercel-proxy/       Same proxy as a Vercel Edge Function
├── web/deno-proxy/         Same proxy on Deno Deploy
└── web/val-town/           Same proxy as a Val Town HTTP val (paste-and-go)
```

Domain layer (`web/src/domain/model`) is shared by every screen. Data layer (`web/src/data`) owns Dexie schema, sync workers, parsers (M3U, XMLTV, Xtream API client, Stalker client). Player layer (`web/src/player`) abstracts HLS / DASH / progressive / MPEG-TS behind a single `WebPlayerEngine`.

## What works

| Area | Web | Desktop | Android / TV |
|---|---|---|---|
| HLS (m3u8) | ✅ via hls.js (+ proxy rewriting) | ✅ native | ✅ |
| DASH (mpd) + Widevine/PlayReady | ✅ via shaka-player | ✅ | ✅ |
| MPEG-TS direct | ✅ via mpegts.js (CDN) | ✅ | ✅ via ExoPlayer handoff |
| HEVC / AC3 / EAC3 / MKV | ⚠ depends on browser | ✅ (Chromium codecs) | ✅ via VLC / ExoPlayer handoff |
| Chromecast | ✅ Cast Web Sender | ✅ | ✅ |
| AirPlay | ✅ Safari | macOS only | n/a |
| PiP / Fullscreen | ✅ | ✅ | ✅ |
| MediaRecorder DVR | ✅ Chrome / Edge | ✅ | partial |
| Notifications (reminders) | ✅ | ✅ | ✅ |

## Configuration

Everything lives **locally** in IndexedDB. No account, no cloud. Provider credentials never leave the device.

The optional network proxy URL is set at build-time via `VITE_DEFAULT_PROXY_URL` (see `web/.env.production`) and can be overridden at runtime in **Settings → Network**.

### Pick your proxy

For the easiest path, deploy the Val Town variant — see [`web/val-town/README.md`](web/val-town/README.md). 60 seconds, no CLI:

1. Sign in at https://www.val.town/
2. New → HTTP val → paste [`web/val-town/proxy.ts`](web/val-town/proxy.ts)
3. Save → grab the `https://<user>-<val>.web.val.run` URL
4. Paste it into Ultra TV → Settings → Network

Cloudflare / Vercel / Deno Deploy alternatives are in their own folders with one-line deploy commands.

## Roadmap

- TMDB metadata enrichment (posters, synopsis, ratings)
- OpenSubtitles auto-download
- Combined-M3U virtual providers (merge several M3U sources into one)
- Mini-player floating across navigation
- Trakt.tv sync
- Voice search (Web Speech API)

## License

MIT. See [LICENSE](LICENSE).

## Disclaimer

Ultra TV is an IPTV **client**, not a content provider. It does not include, host or distribute any stream. Use only playlists, EPG sources and credentials you are authorized to access in your jurisdiction.
