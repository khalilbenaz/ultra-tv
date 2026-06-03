/**
 * Ultra TV — MAC-based remote config Worker (account-per-MAC).
 *
 * Auth model:
 *   - Each MAC is its own "account". The user signs up with (MAC, password)
 *     which creates the KV entry and stores a salted SHA-256 hash.
 *   - Login = (MAC, password). Session = HMAC-signed cookie carrying
 *     `mac.expiry`, signed with env.SESSION_SECRET (falls back to
 *     env.ADMIN_PASSWORD so existing deployments keep working).
 *   - The dashboard only ever shows the cookie's MAC. There is no global
 *     admin / list of all MACs anymore.
 *
 * Public:
 *   GET  /api/config/:mac          → app polls its own config (`?password=`
 *                                    required when a password is set).
 *
 * Browser-authenticated (cookie carries the MAC):
 *   GET  /                         → dashboard for cookie.mac
 *   GET  /login                    → login form
 *   POST /login                    → check (mac, password), issue cookie
 *   GET  /signup                   → signup form
 *   POST /signup                   → create new account, issue cookie
 *   GET  /logout
 *   POST /api/provider/:mac        → add a provider (cookie.mac must match)
 *   POST /api/provider/:mac/:idx/delete
 *   POST /api/config/:mac/delete   → delete the account (drops the KV entry)
 *   POST /api/password/:mac        → change password
 *   POST /api/config/:mac          → power-user raw JSON save
 *
 * KV `CONFIG`: stores the JSON config under the lowercased colon-separated MAC.
 *   Schema: { providers: [...], passwordHash: string, salt: string }
 */

const COOKIE_NAME = "uconf_sess";
const SESSION_HOURS = 24 * 7; // a week — these are app installs, not banking

const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
  "access-control-allow-headers": "authorization,content-type",
};

function json(body, init = {}) {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { "content-type": "application/json; charset=utf-8", ...corsHeaders, ...(init.headers || {}) },
  });
}

function html(body, init = {}) {
  return new Response(body, {
    status: init.status ?? 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      // Keep the crash/event viewer's ?token= out of Referer headers, and stop
      // browsers from MIME-sniffing these HTML responses into anything else.
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      ...(init.headers || {}),
    },
  });
}

function redirect(to, init = {}) {
  return new Response("", { status: init.status ?? 302, headers: { location: to, ...(init.headers || {}) } });
}

function normaliseMac(raw) {
  let s = (raw || "").trim();
  try { s = decodeURIComponent(s); } catch (_) { /* leave as-is */ }
  s = s.toLowerCase();
  const hex = s.replace(/[^a-f0-9]/g, "");
  if (hex.length !== 12) return null;
  return hex.match(/.{2}/g).join(":");
}

function sessionSecret(env) {
  // Prefer a dedicated secret; fall back to ADMIN_PASSWORD so existing
  // deployments keep working. Fail closed: if neither is configured we refuse
  // to sign/verify with a guessable constant — callers turn this into a 500.
  const s = env.SESSION_SECRET || env.ADMIN_PASSWORD;
  if (!s) throw new ConfigError("SESSION_SECRET (or ADMIN_PASSWORD) not configured");
  return s;
}

// Thrown by sessionSecret() when no signing secret is configured.
class ConfigError extends Error {}

/**
 * Constant-time string compare. Avoids leaking how many leading chars matched
 * via early-return timing. Length check first (lengths aren't secret), then
 * XOR-accumulate over char codes so the loop runs the full length regardless.
 */
function timingSafeEqual(a, b) {
  a = String(a == null ? "" : a);
  b = String(b == null ? "" : b);
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---- Session (signed cookie) -----------------------------------------------

async function hmac(secret, msg) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(msg));
  return btoa(String.fromCharCode(...new Uint8Array(sig))).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
}

async function makeSession(env, mac) {
  const exp = Date.now() + SESSION_HOURS * 3600 * 1000;
  const payload = `${mac}.${exp}`;
  const sig = await hmac(sessionSecret(env), payload);
  return `${payload}.${sig}`;
}

/** Returns the authed MAC, or null. */
async function verifySession(value, env) {
  if (!value) return null;
  const parts = value.split(".");
  if (parts.length !== 3) return null;
  const [mac, exp, sig] = parts;
  if (Date.now() > parseInt(exp, 10)) return null;
  const want = await hmac(sessionSecret(env), `${mac}.${exp}`);
  if (!timingSafeEqual(want, sig)) return null;
  return normaliseMac(mac);
}

function readCookie(req, name) {
  const raw = req.headers.get("cookie") || "";
  for (const part of raw.split(";")) {
    const [k, v] = part.trim().split("=", 2);
    if (k === name) return decodeURIComponent(v || "");
  }
  return null;
}

async function authedMac(req, env) {
  const c = readCookie(req, COOKIE_NAME);
  return c ? await verifySession(c, env) : null;
}

/**
 * Stateless CSRF token derived from the session cookie. We re-HMAC the
 * session value with a "csrf" suffix and take the first 32 chars — same
 * secret as session signing, so revoking the session invalidates the token.
 * Returns null when there's no session.
 */
async function csrfFor(req, env) {
  const sess = readCookie(req, COOKIE_NAME);
  if (!sess) return null;
  return (await hmac(sessionSecret(env), `${sess}.csrf`)).slice(0, 32);
}

/**
 * Read the form body, then verify the embedded csrf field matches the token
 * derived from the caller's session cookie. Returns the parsed FormData on
 * success; on mismatch returns null so callers can short-circuit with 403.
 */
async function requireCsrf(req, env) {
  const want = await csrfFor(req, env);
  if (!want) return null;
  const f = await req.formData();
  return if_then_else(timingSafeEqual((f.get("csrf") || "").toString(), want), f, null);
}
function if_then_else(cond, a, b) { return cond ? a : b; }

