// Ultra TV — Electron shell.
//
// Why Electron exists here: the browser can't decode HEVC / AC3 / EAC3 / MKV that
// many IPTV providers serve. Stock Electron ships a stripped Chromium WITHOUT
// these proprietary codecs, so we depend on the `@castlabs/electron-releases`
// fork (see electron/README.md) which bundles a full-codec Chromium + Widevine.
// We also strip CORS so the app can hit any upstream directly — no
// Cloudflare/Vercel proxy needed.

const { app, BrowserWindow, session, shell } = require("electron");
const path = require("node:path");
const url = require("node:url");

// Opt into platform/GPU HEVC hardware decode paths. With the castlabs fork the
// HEVC decoder is already present; this flag just nudges Chromium to prefer the
// OS/GPU decoder where available. Harmless no-op on builds/platforms that lack
// it — kept because it costs nothing and helps HW accel on Windows/macOS.
app.commandLine.appendSwitch("enable-features", "PlatformHEVCDecoderSupport");
// NOTE: ignore-certificate-errors is a Chromium command-line switch and is
// therefore process-global by nature — it cannot be scoped per-request. Many
// IPTV providers serve segments over self-signed / expired TLS certs, so this
// is required for direct-stream playback. Left global intentionally.
app.commandLine.appendSwitch("ignore-certificate-errors");

let mainWindow = null;

// Minimal self-contained fallback page shown when the app fails to load or the
// renderer crashes/hangs. No external assets — inlined so it works even when the
// real bundle is unreachable. The Retry button reloads the window.
function buildErrorPage(title, detail) {
  const safeTitle = String(title).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
  const safeDetail = String(detail).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
  const html = `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ultra TV — Error</title>
<style>
  html,body{height:100%;margin:0}
  body{background:#0b1020;color:#e8ecff;font-family:system-ui,Segoe UI,Roboto,sans-serif;
       display:flex;align-items:center;justify-content:center;text-align:center;padding:24px}
  .card{max-width:560px}
  h1{font-size:20px;margin:0 0 12px}
  p{opacity:.75;margin:0 0 20px;font-size:14px;word-break:break-word}
  code{display:block;background:#161c33;padding:12px;border-radius:8px;font-size:12px;
       text-align:left;white-space:pre-wrap;margin:0 0 20px;opacity:.85}
  button{background:#4f6bff;color:#fff;border:0;border-radius:8px;padding:10px 22px;
         font-size:14px;cursor:pointer}
  button:hover{background:#6680ff}
</style></head>
<body><div class="card">
  <h1>${safeTitle}</h1>
  <p>Ultra TV couldn't load the application.</p>
  <code>${safeDetail}</code>
  <button onclick="location.reload()">Retry</button>
</div></body></html>`;
  return "data:text/html;charset=utf-8," + encodeURIComponent(html);
}

function showFallback(win, title, detail) {
  console.error(`[ultra-tv] ${title}: ${detail}`);
  if (!win || win.isDestroyed()) return;
  win.loadURL(buildErrorPage(title, detail)).catch((e) => {
    console.error("[ultra-tv] Failed to load fallback page:", e);
  });
}

function attachFailureHandlers(win) {
  const wc = win.webContents;

  // Navigation/resource load failed. Ignore the data: fallback itself, sub-frame
  // failures, and user-aborted loads (errorCode -3).
  wc.on("did-fail-load", (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
    if (!isMainFrame || errorCode === -3) return;
    if (typeof validatedURL === "string" && validatedURL.startsWith("data:text/html")) return;
    showFallback(win, "Load failed", `${errorDescription} (${errorCode})\n${validatedURL}`);
  });

  // Renderer process crashed or was killed.
  wc.on("render-process-gone", (_event, details) => {
    showFallback(win, "Renderer crashed", `${details.reason} (exitCode ${details.exitCode})`);
  });

  // Renderer is hung (event loop blocked). Offer the same recovery page.
  win.on("unresponsive", () => {
    showFallback(win, "Application unresponsive", "The renderer stopped responding.");
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 500,
    backgroundColor: "#0b1020",
    title: "Ultra TV",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, "preload.cjs"),
      // webSecurity stays DISABLED on purpose — do not flip to true.
      // In Electron the web app sets DEFAULT_PROXY=null (see web/src/data/net/proxy.ts):
      // hls.js/shaka load manifests, segments, keys and DRM licenses by hitting the
      // IPTV upstream DIRECTLY from the renderer. Those upstreams are typically
      // cross-origin, often cleartext http:// (mixed content vs the app origin),
      // and send no CORS headers. The onHeadersReceived ACAO injection below covers
      // the CORS *response* check, but webSecurity:true would still block the
      // cross-origin/mixed-content *requests* themselves, breaking playback.
      // Renderer-side risk is contained: contextIsolation:true + nodeIntegration:false.
      webSecurity: false,
    },
    autoHideMenuBar: true,
  });

  // Strip restrictive response headers (CSP, X-Frame-Options) on IPTV calls so
  // hls.js / shaka can reach segments straight from the providers.
  // Scoped to remote (http/https) URLs only: never rewrite headers for the app's
  // own file:// (prod) or localhost dev-server assets — those don't need it and
  // relaxing them on our own origin would only weaken the shell unnecessarily.
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    let isRemote = false;
    try {
      const proto = new URL(details.url).protocol;
      isRemote = proto === "http:" || proto === "https:";
    } catch {
      isRemote = false;
    }
    if (!isRemote) {
      callback({ responseHeaders: details.responseHeaders });
      return;
    }
    const headers = details.responseHeaders || {};
    for (const k of Object.keys(headers)) {
      const lk = k.toLowerCase();
      if (lk === "x-frame-options" || lk === "content-security-policy") delete headers[k];
    }
    headers["access-control-allow-origin"] = ["*"];
    callback({ responseHeaders: headers });
  });

  // External links open in the system browser, not in the app — but only for
  // http(s). Reject any other scheme (file:, javascript:, custom protocols, etc.)
  // so a malicious page can't ask the OS to launch arbitrary handlers.
  mainWindow.webContents.setWindowOpenHandler(({ url: targetUrl }) => {
    try {
      const proto = new URL(targetUrl).protocol;
      if (proto === "http:" || proto === "https:") {
        void shell.openExternal(targetUrl);
      } else {
        console.warn(`[ultra-tv] Blocked openExternal for non-http(s) URL: ${targetUrl}`);
      }
    } catch (err) {
      console.warn(`[ultra-tv] Blocked openExternal for unparseable URL: ${targetUrl}`, err);
    }
    return { action: "deny" };
  });

  attachFailureHandlers(mainWindow);

  const isDev = !!process.env.SV_DEV;
  if (isDev) {
    mainWindow.loadURL("http://localhost:5173");
    mainWindow.webContents.openDevTools({ mode: "detach" });
  } else {
    const indexPath = path.join(__dirname, "..", "web", "dist", "index.html");
    mainWindow.loadURL(url.pathToFileURL(indexPath).toString());
  }

  mainWindow.on("closed", () => { mainWindow = null; });
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
