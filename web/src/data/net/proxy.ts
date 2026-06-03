// HTTP transport with optional Cloudflare Worker proxy.
//
// In the browser we can't set User-Agent, can't bypass CORS, and can't talk to
// http:// upstreams from an https:// page. The proxy worker (cloudflare/worker.js)
// solves all three. When configured (settings key "net.proxyUrl"), every Xtream /
// M3U / EPG call goes through it; the UA + Referer from the active provider are
// forwarded via X-SV-UA / X-SV-Referer headers and re-emitted upstream.
//
// If no proxy is configured we fall back to plain fetch — works only when the
// upstream has permissive CORS (rare for IPTV providers).

import { settingsRepo } from "@data/db/repositories";

const PROXY_KEY = "net.proxyUrl";

// When running inside Electron the renderer has full network access (webSecurity:
// false) and Chromium ships with extra codecs — no proxy is necessary, and
// going through one would only slow things down.
const IS_ELECTRON = typeof window !== "undefined" && (window as unknown as { __ultratv_env?: { isElectron?: boolean } }).__ultratv_env?.isElectron === true;

const DEFAULT_PROXY: string | null = IS_ELECTRON
  ? null
  : (import.meta as unknown as { env?: { VITE_DEFAULT_PROXY_URL?: string } }).env?.VITE_DEFAULT_PROXY_URL || null;

let cachedProxy: string | null | undefined;

async function getProxyUrl(): Promise<string | null> {
  if (cachedProxy !== undefined) return cachedProxy;
  const stored = await settingsRepo.get<string>(PROXY_KEY);
  cachedProxy = stored ?? DEFAULT_PROXY;
  return cachedProxy;
}

export function getDefaultProxy(): string | null {
  return DEFAULT_PROXY;
}

export function setProxyUrl(url: string | null) {
  cachedProxy = url;
  setSyncProxy(url);
  return settingsRepo.set(PROXY_KEY, url);
}

export interface FetchOptions {
  signal?: AbortSignal;
  userAgent?: string | null;
  referer?: string | null;
  method?: string;
  body?: BodyInit | null;
  /** Per-request timeout in ms. Default 10 min for catalog sync of huge playlists. */
  timeoutMs?: number;
}

const DEFAULT_TIMEOUT_MS = 30 * 60 * 1000;

function withTimeout(parent: AbortSignal | undefined, ms: number): { signal: AbortSignal; cancel: () => void } {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new DOMException(`Timeout after ${ms}ms`, "TimeoutError")), ms);
  if (parent) {
    if (parent.aborted) ctrl.abort(parent.reason);
    else parent.addEventListener("abort", () => ctrl.abort(parent.reason), { once: true });
  }
  return { signal: ctrl.signal, cancel: () => clearTimeout(timer) };
}

export async function proxiedFetch(targetUrl: string, opts: FetchOptions = {}): Promise<Response> {
  const proxy = await getProxyUrl();
  const headers = new Headers();
  if (opts.userAgent) headers.set("X-SV-UA", opts.userAgent);
  if (opts.referer) headers.set("X-SV-Referer", opts.referer);

  const { signal, cancel } = withTimeout(opts.signal, opts.timeoutMs ?? DEFAULT_TIMEOUT_MS);

  try {
    if (proxy) {
      const u = new URL(proxy);
      u.searchParams.set("target", targetUrl);
      return await fetch(u.toString(), {
        method: opts.method ?? "GET",
        headers,
        body: opts.body ?? null,
        signal,
      });
    }
    return await fetch(targetUrl, { method: opts.method ?? "GET", signal });
  } finally {
    cancel();
  }
}

export async function proxiedJson<T>(targetUrl: string, opts: FetchOptions = {}): Promise<T> {
  const res = await proxiedFetch(targetUrl, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  return (await res.json()) as T;
}

export async function proxiedText(targetUrl: string, opts: FetchOptions = {}): Promise<string> {
  const res = await proxiedFetch(targetUrl, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  return await res.text();
}

// Synchronous proxy URL builder using the cached value. Use after the proxy
// cache has resolved at least once. Returns the original URL if no proxy is set.
let syncProxy: string | null = null;
export function setSyncProxy(url: string | null) { syncProxy = url; }
export function proxify(url: string): string {
  if (!syncProxy) return url;
  if (!url) return url;
  // Already proxified — avoid double-wrapping
  if (url.startsWith(syncProxy)) return url;
  const u = new URL(syncProxy);
  u.searchParams.set("target", url);
  return u.toString();
}

// Hook to ensure the sync proxy mirror is initialised at startup.
export async function initProxyCache(): Promise<void> {
  const u = await getProxyUrl();
  setSyncProxy(u);
}