function sessionCookie(value) {
  return `${COOKIE_NAME}=${encodeURIComponent(value)}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${SESSION_HOURS * 3600}`;
}

function clearCookie() {
  return `${COOKIE_NAME}=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax`;
}

// ---- KV helpers ------------------------------------------------------------

async function readConfig(env, mac) {
  const raw = await env.CONFIG.get(mac);
  if (!raw) return { providers: [] };
  try { return JSON.parse(raw); } catch { return { providers: [] }; }
}

async function writeConfig(env, mac, cfg) {
  await env.CONFIG.put(mac, JSON.stringify(cfg));
}

// ---- Login lockout helpers -------------------------------------------------

/** Parse a `lk:<mac>` record; a corrupt/missing record is treated as no lock. */
function parseLock(raw) {
  if (!raw) return { fails: 0, locked: false, until: 0 };
  try {
    const v = JSON.parse(raw);
    return { fails: v.fails || 0, locked: !!v.locked, until: v.until || 0 };
  } catch {
    return { fails: 0, locked: false, until: 0 };
  }
}

const LOCKOUT_BASE_MS = 60_000;        // 60 s on the 5th failed attempt
const LOCKOUT_CAP_MS = 60 * 60 * 1000; // capped at 1 hour
const LOCKOUT_THRESHOLD = 5;

/** Exponential backoff: 60 s on the 5th fail, doubling each extra fail, cap 1 h. */
function lockoutBackoffMs(fails) {
  if (fails < LOCKOUT_THRESHOLD) return 0;
  const extra = fails - LOCKOUT_THRESHOLD; // 0,1,2,…
  return Math.min(LOCKOUT_BASE_MS * 2 ** extra, LOCKOUT_CAP_MS);
}

// ---- Per-MAC password hashing ---------------------------------------------

async function sha256Hex(input) {
  const buf = new TextEncoder().encode(input);
  const hash = await crypto.subtle.digest("SHA-256", buf);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function randomSalt() {
  const arr = new Uint8Array(16);
  crypto.getRandomValues(arr);
  return Array.from(arr).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// PBKDF2 parameters. The stored hash is self-describing —
// `pbkdf2$<iterations>$<hex>` — so verification reads the iteration count back
// out of the record and future deployments can bump PBKDF2_ITERS without
// breaking older hashes. 100k SHA-256 iterations is the OWASP floor.
const PBKDF2_ITERS = 100_000;
const PBKDF2_KEYLEN_BITS = 256;

function bufToHex(buf) {
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Derive a PBKDF2-SHA256 hex digest of `salt:plaintext`. */
async function pbkdf2Hex(salt, plaintext, iterations = PBKDF2_ITERS) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(salt + ":" + plaintext),
    { name: "PBKDF2" },
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: new TextEncoder().encode(salt), iterations, hash: "SHA-256" },
    key,
    PBKDF2_KEYLEN_BITS,
  );
  return bufToHex(bits);
}

/** Format a PBKDF2 digest into the versioned, self-describing storage string. */
function pbkdf2Format(iterations, hex) {
  return `pbkdf2$${iterations}$${hex}`;
}

/** True if a stored passwordHash is in the new PBKDF2 format (vs legacy SHA-256). */
function isPbkdf2Hash(stored) {
  return typeof stored === "string" && stored.startsWith("pbkdf2$");
}

/**
 * Verify `supplied` against cfg.passwordHash. Detects the storage format:
 *   - `pbkdf2$<iters>$<hex>` → PBKDF2-SHA256 with the embedded iteration count.
 *   - bare 64-char hex       → legacy single-pass sha256Hex(salt:pw).
 * Returns { ok, legacy } so callers can trigger upgrade-on-login when a legacy
 * hash verified successfully.
 */
async function verifyPassword(cfg, supplied) {
  if (!cfg.passwordHash) return { ok: true, legacy: false }; // unprotected
  if (!supplied) return { ok: false, legacy: false };
  if (isPbkdf2Hash(cfg.passwordHash)) {
    const parts = cfg.passwordHash.split("$"); // ["pbkdf2", iters, hex]
    const iterations = parseInt(parts[1], 10) || PBKDF2_ITERS;
    const computed = await pbkdf2Hex(cfg.salt, supplied, iterations);
    return { ok: timingSafeEqual(computed, parts[2] || ""), legacy: false };
  }
  // Legacy scheme.
  const computed = await sha256Hex(cfg.salt + ":" + supplied);
  return { ok: timingSafeEqual(computed, cfg.passwordHash), legacy: true };
}

/** Back-compat boolean wrapper used by read-gated paths that don't upgrade. */
async function passwordMatches(cfg, supplied) {
  return (await verifyPassword(cfg, supplied)).ok;
}

async function setPassword(cfg, plaintext) {
  if (!plaintext) {
    delete cfg.passwordHash;
    delete cfg.salt;
    // Read protection is meaningless without a password — clear it so the
    // dashboard toggle doesn't render checked while the gate is inert.
    delete cfg.protectReads;
    return;
  }
  // Rotate to a fresh salt so the new PBKDF2 hash never reuses a legacy salt.
  cfg.salt = randomSalt();
  cfg.passwordHash = pbkdf2Format(PBKDF2_ITERS, await pbkdf2Hex(cfg.salt, plaintext));
}

// ---- Crash reporting -------------------------------------------------------
//
// Apps POST their stack traces here on the next launch after a crash. We keep
// them in CONFIG under `crash:<ms>:<rand>` so they share KV with the existing
// per-MAC entries. The mac and version land in the value; the prefix lets the
// admin dashboard list recent crashes cheaply.

const CRASH_PREFIX = "crash:";
const CRASH_TTL_S = 30 * 24 * 3600; // 30 days
const CRASH_MAX_BODY = 64 * 1024;   // 64 KB — Compose stack traces are big

