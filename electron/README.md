# Ultra TV — Electron shell

Thin Electron wrapper around the Ultra TV web app. It exists for two reasons the
browser can't satisfy:

1. **Proprietary codecs** — decode HEVC / H.265, AC3, EAC3, and MKV streams that
   many IPTV providers serve.
2. **Direct upstream access** — `webSecurity:false` + response-header rewriting so
   `hls.js` / `shaka` reach segments, keys and DRM licenses directly, with no
   CORS proxy. (See `main.cjs` for the full rationale.)

## Why the castlabs Electron fork

Stock Electron ships a **stripped Chromium without proprietary codecs** — HEVC,
AC3 and EAC3 are absent, which defeats this shell's whole purpose. We therefore
pin the `electron` devDependency to the
[`@castlabs/electron-releases`](https://github.com/castlabs/electron-releases)
fork. The package is **not published on the public npm registry** — castlabs
distributes through GitHub releases, so the dependency pins a GitHub tag
directly (an exact tag, because semver ranges ignore the `+wvcus` build
metadata and could silently resolve to a non-Widevine build):

```jsonc
// package.json
"electron": "github:castlabs/electron-releases#v33.4.11+wvcus"
```

The `+wvcus` tag suffix denotes the Widevine-CDM-enabled US build line. The major
(`33`) is kept in lockstep with the stock Electron we were on (`^33.2.1`) so the
Chromium/Node ABI and `main.cjs` APIs don't change.

### Codec support gained vs. stock Electron

| Codec / container | Stock Electron | castlabs fork |
| ----------------- | -------------- | ------------- |
| H.264 / AAC       | yes            | yes           |
| **HEVC / H.265**  | no             | **yes**       |
| **AC3 / EAC3**    | no             | **yes**       |
| **MKV / Matroska**| partial        | **yes**       |
| Widevine DRM CDM  | no             | **yes** (`wvcus`) |

The `PlatformHEVCDecoderSupport` feature flag in `main.cjs` is kept: with the
fork the decoder is already present, the flag merely prefers the OS/GPU HW decode
path and is a harmless no-op where unsupported.

## Packaging notes (electron-builder)

`build.directories.buildResources` points at `electron/build/`. electron-builder
auto-discovers `icon.icns` (mac), `icon.ico` (win) and `icon.png` (linux) there.

When packaging the **castlabs** fork, follow castlabs' own guidance:

- The fork must be installed so electron-builder's `electronDist` resolves to it
  (the npm alias above makes the `electron` package *be* the fork, so this works
  out of the box). Verify the packaged binary actually has the codecs.
- **Widevine / EVS**: shipping the Widevine CDM for production distribution
  requires component-update / Verified Media Path (EVS) signing per castlabs'
  docs — see <https://github.com/castlabs/electron-releases#evs>. Unsigned dev
  builds play non-DRM HEVC/AC3 fine; DRM playback in a packaged app needs the EVS
  step.

## Icons

Source artwork: `../docs/redesign/logo.svg` (1024×1024). Binary icons are **not**
committed — generate them into `electron/build/` before packaging:

```bash
npm run icons   # electron-icon-builder --input=../docs/redesign/logo.svg --output=./build --flatten
```

This produces `icon.icns`, `icon.ico` and `icon.png` consumed by
`package:mac` / `package:win` / `package:linux`. Without them electron-builder
falls back to the default Electron icon. If `electron-icon-builder` chokes on the
SVG, first rasterize the logo to a ≥1024px PNG and point `--input` at that.

## Scripts

| Script           | Purpose                                            |
| ---------------- | -------------------------------------------------- |
| `dev`            | `cross-env SV_DEV=1 electron .` — loads `localhost:5173` |
| `build:web`      | Build the web bundle (`../web`)                    |
| `icons`          | Generate app icons from the logo into `build/`     |
| `package:mac/win/linux` | Build web then run electron-builder         |