const EVENT_PREFIX = "event:";
const EVENT_TTL_S = 7 * 24 * 3600;  // 7 days — events are noisier than crashes
const EVENT_MAX_BODY = 8 * 1024;    // 8 KB per event

function crashToken(env) {
  return env.CRASH_TOKEN || env.ADMIN_PASSWORD || null;
}

function newCrashKey() {
  const arr = new Uint8Array(8);
  crypto.getRandomValues(arr);
  const rand = Array.from(arr).map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${CRASH_PREFIX}${Date.now()}:${rand}`;
}

async function handleCrashPost(req, env) {
  const want = crashToken(env);
  const got = req.headers.get("x-crash-token") || "";
  if (!want) return json({ error: "crash reporting not configured" }, { status: 503 });
  if (!timingSafeEqual(got, want)) return json({ error: "bad token" }, { status: 401 });
  let body;
  try {
    const text = await req.text();
    if (text.length > CRASH_MAX_BODY) return json({ error: "too large" }, { status: 413 });
    body = JSON.parse(text);
  } catch {
    return json({ error: "invalid json" }, { status: 400 });
  }
  const entry = {
    mac: typeof body.mac === "string" ? body.mac.slice(0, 64) : null,
    version: typeof body.version === "string" ? body.version.slice(0, 32) : null,
    versionCode: Number.isFinite(body.versionCode) ? body.versionCode : null,
    device: typeof body.device === "string" ? body.device.slice(0, 128) : null,
    androidSdk: Number.isFinite(body.androidSdk) ? body.androidSdk : null,
    stack: typeof body.stack === "string" ? body.stack.slice(0, CRASH_MAX_BODY) : "",
    ts: Date.now(),
  };
  const key = newCrashKey();
  await env.CONFIG.put(key, JSON.stringify(entry), { expirationTtl: CRASH_TTL_S });
  return json({ ok: true, key });
}

function newEventKey() {
  const arr = new Uint8Array(6);
  crypto.getRandomValues(arr);
  const rand = Array.from(arr).map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${EVENT_PREFIX}${Date.now()}:${rand}`;
}

async function handleEventPost(req, env) {
  const want = crashToken(env);
  const got = req.headers.get("x-crash-token") || "";
  if (!want) return json({ error: "logging not configured" }, { status: 503 });
  if (!timingSafeEqual(got, want)) return json({ error: "bad token" }, { status: 401 });
  let body;
  try {
    const text = await req.text();
    if (text.length > EVENT_MAX_BODY) return json({ error: "too large" }, { status: 413 });
    body = JSON.parse(text);
  } catch {
    return json({ error: "invalid json" }, { status: 400 });
  }
  const entry = {
    level: typeof body.level === "string" ? body.level.slice(0, 8) : "info",
    tag: typeof body.tag === "string" ? body.tag.slice(0, 32) : "app",
    message: typeof body.message === "string" ? body.message.slice(0, 4000) : "",
    mac: typeof body.mac === "string" ? body.mac.slice(0, 64) : null,
    version: typeof body.version === "string" ? body.version.slice(0, 32) : null,
    versionCode: Number.isFinite(body.versionCode) ? body.versionCode : null,
    device: typeof body.device === "string" ? body.device.slice(0, 128) : null,
    ts: Date.now(),
  };
  const key = newEventKey();
  await env.CONFIG.put(key, JSON.stringify(entry), { expirationTtl: EVENT_TTL_S });
  return json({ ok: true, key });
}

async function listEvents(env, limit) {
  const out = [];
  let cursor;
  do {
    const r = await env.CONFIG.list({ prefix: EVENT_PREFIX, limit: 200, cursor });
    for (const k of r.keys) {
      out.push(k.name);
      if (out.length >= limit) break;
    }
    cursor = r.cursor;
    if (out.length >= limit || r.list_complete) break;
  } while (cursor);
  out.sort().reverse();
  const sliced = out.slice(0, limit);
  const items = await Promise.all(sliced.map(async (key) => {
    const raw = await env.CONFIG.get(key);
    try { return { key, ...JSON.parse(raw) }; } catch { return { key }; }
  }));
  return items;
}

function eventListPage({ items, tokenQs }) {
  const rows = items.map((it) => {
    const when = it.ts ? new Date(it.ts).toISOString().replace("T", " ").slice(0, 19) : "";
    const lvl = (it.level || "info").toLowerCase();
    return `
      <tr class="lvl-${escape(lvl)}">
        <td class="when">${escape(when)}</td>
        <td class="level">${escape(lvl.toUpperCase())}</td>
        <td class="tag">${escape(it.tag || "")}</td>
        <td class="msg">${escape(it.message || "")}</td>
        <td class="device">${escape(it.device || "")}</td>
        <td class="version">${escape(it.version || "")} (${it.versionCode ?? "?"})</td>
        <td class="mac">${escape(it.mac || "")}</td>
      </tr>
    `;
  }).join("");
  return `<!doctype html><html><head><meta charset="utf-8"/>
<title>Ultra TV — events</title>
<style>${baseStyles}
body { padding: 16px 28px; }
.tools { display: flex; gap: 10px; align-items: center; margin: 8px 0 18px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 8px 10px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
th { color: var(--muted); text-transform: uppercase; letter-spacing: 0.06em; font-size: 10px; }
td.when, td.tag, td.version, td.mac { font-family: ui-monospace, monospace; color: var(--muted); white-space: nowrap; }
td.level { font-family: ui-monospace, monospace; font-weight: 600; }
tr.lvl-debug td.level { color: var(--muted); }
tr.lvl-info td.level { color: var(--accent); }
tr.lvl-warn td.level { color: #ffb547; }
tr.lvl-error td.level { color: var(--danger); }
td.msg { color: var(--fg); white-space: pre-wrap; word-break: break-word; max-width: 800px; }
</style></head><body>
<h1>Events — ${items.length}</h1>
<div class="sub">7-day rolling window. Most recent first. <a href="/crashes?${tokenQs}">View crashes →</a></div>
<div class="tools">
  <a href="?${tokenQs}&limit=200"><button class="secondary">Show 200</button></a>
  <a href="?${tokenQs}&limit=500"><button class="secondary">Show 500</button></a>
</div>
<table>
  <thead><tr><th>When</th><th>Level</th><th>Tag</th><th>Message</th><th>Device</th><th>Version</th><th>MAC</th></tr></thead>
  <tbody>${rows || "<tr><td colspan='7' class='muted'>No events recorded.</td></tr>"}</tbody>
</table>
</body></html>`;
}

async function listCrashes(env, limit) {
  const out = [];
  let cursor;
  do {
    const r = await env.CONFIG.list({ prefix: CRASH_PREFIX, limit: 200, cursor });
    for (const k of r.keys) {
      out.push(k.name);
      if (out.length >= limit) break;
    }
    cursor = r.cursor;
    if (out.length >= limit || r.list_complete) break;
  } while (cursor);
  // Most-recent first (key embeds ms).
  out.sort().reverse();
  const sliced = out.slice(0, limit);
  const items = await Promise.all(sliced.map(async (key) => {
    const raw = await env.CONFIG.get(key);
    try { return { key, ...JSON.parse(raw) }; } catch { return { key }; }
  }));
  return items;
}

function crashListPage({ items, tokenQs }) {
  const rows = items.map((it) => {
    const when = it.ts ? new Date(it.ts).toISOString().replace("T", " ").slice(0, 19) : "";
    const head = (it.stack || "").split("\n").slice(0, 2).join(" | ");
    return `
      <details>
        <summary>
          <span class="when">${escape(when)}</span>
          <span class="version">${escape(it.version || "?")} (${it.versionCode ?? "?"})</span>
          <span class="device">${escape(it.device || "?")} · API ${it.androidSdk ?? "?"}</span>
          <span class="mac">${escape(it.mac || "?")}</span>
          <span class="preview">${escape(head)}</span>
        </summary>
        <pre>${escape(it.stack || "")}</pre>
      </details>
    `;
  }).join("");
  return `<!doctype html><html><head><meta charset="utf-8"/>
<title>Ultra TV — crash log</title>
<style>${baseStyles}
body { padding: 16px 28px; }
.tools { display: flex; gap: 10px; align-items: center; margin: 8px 0 18px; }
details { background: var(--bg2); border: 1px solid var(--border); border-radius: 10px; margin-bottom: 8px; padding: 10px 14px; }
summary { cursor: pointer; display: grid; grid-template-columns: 170px 110px 280px 170px 1fr; gap: 12px; align-items: center; font-size: 13px; }
summary .when { color: var(--accent); font-family: ui-monospace, monospace; }
summary .version { color: var(--muted); font-family: ui-monospace, monospace; }
summary .device { color: var(--muted); }
summary .mac { color: var(--muted); font-family: ui-monospace, monospace; }
summary .preview { color: var(--fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
pre { margin: 12px 0 0; padding: 12px; background: var(--bg); border-radius: 8px; overflow-x: auto; font-family: ui-monospace, monospace; font-size: 12px; line-height: 1.45; }
</style></head><body>
<h1>Crash log — ${items.length}</h1>
<div class="sub">30-day rolling window. Most recent first.</div>
<div class="tools">
  <a href="?${tokenQs}&limit=200"><button class="secondary">Show 200</button></a>
  <a href="?${tokenQs}&limit=500"><button class="secondary">Show 500</button></a>
</div>
${rows || "<div class='muted'>No crashes recorded.</div>"}
</body></html>`;
}

// ---- Worker entry ----------------------------------------------------------

export default {
  async fetch(req, env) {
    try {
      return await handle(req, env);
    } catch (e) {
      // A missing signing secret (ConfigError) is a deployment misconfig — fail
      // closed with a 500 rather than signing with a guessable constant.
      if (e instanceof ConfigError) {
        return json({ error: "server misconfigured" }, { status: 500 });
      }
      throw e;
    }
  },
};

async function handle(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });

    // ---- Crash + event ingest (token-gated, no cookie session) ----
    if (url.pathname === "/api/crash" && req.method === "POST") {
      return handleCrashPost(req, env);
    }
    if (url.pathname === "/api/event" && req.method === "POST") {
      return handleEventPost(req, env);
    }
    if (url.pathname === "/crashes" && req.method === "GET") {
      const want = crashToken(env);
      const got = url.searchParams.get("token") || "";
      if (!want || !timingSafeEqual(got, want)) return new Response("Forbidden", { status: 403 });
      const limit = Math.max(10, Math.min(500, parseInt(url.searchParams.get("limit") || "100", 10)));
      const items = await listCrashes(env, limit);
      return html(crashListPage({ items, tokenQs: `token=${encodeURIComponent(want)}` }));
    }
    if (url.pathname === "/logs" && req.method === "GET") {
      const want = crashToken(env);
      const got = url.searchParams.get("token") || "";
      if (!want || !timingSafeEqual(got, want)) return new Response("Forbidden", { status: 403 });
      const limit = Math.max(10, Math.min(500, parseInt(url.searchParams.get("limit") || "100", 10)));
      const items = await listEvents(env, limit);
      return html(eventListPage({ items, tokenQs: `token=${encodeURIComponent(want)}` }));
    }

    // ---- Public: app fetches its own config ----
    // The MAC itself is the bearer: it's hashed from ANDROID_ID so guessing is
    // unfeasible, and each user runs their own worker. The dashboard at /
    // still requires a per-MAC password for mutations; reads are anonymous BY
    // DEFAULT so the app doesn't have to make the user re-enter the password to
    // sync. Owners who want stronger protection can flip `protectReads` on from
    // the dashboard — when set, this read requires `?password=` verified
    // against the stored hash, returning 401 otherwise.
    const macGet = url.pathname.match(/^\/api\/config\/([^/]+)\/?$/);
    if (macGet && req.method === "GET") {
      const mac = normaliseMac(macGet[1]);
      if (!mac) return json({ error: "invalid mac" }, { status: 400 });
      const cfg = await readConfig(env, mac);
      if (cfg.protectReads) {
        const supplied = url.searchParams.get("password");
        if (!(await passwordMatches(cfg, supplied))) {
          return json({ error: "auth required" }, { status: 401 });
        }
      }
      return new Response(
        JSON.stringify({ providers: cfg.providers || [], known: !!cfg.providers?.length }),
        { headers: { "content-type": "application/json; charset=utf-8", ...corsHeaders } },
      );
    }

    // ---- Public auth pages ----
    if (url.pathname === "/login" && req.method === "GET") {
      return html(loginPage(url.searchParams.get("e")));
    }
    if (url.pathname === "/login" && req.method === "POST") {
      const f = await req.formData();
      const mac = normaliseMac((f.get("mac") || "").toString());
      const password = (f.get("password") || "").toString();
      if (!mac) return redirect("/login?e=mac");
      // Brute-force throttle: 5 failed attempts per MAC in any rolling 15-min
      // window start a lockout. The lockout grows exponentially — 60 s after
      // the 5th fail, doubling per additional fail (120 s, 240 s, …) capped at
      // 1 h — so sustained guessing gets exponentially more expensive. Counter
      // is stored under `lk:<mac>` in CONFIG KV with a TTL — the same KV the
      // rest of the worker already uses, no new binding needed.
      const lockKey = `lk:${mac}`;
      const lockRaw = await env.CONFIG.get(lockKey);
      const prevLock = parseLock(lockRaw);
      if (prevLock.locked && Date.now() < prevLock.until) {
        return redirect("/login?e=locked");
      }
      const cfg = await readConfig(env, mac);
      if (!cfg.passwordHash && !cfg.providers?.length) return redirect(`/signup?mac=${encodeURIComponent(mac)}`);
      if (!cfg.passwordHash) return redirect(`/signup?mac=${encodeURIComponent(mac)}&e=claim`);
      const verdict = await verifyPassword(cfg, password);
      if (!verdict.ok) {
        const fails = (prevLock.fails || 0) + 1;
        const locked = fails >= 5;
        const backoffMs = lockoutBackoffMs(fails); // 0 until the 5th fail
        const until = locked ? Date.now() + backoffMs : 0;
        // Keep the row alive at least as long as the lockout itself.
        const ttl = Math.max(15 * 60, Math.ceil(backoffMs / 1000) + 60);
        await env.CONFIG.put(
          lockKey,
          JSON.stringify({ fails, locked, until }),
          { expirationTtl: ttl },
        );
        return redirect(locked ? "/login?e=locked" : "/login?e=pw");
      }
      // Upgrade-on-login: a legacy SHA-256 record that verified gets transparently
      // re-hashed with PBKDF2 and persisted, since we hold the plaintext here and
      // this is the one flow that has it alongside a writable cfg.
      if (verdict.legacy) {
        await setPassword(cfg, password);
        await writeConfig(env, mac, cfg);
      }
      // Successful login clears the lockout counter.
      await env.CONFIG.delete(lockKey);
      const sess = await makeSession(env, mac);
      return new Response("", { status: 302, headers: { location: "/", "set-cookie": sessionCookie(sess) } });
    }

    if (url.pathname === "/signup" && req.method === "GET") {
      return html(signupPage({
        mac: url.searchParams.get("mac") || "",
        err: url.searchParams.get("e"),
      }));
    }
    if (url.pathname === "/signup" && req.method === "POST") {
      const f = await req.formData();
      const mac = normaliseMac((f.get("mac") || "").toString());
      const password = (f.get("password") || "").toString();
      const confirm = (f.get("confirm") || "").toString();
      if (!mac) return redirect("/signup?e=mac");
      if (!password || password.length < 8) return redirect(`/signup?mac=${encodeURIComponent(mac)}&e=short`);
      if (password !== confirm) return redirect(`/signup?mac=${encodeURIComponent(mac)}&e=mismatch`);
      const cfg = await readConfig(env, mac);
      if (cfg.passwordHash) return redirect(`/signup?mac=${encodeURIComponent(mac)}&e=taken`);
      await setPassword(cfg, password);
      cfg.providers = cfg.providers || [];
      await writeConfig(env, mac, cfg);
      const sess = await makeSession(env, mac);
      return new Response("", { status: 302, headers: { location: "/", "set-cookie": sessionCookie(sess) } });
    }

    if (url.pathname === "/logout") {
      return new Response("", { status: 302, headers: { location: "/login", "set-cookie": clearCookie() } });
    }

    // ---- Authenticated: cookie's MAC must match the path's MAC ----
    const me = await authedMac(req, env);
    if (!me) {
      if (url.pathname.startsWith("/api/")) return json({ error: "auth required" }, { status: 401 });
      return redirect("/login");
    }

    // Mac-scoped API guard: any /api/(provider|config|password)/:mac path must
    // belong to the cookie-owner. No cross-account peeking.
    const scoped = url.pathname.match(/^\/api\/(?:provider|config|password|settings)\/([^/]+)/);
    if (scoped) {
      const target = normaliseMac(scoped[1]);
      if (!target) return badRequest("Invalid MAC");
      if (target !== me) return json({ error: "forbidden" }, { status: 403 });
    }

    // Change own password.
    if (url.pathname === `/api/password/${encodeURIComponent(me)}` && req.method === "POST") {
      const form = await requireCsrf(req, env);
      if (!form) return json({ error: "csrf" }, { status: 403 });
      const password = (form.get("password") || "").toString();
      if (password && password.length < 8) return redirect("/?e=short");
      const cfg = await readConfig(env, me);
      await setPassword(cfg, password);
      await writeConfig(env, me, cfg);
      return redirect("/");
    }

    // Toggle read protection (protectReads). CSRF-protected, session-required,
    // and scoped to the cookie-owner like the other mutations.
    if (url.pathname === `/api/settings/${encodeURIComponent(me)}` && req.method === "POST") {
      const form = await requireCsrf(req, env);
      if (!form) return json({ error: "csrf" }, { status: 403 });
      const cfg = await readConfig(env, me);
      // Checkbox: present (any truthy value) = on, absent = off.
      const on = ["on", "true", "1"].includes((form.get("protectReads") || "").toString().toLowerCase());
      if (on && !cfg.passwordHash) {
        // Protecting reads without a password would lock the app out entirely.
        return redirect("/?e=nopw");
      }
      cfg.protectReads = on;
      await writeConfig(env, me, cfg);
      return redirect("/");
    }

    // Add provider.
    if (url.pathname === `/api/provider/${encodeURIComponent(me)}` && req.method === "POST") {
      const f = await requireCsrf(req, env);
      if (!f) return json({ error: "csrf" }, { status: 403 });
      const kind = (f.get("kind") || "").toString().toUpperCase();
      const provider = { kind, name: (f.get("name") || "").toString() };
      if (kind === "XTREAM") {
        provider.url = (f.get("url") || "").toString();
        provider.username = (f.get("username") || "").toString();
        provider.password = (f.get("password") || "").toString();
      } else if (kind === "M3U") {
        provider.url = (f.get("url") || "").toString();
      } else if (kind === "STALKER") {
        provider.url = (f.get("url") || "").toString();
        provider.mac = (f.get("mac") || "").toString();
      } else {
        return badRequest("Unknown kind: " + kind);
      }
      const cfg = await readConfig(env, me);
      cfg.providers = cfg.providers || [];
      cfg.providers.push(provider);
      await writeConfig(env, me, cfg);
      return redirect("/");
    }

    // Delete provider at index.
    const delProv = url.pathname.match(/^\/api\/provider\/([^/]+)\/(\d+)\/delete\/?$/);
    if (delProv && req.method === "POST") {
      const f = await requireCsrf(req, env);
      if (!f) return json({ error: "csrf" }, { status: 403 });
      const idx = parseInt(delProv[2], 10);
      const cfg = await readConfig(env, me);
      cfg.providers = (cfg.providers || []).filter((_, i) => i !== idx);
      await writeConfig(env, me, cfg);
      return redirect("/");
    }

    // Delete the whole account.
    if (url.pathname === `/api/config/${encodeURIComponent(me)}/delete` && req.method === "POST") {
      const f = await requireCsrf(req, env);
      if (!f) return json({ error: "csrf" }, { status: 403 });
      await env.CONFIG.delete(me);
      return new Response("", { status: 302, headers: { location: "/login", "set-cookie": clearCookie() } });
    }

    // Raw JSON save (advanced). This is a cookie-authenticated dashboard
    // endpoint (see README: "cookie (must own :mac)"), so it gets the same
    // CSRF protection as the other mutating routes. The token is read from the
    // `X-CSRF-Token` header (or a `csrf` field in the JSON body) since the
    // request carries a JSON body rather than form-encoded fields.
    if (url.pathname === `/api/config/${encodeURIComponent(me)}` && req.method === "POST") {
      const body = await req.text();
      let incoming;
      try { incoming = JSON.parse(body); } catch { return badRequest("Invalid JSON"); }
      const wantCsrf = await csrfFor(req, env);
      const gotCsrf = (req.headers.get("x-csrf-token") || (incoming && incoming.csrf) || "").toString();
      if (!wantCsrf || !timingSafeEqual(gotCsrf, wantCsrf)) return json({ error: "csrf" }, { status: 403 });
      // Preserve the password hash + salt; only providers come from the body.
      const cfg = await readConfig(env, me);
      cfg.providers = incoming.providers || [];
      await writeConfig(env, me, cfg);
      return json({ ok: true, mac: me });
    }

    if (url.pathname === "/" || url.pathname === "/dashboard") {
      const cfg = await readConfig(env, me);
      const csrf = await csrfFor(req, env);
      return html(dashboardPage({ mac: me, cfg, csrf, err: url.searchParams.get("e") }));
    }

    return new Response("Not found", { status: 404 });
}

function badRequest(msg) {
  return new Response(msg, { status: 400 });
}

// ---- HTML pages ------------------------------------------------------------

const baseStyles = `
:root { color-scheme: dark; --bg:#0b1020; --bg2:#131a30; --bg3:#1b2240; --fg:#e6e9f2; --muted:#8a93ac; --accent:#6ea8ff; --danger:#ff6b6b; --border:rgba(255,255,255,0.08); }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--fg); font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif; padding:24px; }
a { color: var(--accent); text-decoration: none; }
h1 { margin:0 0 4px 0; font-size:22px; }
.sub { color:var(--muted); margin-bottom:24px; }
.panel { background:var(--bg2); border:1px solid var(--border); border-radius:12px; padding:16px; }
label { display:block; color:var(--muted); font-size:12px; margin:10px 0 4px; }
input, select, textarea { width:100%; background:var(--bg); color:var(--fg); border:1px solid var(--border); border-radius:8px; padding:9px 11px; font:inherit; }
button { background:var(--accent); color:#0b1020; border:0; border-radius:8px; padding:9px 14px; font:inherit; font-weight:600; cursor:pointer; }
button.secondary { background:transparent; color:var(--fg); border:1px solid var(--border); font-weight:400; }
button.danger { background:transparent; color:var(--danger); border:1px solid var(--danger); }
.row { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin-top:10px; }
.providers { display:flex; flex-direction:column; gap:8px; margin-top:12px; }
.provider { display:flex; gap:10px; align-items:center; padding:10px 12px; background:var(--bg3); border-radius:10px; }
.provider .kind { background:var(--accent); color:#0b1020; padding:2px 8px; border-radius:6px; font-size:11px; font-weight:700; }
.provider .name { font-weight:600; }
.provider .muted { color:var(--muted); font-size:12px; }
.provider form { margin-left:auto; }
.tabs { display:flex; gap:6px; border-bottom:1px solid var(--border); margin:12px 0; }
.tabs button { background:transparent; color:var(--muted); padding:8px 14px; border-radius:0; font-weight:500; }
.tabs button.on { color:var(--accent); border-bottom:2px solid var(--accent); }
.toolbar { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; }
.auth-card { max-width: 400px; margin: 80px auto; padding: 24px; background: var(--bg2); border-radius: 14px; border: 1px solid var(--border); }
.notice { padding: 10px 12px; border-radius: 8px; background: var(--bg3); font-size: 13px; }
.notice.err { color: var(--danger); border: 1px solid var(--danger); }
`;

const loginErrors = {
  mac: "Adresse MAC invalide (12 chiffres hex).",
  pw: "Mot de passe incorrect.",
  locked: "Trop d'essais. Compte temporairement bloqué — réessaie plus tard (le délai augmente à chaque échec).",
};
const signupErrors = {
  mac: "Adresse MAC invalide (12 chiffres hex).",
  short: "Mot de passe trop court (8 caractères minimum).",
  mismatch: "Les deux mots de passe ne correspondent pas.",
  taken: "Ce MAC a déjà un compte — utilise la page de connexion.",
  claim: "Ce MAC existe sans mot de passe — finis la configuration ici pour le protéger.",
};

function loginPage(err) {
  const errMsg = err && loginErrors[err];
  return `<!doctype html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><title>Ultra TV — connexion</title><style>${baseStyles}</style></head><body>
<div class="auth-card">
  <h1>Ultra TV</h1>
  <div class="sub">Connexion à ta config</div>
  ${errMsg ? `<div class="notice err">${escape(errMsg)}</div>` : ""}
  <form method="post" action="/login" style="margin-top:12px;">
    <label>Adresse MAC de l'appareil</label>
    <input name="mac" placeholder="aa:bb:cc:dd:ee:ff" required pattern="^[0-9a-fA-F:\\-]{12,17}$" autofocus />
    <label>Mot de passe</label>
    <input name="password" type="password" autocomplete="current-password" required />
    <div class="row" style="margin-top:16px; justify-content:space-between;">
      <button type="submit">Se connecter</button>
      <a href="/signup" style="font-size:13px;">Créer un compte →</a>
    </div>
  </form>
</div>
</body></html>`;
}

function signupPage({ mac, err }) {
  const errMsg = err && signupErrors[err];
  return `<!doctype html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><title>Ultra TV — inscription</title><style>${baseStyles}</style></head><body>
<div class="auth-card">
  <h1>Ultra TV</h1>
  <div class="sub">Créer un compte pour ton appareil</div>
  ${errMsg ? `<div class="notice err">${escape(errMsg)}</div>` : ""}
  <form method="post" action="/signup" style="margin-top:12px;">
    <label>Adresse MAC de l'appareil <span class="muted">(visible dans l'app, écran Réglages)</span></label>
    <input name="mac" placeholder="aa:bb:cc:dd:ee:ff" required pattern="^[0-9a-fA-F:\\-]{12,17}$" value="${escape(mac || "")}" />
    <label>Mot de passe <span class="muted">(8 caractères minimum)</span></label>
    <input name="password" type="password" autocomplete="new-password" required minlength="8" />
    <label>Confirmer le mot de passe</label>
    <input name="confirm" type="password" required minlength="8" />
    <div class="row" style="margin-top:16px; justify-content:space-between;">
      <button type="submit">Créer le compte</button>
      <a href="/login" style="font-size:13px;">← J'ai déjà un compte</a>
    </div>
  </form>
</div>
</body></html>`;
}

const dashboardErrors = {
  short: "Mot de passe trop court (8 caractères minimum).",
  nopw: "Définis d'abord un mot de passe de compte avant de protéger les lectures.",
};

function dashboardPage({ mac, cfg, csrf, err }) {
  const providers = (cfg && cfg.providers) || [];
  const csrfInput = `<input type="hidden" name="csrf" value="${escape(csrf || "")}"/>`;
  const errMsg = err && dashboardErrors[err];
  const providerRows = providers.map((p, i) => `
    <div class="provider">
      <span class="kind">${escape(p.kind || "?")}</span>
      <div>
        <div class="name">${escape(p.name || "(sans nom)")}</div>
        <div class="muted">${escape(p.url || "")}${p.username ? " · " + escape(p.username) : ""}${p.mac ? " · MAC " + escape(p.mac) : ""}</div>
      </div>
      <form method="post" action="/api/provider/${encodeURIComponent(mac)}/${i}/delete" onsubmit="return confirm('Supprimer ce fournisseur ?')">
        ${csrfInput}
        <button type="submit" class="danger">Supprimer</button>
      </form>
    </div>
  `).join("");

  return `<!doctype html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><title>Ultra TV — ${escape(mac)}</title><style>${baseStyles}
.layout { max-width: 880px; margin: 0 auto; }
.topbar { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; gap:12px; flex-wrap:wrap; }
.macpill { font-family:ui-monospace,monospace; background:var(--bg3); padding:6px 10px; border-radius:8px; color:var(--accent); }
</style></head><body>
<div class="layout">
  <div class="topbar">
    <div>
      <h1 style="display:inline; margin-right:10px;">Ultra TV</h1>
      <span class="macpill">${escape(mac)}</span>
    </div>
    <a href="/logout"><button class="secondary">Se déconnecter</button></a>
  </div>

  ${errMsg ? `<div class="notice err" style="margin-bottom:14px;">${escape(errMsg)}</div>` : ""}

  <div class="panel" style="margin-bottom:14px;">
    <h2 style="margin:0 0 8px 0; font-size:16px;">Fournisseurs (${providers.length})</h2>
    <div class="providers">
      ${providers.length ? providerRows : `<div class="muted">Aucun fournisseur. Ajoute-en un ci-dessous, puis ouvre l'app et synchronise.</div>`}
    </div>

    <h2 style="margin:24px 0 8px 0; font-size:16px;">Ajouter un fournisseur</h2>
    <div class="tabs">
      <button type="button" class="on" onclick="showTab('xtream')">Xtream Codes</button>
      <button type="button" onclick="showTab('m3u')">M3U URL</button>
      <button type="button" onclick="showTab('stalker')">Stalker Portal</button>
    </div>

    <form method="post" action="/api/provider/${encodeURIComponent(mac)}" id="form-xtream">
      ${csrfInput}
      <input type="hidden" name="kind" value="XTREAM" />
      <label>Nom (optionnel)</label>
      <input name="name" placeholder="My Xtream" />
      <label>URL du serveur <span class="muted">(http://provider.com:8080)</span></label>
      <input name="url" required />
      <label>Utilisateur</label>
      <input name="username" required />
      <label>Mot de passe</label>
      <input name="password" type="password" required />
      <div class="row"><button type="submit">Ajouter le fournisseur Xtream</button></div>
    </form>

    <form method="post" action="/api/provider/${encodeURIComponent(mac)}" id="form-m3u" style="display:none;">
      ${csrfInput}
      <input type="hidden" name="kind" value="M3U" />
      <label>Nom (optionnel)</label>
      <input name="name" placeholder="My M3U" />
      <label>URL de la playlist <span class="muted">(.m3u / .m3u8)</span></label>
      <input name="url" required />
      <div class="row"><button type="submit">Ajouter la playlist M3U</button></div>
    </form>

    <form method="post" action="/api/provider/${encodeURIComponent(mac)}" id="form-stalker" style="display:none;">
      ${csrfInput}
      <input type="hidden" name="kind" value="STALKER" />
      <label>Nom (optionnel)</label>
      <input name="name" placeholder="MAG portal" />
      <label>URL du portail <span class="muted">(http://host:8080)</span></label>
      <input name="url" required />
      <label>MAC de l'appareil <span class="muted">(00:1A:79:XX:XX:XX)</span></label>
      <input name="mac" required pattern="^[0-9a-fA-F:]{17}$" />
      <div class="row"><button type="submit">Ajouter le portail Stalker</button></div>
    </form>
  </div>

  <div class="panel" style="margin-bottom:14px;">
    <h2 style="margin:0 0 8px 0; font-size:16px;">🔑 Mot de passe du compte</h2>
    <div class="muted" style="font-size:12px;">Le mot de passe est exigé par l'application Ultra TV (écran Réglages → Mot de passe de la config) pour récupérer cette configuration. Laisse vide pour le retirer (déconseillé).</div>
    <form method="post" action="/api/password/${encodeURIComponent(mac)}" style="display:flex; gap:8px; margin-top:8px;">
      ${csrfInput}
      <input name="password" type="password" placeholder="Nouveau mot de passe (vide pour effacer)" />
      <button type="submit">Mettre à jour</button>
    </form>
  </div>

  <div class="panel" style="margin-bottom:14px;">
    <h2 style="margin:0 0 8px 0; font-size:16px;">🛡️ Protection des lectures</h2>
    <div class="muted" style="font-size:12px;">Par défaut, <code>GET /api/config/:mac</code> renvoie les fournisseurs sans authentification (l'app n'a pas à redemander le mot de passe à chaque synchro). Active cette option pour exiger <code>?password=</code> sur les lectures aussi. Nécessite un mot de passe de compte défini.</div>
    <form method="post" action="/api/settings/${encodeURIComponent(mac)}" style="display:flex; gap:10px; align-items:center; margin-top:10px;">
      ${csrfInput}
      <label style="display:flex; gap:8px; align-items:center; margin:0; color:var(--fg);">
        <input type="checkbox" name="protectReads" value="on" style="width:auto;" ${cfg && cfg.protectReads ? "checked" : ""} ${cfg && !cfg.passwordHash ? "disabled" : ""} />
        Exiger le mot de passe pour lire la config
      </label>
      <button type="submit" class="secondary">Enregistrer</button>
    </form>
    ${cfg && !cfg.passwordHash ? `<div class="muted" style="font-size:12px; margin-top:6px;">Définis d'abord un mot de passe ci-dessus.</div>` : ""}
  </div>

  <div class="panel">
    <h2 style="margin:0 0 8px 0; font-size:16px;">Zone dangereuse</h2>
    <div class="muted" style="font-size:12px;">Supprime ton compte et toute sa config. L'application devra ré-importer une config (nouvelle inscription ou ajout manuel).</div>
    <form method="post" action="/api/config/${encodeURIComponent(mac)}/delete" onsubmit="return confirm('Supprimer définitivement ce compte et toute la config ?')" style="margin-top:8px;">
      ${csrfInput}
      <button class="danger" type="submit">Supprimer mon compte</button>
    </form>
  </div>
</div>

<script>
function showTab(id) {
  ['xtream','m3u','stalker'].forEach(k => document.getElementById('form-'+k).style.display = (k===id?'block':'none'));
  document.querySelectorAll('.tabs button').forEach((b,i) => b.classList.toggle('on', i === ['xtream','m3u','stalker'].indexOf(id)));
}
</script>
</body></html>`;
}

function escape(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
