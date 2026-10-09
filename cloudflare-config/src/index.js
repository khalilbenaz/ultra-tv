/**
 * Ultra TV — Worker de configuration à distance (v2, appairage par jeton d'appareil).
 *
 * Modèle d'authentification
 *   - Tableau de bord : compte (identifiant + mot de passe PBKDF2), cookie de
 *     session signé HMAC, jeton CSRF lié à la session, contrôle d'Origin.
 *   - Application TV  : appairage par code court (affiché sur la TV, saisi dans
 *     le tableau de bord) → jeton d'appareil aléatoire de 256 bits, stocké haché,
 *     révocable. La MAC n'est plus qu'une étiquette d'affichage.
 *   - Crashs / journaux : l'ingestion exige un jeton d'appareil ; la lecture
 *     exige OPS_TOKEN (secret serveur, jamais dans l'APK).
 *
 * Voir cloudflare-config/README.md pour le détail des routes et de la migration.
 */

import { JSQR_SOURCE } from "./jsqr-bundle.js";
import { Guard } from "./guard.js";
import { hashPassword, verifyPassword, randomToken, sha256Hex, timingSafeEqual, keysFromEnv, encryptJson, decryptJson } from "./crypto.js";
import {
  ConfigError, json, redirect, tooMany, nonce, readJson, readForm, readLimited, sameOrigin, clientIp,
  withSecurityHeaders, readCookie, sessionCookieHeader, clearCookieHeader, COOKIE, signSession, verifySession,
  csrfToken, parseBearer, parseOpsSecret, assertSessionSecret,
} from "./http.js";
import {
  guardStub, normalizeLogin, isMacLogin, getAccount, putAccount, loadProviders, saveProviders, deleteAccount,
  newDeviceToken, registerDevice, authDevice, revokeDevice, rotateDevice, parseProvider, publicProvider, syncProvider, parseDeviceProvider, parsePrefs,
  isVisibleTo, assignmentOf, isSupportedKind, parseAssign, dropDeviceFromAssignments, renameDevice,
  MAX_PROVIDERS, MAX_DEVICES, iptvLink, xtreamAccount, verifyProLicense, setDeviceInfo, parseStateBody, mergeState, loadState, saveState, deleteState,
} from "./store.js";
import { tmdbProxy } from "./tmdb.js";
import { subtitlesSearch, subtitlesDownload, osLogin } from "./subtitles.js";
import { sanitizeText } from "./sanitize.js";
import {
  TRAKT_STATE_TTL_S, pkcePair, authorizeUrl, exchangeCode, refreshTokens, traktUsername, revokeToken,
  parseScrobble, resolveTmdb, traktScrobbleBody, sendScrobble,
} from "./trakt.js";
import { LIB_TTL_S, LANGS, fetchTraktLibrary, enrichTitles, libraryForDevice } from "./traktlib.js";
import { fontResponse } from "./fonts.js";
import { loginPage, signupPage, pairPage, dashboardPage, eventsPage, crashesPage, adminPage } from "./pages.js";

export { Guard };

const PAIR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 32 symboles, sans I/O/0/1
const PAIR_TTL_S = 600;
const MIN_PASSWORD = 10;

const CRASH_TTL_S = 30 * 24 * 3600;
const EVENT_TTL_S = 7 * 24 * 3600;
const EVENT_MAX_BODY = 8 * 1024;
const CRASH_MAX_BODY = 48 * 1024;

// ---- limitation de débit ------------------------------------------------------

/** Renvoie une réponse 429 si la limite est dépassée, sinon null. */
async function limited(env, key, limit, windowSec, asJson = false) {
  const r = await guardStub(env, key).hit(limit, windowSec);
  return r.ok ? null : tooMany(r.retryAfter, asJson);
}

async function lockedOut(env, key, asJson = false) {
  const s = await guardStub(env, key).lockState();
  return s.locked ? tooMany(s.retryAfter, asJson) : null;
}

const LOGIN_LOCK = { threshold: 5, baseSec: 60, capSec: 900 };   // par compte
const IP_LOCK = { threshold: 10, baseSec: 60, capSec: 3600 };    // par IP
const SECRET_LOCK = { threshold: 5, baseSec: 60, capSec: 3600 }; // OPS / ADMIN

// ---- sérialisation des mutations d'un compte ----------------------------------------

/**
 * Toute mutation d'un compte (fournisseurs, affectations, appareils, mot de passe) passe par ici : un verrou par compte dans
 * un Durable Object. `fn` reçoit le compte relu SOUS le verrou (jamais une copie lue avant l'attente), puis la version
 * (`cfgVersion`) n'est incrémentée que dans ce critique : aucune écriture perdue entre deux appareils et le tableau de bord.
 */
async function withAccountLock(env, login, fn) {
  const stub = guardStub(env, `mut:${login}`);
  let token = null;
  for (let i = 0; i < 60 && !token; i++) {
    const r = await stub.lockAcquire(10_000);
    if (r.ok) token = r.token; else await new Promise((res) => setTimeout(res, 40 + Math.random() * 80));
  }
  if (!token) return json({ error: "busy" }, 503, { "retry-after": "2" });
  try {
    const acct = await getAccount(env, login);
    if (!acct) return json({ error: "gone" }, 404);
    return await fn(acct);
  } finally {
    await stub.lockRelease(token);
  }
}

/**
 * Abonnement d'un fournisseur, gardé 10 min en mémoire de l'isolat (au mieux : aucune écriture KV). Clé liée au
 * compte, au fournisseur et à ses identifiants : un changement d'identifiants relit tout de suite.
 */
const accountCache = new Map();
async function cachedAccount(env, login, p, now = Date.now()) {
  const key = `${login}|${p.id}|${p.url}|${p.username || ""}|${p.password || ""}`;
  const hit = accountCache.get(key);
  if (hit && now - hit.at < 600_000) return hit.value;
  // Relais hors Cloudflare (facultatif) pour les fournisseurs qui bloquent les serveurs Cloudflare.
  const relay = env.ACCOUNT_RELAY_URL && env.ACCOUNT_RELAY_KEY ? { url: env.ACCOUNT_RELAY_URL, key: env.ACCOUNT_RELAY_KEY } : null;
  const value = await xtreamAccount(p, fetch, 8000, relay);
  if (accountCache.size > 500) accountCache.clear();
  // Les erreurs passagères ne sont gardées qu'1 min.
  accountCache.set(key, { at: value.error === "unreachable" ? now - 540_000 : now, value });
  return value;
}

// ---- point d'entrée -----------------------------------------------------------

export default {
  async fetch(req, env, ctx) {
    try {
      return withSecurityHeaders(await route(req, env, ctx));
    } catch (err) {
      console.error("erreur:", err instanceof ConfigError ? `configuration: ${err.message}` : err?.message);
      return withSecurityHeaders(json({ error: err instanceof ConfigError ? "server_misconfigured" : "internal" }, 500));
    }
  },
};

async function route(req, env, ctx) {
  const url = new URL(req.url);
  const { pathname: path } = url;
  const m = req.method;

  // Aucune origine tierce n'a besoin d'appeler ce Worker depuis un navigateur : pas de CORS.
  if (m === "OPTIONS") return new Response(null, { status: 204 });

  // ---- API application TV (jeton d'appareil / appairage) ----
  if (path === "/api/pair/start" && m === "POST") return pairStart(req, env);
  if (path === "/api/pair/poll" && m === "POST") return pairPoll(req, env);
  if (path === "/api/config" && m === "GET") return deviceConfig(req, env);
  if (path === "/api/device/rotate" && m === "POST") return deviceRotate(req, env);
  if (path === "/api/device/providers" && (m === "POST" || m === "PUT")) return devicePutProvider(req, env);
  const delProv = path.match(/^\/api\/device\/providers\/([0-9a-f]{8})$/);
  if (delProv && m === "DELETE") return deviceDeleteProvider(req, env, delProv[1]);
  const prefsProv = path.match(/^\/api\/device\/providers\/([0-9a-f]{8})\/prefs$/);
  if (prefsProv && m === "PUT") return devicePutPrefs(req, env, prefsProv[1]);
  const stateProv = path.match(/^\/api\/device\/providers\/([0-9a-f]{8})\/state$/);
  if (stateProv && m === "POST") return deviceSyncState(req, env, stateProv[1]);
  if (path === "/api/device/license" && m === "POST") return deviceLicense(req, env);
  if (path === "/api/device/trakt/scrobble" && m === "POST") return deviceTraktScrobble(req, env);
  if (path === "/api/device/trakt/library" && m === "GET") return deviceTraktLibrary(req, env, url, ctx);
  if ((path === "/api/device/self" && m === "POST") || (path === "/api/device" && (m === "PATCH" || m === "POST"))) return deviceRename(req, env);
  if ((path === "/api/subtitles/search" || path === "/api/subtitles/download") && m === "GET") return deviceSubtitles(req, env, path.endsWith("/search"), url);
  if (path.startsWith("/api/tmdb/") && m === "GET") return deviceTmdb(req, env, path.slice("/api/tmdb/".length), url);
  if (path === "/api/event" && m === "POST") return ingest(req, env, "event");
  if (path === "/api/crash" && m === "POST") return ingest(req, env, "crash");
  // Ancienne lecture anonyme par MAC : supprimée (la MAC n'est pas un secret).
  if (/^\/api\/config\//.test(path)) {
    return json({ error: "pairing_required", message: "La lecture par adresse MAC est supprimée. Mets à jour l'application et appaire-la." }, 410);
  }

  // ---- exploitation ----
  if ((path === "/crashes" || path === "/logs") && m === "GET") return opsPage(req, env, path === "/crashes" ? "crash" : "event");
  if (path === "/api/admin/migrate" && m === "POST") return adminMigrate(req, env, url);

  // ---- polices auto-hébergées (publiques, immuables) ----
  const font = path.match(/^\/assets\/([a-z]+\.woff2)$/);
  if (font && m === "GET") return fontResponse(font[1]) || new Response("Not found", { status: 404 });

  // ---- lecteur de QR vendorisé (jsQR, Apache-2.0) : chargé seulement à l'ouverture du scanner ----
  if (path === "/assets/jsqr.js" && m === "GET") return new Response(JSQR_SOURCE, { headers: { "content-type": "text/javascript; charset=utf-8", "cache-control": "public, max-age=31536000, immutable", "x-content-type-options": "nosniff" } });

  // ---- pages publiques ----
  if (path === "/login" && m === "GET") { const n = nonce(); return loginPage(n, url.searchParams.get("e"), safeNext(url.searchParams.get("next"))); }
  if (path === "/login" && m === "POST") return doLogin(req, env);
  if (path === "/signup" && m === "GET") { const n = nonce(); return signupPage(n, url.searchParams.get("e"), safeNext(url.searchParams.get("next"))); }
  if (path === "/signup" && m === "POST") return doSignup(req, env);

  // Retour de Trakt : AVANT la session. Le cookie de session est SameSite=Strict, donc absent d'une navigation venue
  // de trakt.tv ; le retour est authentifié par l'état à usage unique ET le cookie SameSite=Lax posé au départ.
  if (path === "/trakt/callback" && m === "GET") return traktFinish(req, env, url);

  // ---- tableau de bord (session) ----
  const sess = await currentSession(req, env);
  const dashboardRoute = (path === "/" || path === "/dashboard") && m === "GET";
  const adminRoute = (path === "/admin" || path === "/admin/comptes.csv") && m === "GET";
  const pairRoute = path === "/pair" && m === "GET";
  const mutating = m === "POST" && ["/pair", "/providers", "/password", "/account/delete", "/logout", "/subtitles/link", "/subtitles/unlink", "/trakt/connect", "/trakt/disconnect"].includes(path)
    || (m === "POST" && /^\/(devices\/[0-9a-f]+\/(revoke|rename)|providers\/[0-9a-f]+\/(delete|assign|link|account))$/.test(path));
  if (!dashboardRoute && !pairRoute && !adminRoute && !mutating) return new Response("Not found", { status: 404 });
  if (!sess) {
    const c = pairRoute ? normalizeCode(url.searchParams.get("code")) : null;
    return redirect(c ? `/login?next=${encodeURIComponent(`/pair?code=${c}`)}` : "/login");
  }

  // Lien/QR affiché par la TV : on confirme avec un POST explicite, le GET n'appaire jamais.
  if (pairRoute) {
    const raw = url.searchParams.get("code");
    const c = normalizeCode(raw);
    return pairPage(nonce(), { acct: sess.acct, csrf: sess.csrf, code: c, invalid: Boolean(raw) && !c });
  }

  if (adminRoute) {
    // Réservé aux identifiants listés dans ADMIN_LOGINS ; tout autre compte reçoit un 404 (l'existence n'est pas révélée).
    if (!isAdmin(env, sess.acct)) return new Response("Not found", { status: 404 });
    const rows = await adminAccounts(env);
    if (path.endsWith(".csv")) {
      return new Response(adminCsv(rows), { headers: {
        "content-type": "text/csv; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff",
        "content-disposition": `attachment; filename="ultratv-comptes-${new Date().toISOString().slice(0, 10)}.csv"`,
      } });
    }
    return adminPage(nonce(), { rows, me: sess.acct.login });
  }

  if (dashboardRoute) {
    const n = nonce();
    const providers = await loadProviders(env, sess.acct);
    return dashboardPage(n, { acct: sess.acct, providers, csrf: sess.csrf, err: url.searchParams.get("e"), ok: url.searchParams.get("m"), admin: isAdmin(env, sess.acct) });
  }

  // Mutations : Origin + jeton CSRF + débit par compte.
  if (!sameOrigin(req)) return new Response("Forbidden", { status: 403 });
  const parsed = await readForm(req);
  if (parsed.error) return parsed.error;
  const form = parsed.form;
  if (!timingSafeEqual(form.get("csrf") || "", sess.csrf)) return new Response("Forbidden (csrf)", { status: 403 });
  // Abonnements : une requête par fournisseur Xtream à chaque ouverture du tableau de bord (jusqu'à 20) — limite
  // PROPRE, sinon quelques rechargements épuisaient celle des actions (appairage, partage, suppression).
  const acc = path.match(/^\/providers\/([0-9a-f]+)\/account$/);
  if (acc) {
    const rla = await limited(env, `acctinfo:${sess.acct.login}`, 600, 3600);
    if (rla) return rla;
    const p = (await loadProviders(env, sess.acct)).find((x) => x.id === acc[1]);
    if (!p) return new Response("Not found", { status: 404 });
    return new Response(JSON.stringify(await cachedAccount(env, sess.acct.login, p)), { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" } });
  }

  const rl = await limited(env, `dash:${sess.acct.login}`, 120, 3600);
  if (rl) return rl;

  // Lien IPTV complet, à la demande (jamais dans la page elle-même) : réponse non mise en cache.
  const lnk = path.match(/^\/providers\/([0-9a-f]+)\/link$/);
  if (lnk) {
    const p = (await loadProviders(env, sess.acct)).find((x) => x.id === lnk[1]);
    if (!p) return new Response("Not found", { status: 404 });
    // Source du revendeur : ses identifiants ne sont pas ceux du client, ils ne sont jamais révélés.
    if (p.managed === "reseller") return new Response(JSON.stringify({ error: "managed" }), { status: 403, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
    return new Response(JSON.stringify({ link: iptvLink(p) }), { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" } });
  }

  return withAccountLock(env, sess.acct.login, async (acct) => {
  if (path === "/logout") {
    acct.sessEpoch = (acct.sessEpoch || 0) + 1; // invalide aussi le cookie s'il a fuité
    await putAccount(env, acct);
    return redirect("/login", { "set-cookie": clearCookieHeader() });
  }
  if (path === "/pair") return confirmPairing(env, acct, form);
  if (path === "/providers") return addProvider(env, acct, form);
  if (path === "/password") return changePassword(env, acct, form, req);
  if (path === "/subtitles/link") return linkOpenSubtitles(env, acct, form);
  if (path === "/trakt/connect") return traktStart(env, acct, url);
  if (path === "/trakt/disconnect") {
    if (acct.traktEnc && env.TRAKT_CLIENT_ID) {
      const t = await decryptJson(keysFromEnv(env), acct.traktEnc, acct.login).catch(() => null);
      if (t) await revokeToken(env.TRAKT_CLIENT_ID, t.access);
    }
    delete acct.traktEnc;
    delete acct.trakt;
    await putAccount(env, acct);
    await forgetTraktLibrary(env, acct.login, true);
    return redirect("/?m=trakt_off#compte");
  }
  if (path === "/subtitles/unlink") {
    delete acct.osEnc;
    delete acct.os;
    await putAccount(env, acct);
    await env.CONFIG.delete(osTokenKey(acct.login));
    return redirect("/?m=os_off#compte");
  }
  if (path === "/account/delete") return removeAccount(env, acct, form);
  const rev = path.match(/^\/devices\/([0-9a-f]+)\/revoke$/);
  if (rev) {
    if (await revokeDevice(env, acct, rev[1])) {
      // Un appareil révoqué sort de toutes les affectations.
      await saveProviders(env, acct, dropDeviceFromAssignments(await loadProviders(env, acct), rev[1]));
      await putAccount(env, acct);
    }
    return redirect("/?m=revoked");
  }
  const ren = path.match(/^\/devices\/([0-9a-f]+)\/rename$/);
  if (ren) {
    await renameDevice(env, acct, ren[1], form.get("name") || "");
    return redirect("/?m=renamed");
  }
  const asg = path.match(/^\/providers\/([0-9a-f]+)\/assign$/);
  if (asg) {
    const all = form.get("all") === "1";
    const r = parseAssign(all ? "all" : form.getAll("d"), acct);
    if (r.error) return redirect("/?e=assign");
    const providers = await loadProviders(env, acct);
    if (!providers.some((p) => p.id === asg[1])) return redirect("/");
    await saveProviders(env, acct, providers.map((p) => (p.id === asg[1] ? { ...p, assign: r.assign, updatedAt: Date.now() } : p)));
    await putAccount(env, acct);
    return redirect("/?m=assigned");
  }
  const del = path.match(/^\/providers\/([0-9a-f]+)\/delete$/);
  if (!del) return new Response("Not found", { status: 404 });
  const providers = await loadProviders(env, acct);
  await saveProviders(env, acct, providers.filter((p) => p.id !== del[1]));
  await deleteState(env, acct.login, del[1]);
  await putAccount(env, acct);
  return redirect("/");
  });
}

// Retour après connexion : uniquement /pair?code=XXXXXXXX (chemin relatif strict, jamais d'open redirect).
const NEXT_RE = /^\/pair\?code=[A-Z2-9]{8}$/;
const safeNext = (v) => (typeof v === "string" && NEXT_RE.test(v) ? v : "/");
const nextQuery = (to) => (to === "/" ? "" : `&next=${encodeURIComponent(to)}`);

// ---- sessions -------------------------------------------------------------------

async function currentSession(req, env) {
  assertSessionSecret(env.SESSION_SECRET); // échec fermé, même sans cookie
  const s = await verifySession(env.SESSION_SECRET, readCookie(req, COOKIE));
  if (!s) return null;
  const acct = await getAccount(env, s.login);
  if (!acct || (acct.sessEpoch || 0) !== s.epoch) return null;
  return { acct, csrf: await csrfToken(env.SESSION_SECRET, s.sid) };
}

async function startSession(env, acct, to = "/") {
  const value = await signSession(env.SESSION_SECRET, { login: acct.login, epoch: acct.sessEpoch || 0 });
  return redirect(to, { "set-cookie": sessionCookieHeader(value) });
}

// ---- connexion / inscription -----------------------------------------------------

async function doLogin(req, env) {
  if (!sameOrigin(req)) return new Response("Forbidden", { status: 403 });
  const ip = clientIp(req);
  const rl = await limited(env, `login:ip:${ip}`, 20, 900);
  if (rl) return rl;
  const locked = await lockedOut(env, `lock:ip:${ip}`);
  if (locked) return locked;
  const parsed = await readForm(req);
  if (parsed.error) return parsed.error;
  const to = safeNext(parsed.form.get("next"));
  const rawLogin = (parsed.form.get("login") || "").slice(0, 128);
  const password = (parsed.form.get("password") || "").slice(0, 256);
  const login = normalizeLogin(rawLogin) || `invalid:${await sha256Hex(rawLogin)}`;

  const lockKey = `lock:acct:${login}`;
  const acctLocked = await lockedOut(env, lockKey);
  if (acctLocked) return acctLocked;

  const acct = login.startsWith("invalid:") ? null : await getAccount(env, login);
  const verdict = await verifyPassword(acct, password); // coût identique si le compte n'existe pas
  if (!acct || !verdict.ok) {
    const [a, b] = await Promise.all([
      guardStub(env, lockKey).lockFail(LOGIN_LOCK),
      guardStub(env, `lock:ip:${ip}`).lockFail(IP_LOCK),
    ]);
    if (a.locked || b.locked) return tooMany(Math.max(a.retryAfter, b.retryAfter));
    return redirect(`/login?e=pw${nextQuery(to)}`);
  }
  await guardStub(env, lockKey).lockClear();
  if (verdict.needsRehash) {
    acct.passwordHash = await hashPassword(password);
    delete acct.salt;
    await putAccount(env, acct);
  }
  // Dernière connexion et pays (code Cloudflare, jamais l'adresse IP) : vue d'administration.
  const country = countryOf(req);
  if (Date.now() - (acct.lastLoginAt || 0) > 3600_000 || (country && acct.country !== country)) {
    await withAccountLock(env, acct.login, async (a) => {
      a.lastLoginAt = Date.now();
      if (country) a.country = country;
      await putAccount(env, a);
    });
  }
  return startSession(env, acct, to);
}

/** Pays de la requête (code ISO à 2 lettres fourni par Cloudflare), ou null. L'adresse IP n'est jamais gardée. */
function countryOf(req) {
  const c = req.cf && req.cf.country;
  return typeof c === "string" && /^[A-Z]{2}$/.test(c) ? c : null;
}

async function doSignup(req, env) {
  if (!sameOrigin(req)) return new Response("Forbidden", { status: 403 });
  const rl = await limited(env, `signup:ip:${clientIp(req)}`, 5, 3600);
  if (rl) return rl;
  const parsed = await readForm(req);
  if (parsed.error) return parsed.error;
  const f = parsed.form;
  const to = safeNext(f.get("next"));
  const login = normalizeLogin(f.get("login"));
  const password = (f.get("password") || "").slice(0, 256);
  if (!login) return redirect(`/signup?e=login${nextQuery(to)}`);
  if (password.length < MIN_PASSWORD) return redirect(`/signup?e=short${nextQuery(to)}`);
  if (password !== (f.get("confirm") || "")) return redirect(`/signup?e=mismatch${nextQuery(to)}`);
  // Un compte hérité (clé = MAC en clair) ne peut pas être « squatté » avant sa migration.
  if (isMacLogin(login) && (await env.CONFIG.get(login)) !== null) return redirect(`/signup?e=taken${nextQuery(to)}`);
  if (!(await guardStub(env, `acct:${login}`).claim())) return redirect(`/signup?e=taken${nextQuery(to)}`);
  try {
    const acct = { login, passwordHash: await hashPassword(password), devices: [], sessEpoch: 0, createdAt: Date.now(), country: countryOf(req) };
    await saveProviders(env, acct, []);
    await putAccount(env, acct);
    return await startSession(env, acct, to);
  } catch (err) {
    await guardStub(env, `acct:${login}`).release();
    throw err;
  }
}

// ---- appairage ------------------------------------------------------------------

function newPairCode() {
  return [...crypto.getRandomValues(new Uint8Array(8))].map((b) => PAIR_ALPHABET[b & 31]).join("");
}
const formatCode = (c) => `${c.slice(0, 4)}-${c.slice(4)}`;
const normalizeCode = (raw) => {
  const c = String(raw ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  return c.length === 8 && [...c].every((ch) => PAIR_ALPHABET.includes(ch)) ? c : null;
};

async function pairStart(req, env) {
  const rl = await limited(env, `pairstart:ip:${clientIp(req)}`, 10, 3600, true);
  if (rl) return rl;
  const body = await readJson(req, 2048);
  if (body.error) return body.error;
  const label = sanitizeText(typeof body.value.label === "string" ? body.value.label : "", 64);
  const pollSecret = randomToken(32);
  const secretHash = await sha256Hex(pollSecret);
  for (let i = 0; i < 5; i++) {
    const code = newPairCode();
    const r = await guardStub(env, `pair:${code}`).pairInit({ secretHash, label, ttlSec: PAIR_TTL_S });
    if (r.ok) return json({ code: formatCode(code), pollSecret, expiresIn: PAIR_TTL_S, interval: 3 });
  }
  return json({ error: "unavailable" }, 503);
}

async function pairPoll(req, env) {
  const rl = await limited(env, `pairpoll:ip:${clientIp(req)}`, 300, 600, true);
  if (rl) return rl;
  const body = await readJson(req, 2048);
  if (body.error) return body.error;
  const code = normalizeCode(body.value.code);
  const secret = typeof body.value.pollSecret === "string" ? body.value.pollSecret.slice(0, 128) : "";
  if (!code || !secret) return json({ error: "not_found" }, 404);
  const r = await guardStub(env, `pair:${code}`).pairPoll(await sha256Hex(secret));
  if (r.status === "pending") return json({ status: "pending" }, 202);
  if (r.status === "ready") return json({ token: r.token, deviceId: r.deviceId });
  return json({ error: "not_found" }, 404);
}

async function confirmPairing(env, acct, form) {
  const lock = await lockedOut(env, `lock:pair:${acct.login}`);
  if (lock) return lock;
  const rl = await limited(env, `pairconfirm:${acct.login}`, 30, 3600);
  if (rl) return rl;
  if ((acct.devices || []).length >= MAX_DEVICES) return redirect("/?e=limit");
  const code = normalizeCode(form.get("code"));
  const token = newDeviceToken();
  const deviceId = [...crypto.getRandomValues(new Uint8Array(6))].map((b) => b.toString(16).padStart(2, "0")).join("");
  const r = code ? await guardStub(env, `pair:${code}`).pairConfirm({ login: acct.login, deviceId, token }) : { ok: false };
  if (!r.ok) {
    const f = await guardStub(env, `lock:pair:${acct.login}`).lockFail(LOGIN_LOCK);
    return f.locked ? tooMany(f.retryAfter) : redirect("/?e=code");
  }
  const name = sanitizeText(form.get("name") || "", 40) || sanitizeText(r.label || "", 40) || "Appareil";
  await registerDevice(env, acct, { token, deviceId, name, label: r.label });
  return redirect("/?m=paired");
}

// ---- opérations du tableau de bord ---------------------------------------------

async function addProvider(env, acct, form) {
  const providers = await loadProviders(env, acct);
  if (providers.length >= MAX_PROVIDERS) return redirect("/?e=limit");
  const r = parseProvider(form, { deviceId: "", name: "dashboard" });
  if (r.error) return redirect(`/?e=${r.error}`);
  providers.push(r.provider);
  await saveProviders(env, acct, providers);
  await putAccount(env, acct);
  return redirect("/?m=added");
}

async function changePassword(env, acct, form, req) {
  const lock = await lockedOut(env, `lock:acct:${acct.login}`);
  if (lock) return lock;
  const verdict = await verifyPassword(acct, (form.get("current") || "").slice(0, 256));
  if (!verdict.ok) {
    const f = await guardStub(env, `lock:acct:${acct.login}`).lockFail(LOGIN_LOCK);
    return f.locked ? tooMany(f.retryAfter) : redirect("/?e=pw");
  }
  const next = (form.get("password") || "").slice(0, 256);
  if (next.length < MIN_PASSWORD) return redirect("/?e=short");
  acct.passwordHash = await hashPassword(next);
  delete acct.salt;
  acct.sessEpoch = (acct.sessEpoch || 0) + 1; // toutes les anciennes sessions meurent
  await putAccount(env, acct);
  return startSession(env, acct, "/?m=pw");
}

// ---- administration ---------------------------------------------------------------

function isAdmin(env, acct) {
  const list = String(env.ADMIN_LOGINS || "").split(",").map((x) => x.trim()).filter(Boolean);
  return Boolean(acct && list.includes(acct.login));
}

const LEGACY_LOGIN = /^([0-9a-f]{2}:){5}[0-9a-f]{2}$/;

/**
 * Vue d'administration : métadonnées des comptes, JAMAIS de secret (empreinte de mot de passe, sources chiffrées,
 * jetons Trakt / OpenSubtitles exclus). Lecture de tous les comptes (au plus 2000), 50 en parallèle.
 */
async function adminAccounts(env) {
  const names = [];
  let cursor;
  do {
    const page = await env.CONFIG.list({ prefix: "acct:", cursor });
    names.push(...page.keys.map((k) => k.name));
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor && names.length < 2000);
  const rows = [];
  for (let i = 0; i < names.length; i += 50) {
    const got = await Promise.all(names.slice(i, i + 50).map((n) => env.CONFIG.get(n)));
    for (const raw of got) {
      let a;
      try { a = JSON.parse(raw); } catch { continue; }
      if (!a || !a.login) continue;
      const devices = (a.devices || []).map((d) => ({
        name: d.name || "", model: d.label || "", edition: d.edition || "standard", lastSeen: d.lastSeen || 0,
        country: d.country || null, createdAt: d.createdAt || 0,
      }));
      rows.push({
        login: a.login,
        legacy: LEGACY_LOGIN.test(a.login),
        createdAt: a.createdAt || 0,
        lastLoginAt: a.lastLoginAt || 0,
        country: a.country || null,
        devices,
        lastSeen: Math.max(0, ...devices.map((d) => d.lastSeen)),
        sources: Boolean(a.providersEnc),
        trakt: a.traktEnc ? (a.trakt && a.trakt.user) || "oui" : "",
        opensubtitles: (a.os && a.os.user) || "",
      });
    }
  }
  rows.sort((x, y) => (x.legacy - y.legacy) || (y.createdAt - x.createdAt));
  return rows;
}

function adminCsv(rows) {
  const d = (t) => (t ? new Date(t).toISOString().slice(0, 16).replace("T", " ") : "");
  // Cellules neutralisées contre l'injection de formules (=, +, -, @ en tête) et échappées.
  const cell = (v) => { let s = String(v ?? ""); if (/^[=+\-@\t\r]/.test(s)) s = "'" + s; return `"${s.replace(/"/g, '""')}"`; };
  const head = ["compte", "type", "email", "cree_le", "derniere_connexion", "pays_compte", "nb_appareils", "appareils", "derniere_activite", "pays_appareils", "sources", "trakt", "opensubtitles"];
  const lines = rows.map((r) => [
    r.login, r.legacy ? "ancien (adresse MAC)" : "inscrit", r.login.includes("@") ? r.login : "", d(r.createdAt), d(r.lastLoginAt), r.country || "",
    r.devices.length, r.devices.map((x) => `${x.name} (${x.model}, ${x.edition}${x.lastSeen ? `, vu ${d(x.lastSeen)}` : ""})`).join(" | "),
    d(r.lastSeen), [...new Set(r.devices.map((x) => x.country).filter(Boolean))].join(" "), r.sources ? "oui" : "non", r.trakt, r.opensubtitles,
  ].map(cell).join(";"));
  return "\ufeff" + [head.join(";"), ...lines].join("\r\n") + "\r\n";
}

// ---- Trakt (facultatif) ---------------------------------------------------------
// Connexion depuis l'espace client : OAuth 2 + PKCE (client public, sans secret). Jetons chiffrés dans le compte,
// renouvelés par le Worker ; les appareils n'envoient que « ce que je lis » (POST /api/device/trakt/scrobble).

const traktRedirect = (url) => `${url.origin}/trakt/callback`;
const traktStateKey = (state) => `traktst:${state}`;

async function traktStart(env, acct, url) {
  if (!env.TRAKT_CLIENT_ID) return redirect("/?e=trakt_na#compte");
  const rl = await limited(env, `traktlink:${acct.login}`, 10, 3600);
  if (rl) return rl;
  const state = randomToken(24);
  const { verifier, challenge } = await pkcePair();
  // Le vérificateur ne quitte jamais le Worker ; l'état lie le retour de Trakt à CE compte (anti-CSRF du retour).
  await env.CONFIG.put(traktStateKey(state), JSON.stringify({ login: acct.login, verifier }), { expirationTtl: TRAKT_STATE_TTL_S });
  // Cookie lié au navigateur (Lax : renvoyé par la navigation de retour depuis trakt.tv) : un lien d'autorisation
  // fabriqué sur le compte d'un tiers et ouvert par la victime ne relie pas son Trakt au compte du tiers.
  return redirect(authorizeUrl(env.TRAKT_CLIENT_ID, traktRedirect(url), state, challenge), {
    "set-cookie": `${TRAKT_COOKIE}=${state}; Path=/trakt; HttpOnly; Secure; SameSite=Lax; Max-Age=${TRAKT_STATE_TTL_S}`,
  });
}

const TRAKT_COOKIE = "utv_trakt";

/**
 * Fin du parcours Trakt : page relais qui renvoie au tableau de bord par une navigation INTERNE au site (le cookie
 * de session Strict est alors renvoyé ; une redirection 302 dans la chaîne venue de trakt.tv ne le serait pas).
 */
function traktDone(query) {
  const to = `/?${query}#compte`;
  return new Response(`<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=${to}"><title>Ultra TV</title><p><a href="${to}">Retour au tableau de bord</a></p>`, {
    headers: {
      "content-type": "text/html; charset=utf-8", "cache-control": "no-store",
      "content-security-policy": "default-src 'none'; base-uri 'none'; frame-ancestors 'none'",
      "x-content-type-options": "nosniff", "referrer-policy": "no-referrer",
      "set-cookie": `${TRAKT_COOKIE}=; Path=/trakt; HttpOnly; Secure; SameSite=Lax; Max-Age=0`,
    },
  });
}

async function traktFinish(req, env, url) {
  const state = url.searchParams.get("state") || "";
  const code = url.searchParams.get("code") || "";
  if (!/^[A-Za-z0-9_-]{16,64}$/.test(state)) return traktDone("e=trakt");
  // Même navigateur que celui qui a lancé la connexion.
  if (!timingSafeEqual(readCookie(req, TRAKT_COOKIE) || "", state)) return traktDone("e=trakt");
  const raw = await env.CONFIG.get(traktStateKey(state));
  if (raw) await env.CONFIG.delete(traktStateKey(state));
  let st = null;
  try { st = raw ? JSON.parse(raw) : null; } catch { st = null; }
  // État expiré ou déjà utilisé, refus chez Trakt : rien n'est enregistré.
  if (!st || typeof st.login !== "string" || !code || code.length > 512 || !env.TRAKT_CLIENT_ID) return traktDone("e=trakt");
  const t = await exchangeCode(env.TRAKT_CLIENT_ID, traktRedirect(url), code, st.verifier);
  if (t.error) return traktDone("e=trakt");
  const user = await traktUsername(env.TRAKT_CLIENT_ID, t.access);
  return withAccountLock(env, st.login, async (acct) => {
    if (!acct) return traktDone("e=trakt");
    acct.traktEnc = await encryptJson(keysFromEnv(env), t, acct.login);
    acct.trakt = { user, at: Date.now() };
    await putAccount(env, acct);
    return traktDone("m=trakt");
  });
}

/** Jeton d'accès Trakt valide pour le compte (renouvelé sous verrou si besoin), ou null (non relié / révoqué). */
async function traktAccess(env, login, redirectUri, force = false) {
  const keys = keysFromEnv(env);
  const acct0 = await getAccount(env, login);
  if (!acct0 || !acct0.traktEnc) return null;
  const t0 = await decryptJson(keys, acct0.traktEnc, login).catch(() => null);
  if (!t0) return null;
  if (!force && t0.expiresAt - Date.now() > 86_400_000) return t0.access;
  return withAccountLock(env, login, async (acct) => {
    if (!acct.traktEnc) return null;
    const cur = await decryptJson(keys, acct.traktEnc, login).catch(() => null);
    if (!cur) return null;
    // Un autre appareil l'a déjà renouvelé pendant l'attente du verrou.
    if (cur.access !== t0.access && cur.expiresAt - Date.now() > 86_400_000) return cur.access;
    const nt = await refreshTokens(env.TRAKT_CLIENT_ID, redirectUri, cur.refresh);
    if (nt.error === "revoked") {
      // Accès retiré chez Trakt : on oublie les jetons, l'espace client propose de reconnecter.
      delete acct.traktEnc;
      acct.trakt = { ...(acct.trakt || {}), revoked: true };
      await putAccount(env, acct);
      return null;
    }
    if (nt.error) return force ? null : cur.access;
    acct.traktEnc = await encryptJson(keys, nt, login);
    await putAccount(env, acct);
    return nt.access;
  });
}

async function deviceTraktScrobble(req, env) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = await limited(env, `trakt:dev:${auth.device.id}`, 120, 600, true);
  if (rl) return rl;
  const body = await readJson(req, 4 * 1024);
  if (body.error) return body.error;
  const s = parseScrobble(body.value);
  if (!s) return json({ error: "invalid" }, 400);
  if (!env.TRAKT_CLIENT_ID || !auth.acct.traktEnc) return json({ linked: false });
  const tmdb = s.tmdb || (await resolveTmdb(env, s.kind === "movie" ? "movie" : "tv", s.title, s.year));
  const payload = traktScrobbleBody(s, tmdb);
  if (!payload) { console.log(JSON.stringify({ trakt: "scrobble", unmatched: s.kind })); return json({ linked: true, matched: false }); }
  const redirectUri = traktRedirect(new URL(req.url));
  let access = await traktAccess(env, auth.acct.login, redirectUri);
  if (!access) return json({ linked: false });
  let r = await sendScrobble(env.TRAKT_CLIENT_ID, access, s.action, payload);
  if (r === "unauthorized") {
    access = await traktAccess(env, auth.acct.login, redirectUri, true);
    if (!access) return json({ linked: false });
    r = await sendScrobble(env.TRAKT_CLIENT_ID, access, s.action, payload);
  }
  // Vu (Trakt marque « vu » au-delà de 80 %) : la bibliothèque en cache est périmée.
  if (r === "ok" && s.action === "stop" && s.progress >= 80) await forgetTraktLibrary(env, auth.acct.login);
  return json({ linked: true, matched: r !== "unmatched", ok: r === "ok" });
}

const traktLibKey = (login, lang) => `tlib:${login}:${lang}`;
const traktDictKey = (login, lang) => `tdict:${login}:${lang}`;

/** Oublie la bibliothèque en cache (et, au déliage, le dictionnaire des titres). */
async function forgetTraktLibrary(env, login, all = false) {
  await Promise.all([...LANGS].flatMap((l) => [env.CONFIG.delete(traktLibKey(login, l)), ...(all ? [env.CONFIG.delete(traktDictKey(login, l))] : [])]));
}

// Bibliothèque Trakt pour l'appareil, gardée chiffrée en KV. Au-delà de 15 min, la version gardée est servie TOUT DE
// SUITE et rafraîchie en arrière-plan (l'appareil n'attend pas les 10 appels Trakt) ; au-delà de 24 h, relue d'abord.
const TRAKT_LIB_KEEP_S = 24 * 3600;

async function deviceTraktLibrary(req, env, url, ctx) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = await limited(env, `traktlib:dev:${auth.device.id}`, 30, 600, true);
  if (rl) return rl;
  if (!env.TRAKT_CLIENT_ID || !auth.acct.traktEnc) return json({ linked: false });
  const login = auth.acct.login;
  const lang = LANGS.has(url.searchParams.get("lang")) ? url.searchParams.get("lang") : "en";
  const cached = await env.CONFIG.get(traktLibKey(login, lang));
  const v = cached ? await decryptJson(keysFromEnv(env), cached, login).catch(() => null) : null;
  // Ancien format (données seules) : traité comme périmé.
  const at = v && typeof v.at === "number" ? v.at : 0;
  const data = v && v.data ? v.data : null;
  if (data && Date.now() - at < LIB_TTL_S * 1000) return json(data);
  if (data && ctx && typeof ctx.waitUntil === "function") {
    ctx.waitUntil(buildTraktLibrary(env, login, lang, traktRedirect(url)).catch(() => {}));
    return json(data);
  }
  const fresh = await buildTraktLibrary(env, login, lang, traktRedirect(url));
  return json(fresh.body, fresh.status);
}

/** Relit Trakt, complète les titres, garde le résultat (chiffré) → { status, body }. */
async function buildTraktLibrary(env, login, lang, redirectUri) {
  const keys = keysFromEnv(env);
  let access = await traktAccess(env, login, redirectUri);
  if (!access) return { status: 200, body: { linked: false } };
  let lib;
  try {
    lib = await fetchTraktLibrary(env.TRAKT_CLIENT_ID, access);
  } catch (e) {
    if (!e.unauthorized) return { status: 502, body: { error: "upstream" } };
    access = await traktAccess(env, login, redirectUri, true);
    if (!access) return { status: 200, body: { linked: false } };
    try { lib = await fetchTraktLibrary(env.TRAKT_CLIENT_ID, access); } catch { return { status: 502, body: { error: "upstream" } }; }
  }
  let known = {};
  try { known = JSON.parse((await env.CONFIG.get(traktDictKey(login, lang))) || "{}") || {}; } catch { known = {}; }
  const dict = await enrichTitles(env, lib, known, lang);
  if (Object.keys(dict).length !== Object.keys(known).length) {
    await env.CONFIG.put(traktDictKey(login, lang), JSON.stringify(dict), { expirationTtl: 90 * 86400 });
  }
  const data = libraryForDevice(lib, dict, Date.now());
  await env.CONFIG.put(traktLibKey(login, lang), await encryptJson(keys, { at: Date.now(), data }, login), { expirationTtl: TRAKT_LIB_KEEP_S });
  return { status: 200, body: data };
}

// ---- compte OpenSubtitles du client (facultatif) ------------------------------
// Lié depuis l'espace client : identifiants chiffrés dans le compte (AES-GCM, lié au login), jeton de session gardé
// 20 h en KV (chiffré lui aussi). Les appareils n'en savent rien : le Worker télécharge au nom du client.

const OS_TOKEN_TTL_S = 20 * 3600;
const osTokenKey = (login) => `ostok:${login}`;

async function linkOpenSubtitles(env, acct, form) {
  const username = (form.get("os_user") || "").trim().slice(0, 100);
  const password = (form.get("os_pass") || "").slice(0, 200);
  if (!username || !password) return redirect("/?e=os_denied#compte");
  const rl = await limited(env, `oslink:${acct.login}`, 10, 3600);
  if (rl) return rl;
  const r = await osLogin(env, username, password);
  if (r.error) return redirect(`/?e=${r.error === "denied" ? "os_denied" : r.error === "busy" ? "os_busy" : "os_upstream"}#compte`);
  const keys = keysFromEnv(env);
  acct.osEnc = await encryptJson(keys, { username, password }, acct.login);
  acct.os = { user: username, level: r.level, allowed: r.allowed, vip: r.vip, at: Date.now() };
  await putAccount(env, acct);
  await env.CONFIG.put(osTokenKey(acct.login), await encryptJson(keys, { token: r.token, base: r.base }, acct.login), { expirationTtl: OS_TOKEN_TTL_S });
  return redirect("/?m=os#compte");
}

/** Session OpenSubtitles d'un compte pour subtitlesDownload, ou null si aucun compte n'est lié. */
function osSession(env, acct) {
  if (!acct.osEnc) return null;
  const keys = keysFromEnv(env);
  return {
    async get(force) {
      if (!force) {
        const blob = await env.CONFIG.get(osTokenKey(acct.login));
        if (blob) return decryptJson(keys, blob, acct.login);
      }
      // Reconnexion bornée : un mot de passe changé chez OpenSubtitles ne déclenche pas une connexion par téléchargement.
      if (await limited(env, `oslogin:${acct.login}`, 6, 3600, true)) return null;
      const { username, password } = await decryptJson(keys, acct.osEnc, acct.login);
      const r = await osLogin(env, username, password);
      if (r.error) return null;
      await env.CONFIG.put(osTokenKey(acct.login), await encryptJson(keys, { token: r.token, base: r.base }, acct.login), { expirationTtl: OS_TOKEN_TTL_S });
      return { token: r.token, base: r.base };
    },
  };
}

async function removeAccount(env, acct, form) {
  const lock = await lockedOut(env, `lock:acct:${acct.login}`);
  if (lock) return lock;
  const verdict = await verifyPassword(acct, (form.get("password") || "").slice(0, 256));
  if (!verdict.ok) {
    const f = await guardStub(env, `lock:acct:${acct.login}`).lockFail(LOGIN_LOCK);
    return f.locked ? tooMany(f.retryAfter) : redirect("/?e=pw");
  }
  await deleteAccount(env, acct);
  return redirect("/login", { "set-cookie": clearCookieHeader() });
}

// ---- API appareil ------------------------------------------------------------

async function deviceAuth(req, env) {
  const token = parseBearer(req);
  const lock = await lockedOut(env, `lock:ip:${clientIp(req)}`, true);
  if (lock) return { res: lock };
  const auth = await authDevice(env, token);
  if (!auth) {
    // Un jeton présenté mais faux compte comme un échec (anti-devinette) ; l'absence de jeton non.
    if (token) await guardStub(env, `lock:ip:${clientIp(req)}`).lockFail({ threshold: 20, baseSec: 60, capSec: 3600 });
    return { res: json({ error: "unauthorized" }, 401, { "www-authenticate": 'Bearer realm="ultratv"' }) };
  }
  return { auth };
}

const EDITIONS = new Set(["standard", "pro"]);

/** Édition de l'appli (en-tête X-Ultra-Edition) : enregistrée seulement quand elle change (écriture rare). */
async function noteEdition(req, env, auth) {
  // Édition, dernière activité (à l'heure près : une écriture au plus par heure) et pays de l'appareil.
  const ed = (req.headers.get("x-ultra-edition") || "").trim().toLowerCase();
  const patch = {};
  if (EDITIONS.has(ed) && auth.device.edition !== ed) patch.edition = ed;
  if (Date.now() - (auth.device.lastSeen || 0) > 3600_000) patch.lastSeen = Date.now();
  const country = countryOf(req);
  if (country && auth.device.country !== country) patch.country = country;
  if (!Object.keys(patch).length) return;
  await withAccountLock(env, auth.acct.login, (acct) => setDeviceInfo(env, acct, auth.device.id, patch));
}

/**
 * Édition Pro : l'appareil transmet son statut de licence SIGNÉ par le panneau revendeur. Vérifié (signature,
 * fraîcheur) puis gardé avec l'appareil pour le tableau de bord ; l'appareil devient « pro ».
 */
async function deviceLicense(req, env) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = await limited(env, `lic:dev:${auth.device.id}`, 20, 3600, true);
  if (rl) return rl;
  const body = await readJson(req, 6 * 1024);
  if (body.error) return body.error;
  const lic = await verifyProLicense(body.value.payload, body.value.sig);
  if (!lic) return json({ error: "invalid", field: "license" }, 400);
  return withAccountLock(env, auth.acct.login, async (acct) => {
    if (!(await setDeviceInfo(env, acct, auth.device.id, { edition: "pro", license: lic }))) return json({ error: "unauthorized" }, 401);
    return json({ ok: true });
  });
}

async function deviceConfig(req, env) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  await noteEdition(req, env, auth);
  const rl = (await limited(env, `cfg:ip:${clientIp(req)}`, 300, 600, true))
    || (await limited(env, `cfg:dev:${auth.device.id}`, 60, 600, true));
  if (rl) return rl;
  const version = auth.acct.cfgVersion || 0;
  const etag = `"v${version}"`;
  // Synchro incrémentale : rien n'a changé depuis la version connue de l'appareil.
  if (req.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers: { etag, "cache-control": "no-store" } });
  // Un appareil ne reçoit QUE les fournisseurs qui lui sont affectés (ou affectés à tous).
  const providers = (await loadProviders(env, auth.acct)).filter((p) => isSupportedKind(p) && isVisibleTo(p, auth.device.id));
  const devices = (auth.acct.devices || []).map((d) => ({ id: d.id, name: d.name, model: d.label || "", lastSeen: d.lastSeen || 0, isCurrent: d.id === auth.device.id }));
  return json(
    { version, self: auth.device.id, devices, providers: providers.map(syncProvider) },
    200, { etag, "cache-control": "no-store" },
  );
}

const DEV_BODY_MAX = 4 * 1024;

/** Ajoute ou met à jour (si `id` connu de CE compte) un fournisseur, depuis un appareil appairé. */
async function devicePutProvider(req, env) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = (await limited(env, `pput:ip:${clientIp(req)}`, 120, 600, true))
    || (await limited(env, `pput:dev:${auth.device.id}`, 30, 600, true));
  if (rl) return rl;
  const body = await readJson(req, DEV_BODY_MAX);
  if (body.error) return body.error;
  const origin = { deviceId: auth.device.id, name: auth.device.name };
  const r = parseDeviceProvider(body.value, origin);
  if (r.error) return json({ error: "invalid", field: r.error }, 400);
  return withAccountLock(env, auth.acct.login, async (acct) => {
    if (!acct.devices?.some((d) => d.id === auth.device.id)) return json({ error: "unauthorized" }, 401);
  const providers = await loadProviders(env, acct);
  const wanted = body.value.id;
  if (wanted !== undefined && !(typeof wanted === "string" && /^[0-9a-f]{8}$/.test(wanted))) return json({ error: "invalid", field: "id" }, 400);
  let assign = null;
  if (body.value.shareWith !== undefined) {
    const a = parseAssign(body.value.shareWith, acct);
    if (a.error) return json({ error: "invalid", field: "shareWith" }, 400);
    assign = a.assign;
  }
  let status = 201;
  let savedId;
  if (wanted) {
    const i = providers.findIndex((p) => p.id === wanted);
    // Inconnu OU non affecté à cet appareil : même réponse (on ne révèle pas l'existence d'une source d'un autre appareil).
    if (i < 0 || !isVisibleTo(providers[i], auth.device.id)) return json({ error: "not_found" }, 404);
    const old = providers[i];
    providers[i] = { ...r.provider, id: old.id, createdAt: old.createdAt, originDeviceId: old.originDeviceId, originName: old.originName, assign: assign ?? assignmentOf(old), ...(old.prefs ? { prefs: old.prefs } : {}) };
    status = 200; savedId = old.id;
  } else {
    if (providers.length >= MAX_PROVIDERS) return json({ error: "limit" }, 409);
    // Créée depuis un appareil : privée à cet appareil, sauf partage explicite.
    r.provider.assign = assign ?? [auth.device.id];
    if (Array.isArray(r.provider.assign) && !r.provider.assign.includes(auth.device.id)) r.provider.assign.push(auth.device.id);
    providers.push(r.provider); savedId = r.provider.id;
  }
  await saveProviders(env, acct, providers);
  await putAccount(env, acct);
  return json({ version: acct.cfgVersion, provider: syncProvider(providers.find((p) => p.id === savedId)) }, status);
  });
}

const PREFS_BODY_MAX = 192 * 1024;

/**
 * Publie les réglages d'affichage partagés d'une source (langues, catégories désactivées).
 * Le plus récent gagne : un envoi plus ancien que la version stockée renvoie 409 avec la version gagnante.
 */
async function devicePutPrefs(req, env, id) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = (await limited(env, `pprefs:ip:${clientIp(req)}`, 240, 600, true))
    || (await limited(env, `pprefs:dev:${auth.device.id}`, 60, 600, true));
  if (rl) return rl;
  const body = await readJson(req, PREFS_BODY_MAX);
  if (body.error) return body.error;
  const r = parsePrefs(body.value);
  if (r.error) return json({ error: "invalid", field: r.error }, 400);
  return withAccountLock(env, auth.acct.login, async (acct) => {
    if (!acct.devices?.some((d) => d.id === auth.device.id)) return json({ error: "unauthorized" }, 401);
    const providers = await loadProviders(env, acct);
    const i = providers.findIndex((p) => p.id === id);
    if (i < 0 || !isVisibleTo(providers[i], auth.device.id)) return json({ error: "not_found" }, 404);
    const cur = providers[i].prefs;
    if (cur && cur.updatedAt > r.prefs.updatedAt) return json({ error: "stale", prefs: cur }, 409);
    providers[i] = { ...providers[i], prefs: { ...r.prefs, by: auth.device.id } };
    await saveProviders(env, acct, providers);
    await putAccount(env, acct);
    return json({ version: acct.cfgVersion, prefs: providers[i].prefs });
  });
}

const STATE_BODY_MAX = 512 * 1024;

/**
 * Favoris, positions de reprise et derniers vus d'une source, partagés entre les appareils du compte.
 * Un seul aller-retour : l'appareil envoie ses changements, reçoit l'état fusionné (le plus récent gagne, par entrée).
 */
async function deviceSyncState(req, env, id) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = (await limited(env, `pstate:ip:${clientIp(req)}`, 240, 600, true))
    || (await limited(env, `pstate:dev:${auth.device.id}`, 120, 600, true));
  if (rl) return rl;
  const body = await readJson(req, STATE_BODY_MAX);
  if (body.error) return body.error;
  const incoming = parseStateBody(body.value);
  if (incoming.error) return json({ error: "invalid", field: incoming.error }, 400);
  return withAccountLock(env, auth.acct.login, async (acct) => {
    if (!acct.devices?.some((d) => d.id === auth.device.id)) return json({ error: "unauthorized" }, 401);
    const providers = await loadProviders(env, acct);
    const p = providers.find((x) => x.id === id);
    if (!p || !isVisibleTo(p, auth.device.id)) return json({ error: "not_found" }, 404);
    const merged = mergeState(await loadState(env, acct.login, id), incoming);
    if (incoming.fav.length || incoming.hist.length) await saveState(env, acct.login, id, merged);
    return json(merged);
  });
}

async function deviceDeleteProvider(req, env, id) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = (await limited(env, `pput:ip:${clientIp(req)}`, 120, 600, true))
    || (await limited(env, `pput:dev:${auth.device.id}`, 30, 600, true));
  if (rl) return rl;
  return withAccountLock(env, auth.acct.login, async (acct) => {
    if (!acct.devices?.some((d) => d.id === auth.device.id)) return json({ error: "unauthorized" }, 401);
  const providers = await loadProviders(env, acct);
  const p = providers.find((x) => x.id === id);
  if (!p || !isVisibleTo(p, auth.device.id)) return json({ error: "not_found" }, 404);
  // Par défaut l'appareil se RETIRE de l'affectation ; `?all=1` supprime le fournisseur du compte.
  const everywhere = new URL(req.url).searchParams.get("all") === "1";
  const a = assignmentOf(p);
  let next;
  if (everywhere) next = providers.filter((x) => x.id !== id);
  else {
    const rest = a === "all" ? (acct.devices || []).map((d) => d.id).filter((d) => d !== auth.device.id) : a.filter((d) => d !== auth.device.id);
    next = rest.length === 0 ? providers.filter((x) => x.id !== id) : providers.map((x) => (x.id === id ? { ...x, assign: rest } : x));
  }
  await saveProviders(env, acct, next);
  await putAccount(env, acct);
  if (!next.some((x) => x.id === id)) await deleteState(env, acct.login, id);
  return json({ version: acct.cfgVersion });
  });
}

/** Renomme CET appareil (étiquette affichée dans le tableau de bord et dans les listes de partage). */
async function deviceRename(req, env) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = await limited(env, `pput:dev:${auth.device.id}`, 30, 600, true);
  if (rl) return rl;
  const body = await readJson(req, 1024);
  if (body.error) return body.error;
  if (typeof body.value.name !== "string") return json({ error: "invalid", field: "name" }, 400);
  return withAccountLock(env, auth.acct.login, async (acct) => {
    if (!(await renameDevice(env, acct, auth.device.id, body.value.name))) return json({ error: "invalid", field: "name" }, 400);
    return json({ ok: true });
  });
}

// Métadonnées TMDB : réservé aux appareils appairés, débit limité par appareil et par IP.
async function deviceTmdb(req, env, rest, url) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = (await limited(env, `tmdb:ip:${clientIp(req)}`, 300, 600, true))
    || (await limited(env, `tmdb:dev:${auth.device.id}`, 120, 600, true));
  if (rl) return rl;
  return tmdbProxy(rest, url.searchParams, env);
}

// Sous-titres OpenSubtitles : mêmes garde-fous que TMDB (appareil appairé, débit par appareil et par IP), plus serrés
// car chaque téléchargement consomme le quota de la clé.
async function deviceSubtitles(req, env, isSearch, url) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = (await limited(env, `sub:ip:${clientIp(req)}`, 120, 600, true))
    || (await limited(env, `sub:dev:${auth.device.id}`, 40, 600, true));
  if (rl) return rl;
  return isSearch ? subtitlesSearch(url.searchParams, env) : subtitlesDownload(url.searchParams, env, { session: osSession(env, auth.acct) });
}

async function deviceRotate(req, env) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const rl = await limited(env, `rotate:dev:${auth.device.id}`, 5, 3600, true);
  if (rl) return rl;
  return withAccountLock(env, auth.acct.login, async (acct) => {
    const dev = acct.devices?.find((d) => d.id === auth.device.id);
    if (!dev) return json({ error: "unauthorized" }, 401);
    const token = await rotateDevice(env, acct, dev);
    return json({ token, deviceId: auth.device.id });
  });
}

// ---- ingestion ---------------------------------------------------------------

const LEVELS = ["debug", "info", "warn", "error"];
const int = (v) => (Number.isFinite(v) ? Math.trunc(v) : null);
const rand = (n) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");

async function ingest(req, env, kind) {
  const { auth, res } = await deviceAuth(req, env);
  if (res) return res;
  const isCrash = kind === "crash";
  const rl = (await limited(env, `ing:ip:${clientIp(req)}`, 300, 3600, true))
    || (await limited(env, `ing:${kind}:${auth.device.id}`, isCrash ? 20 : 60, 3600, true));
  if (rl) return rl;
  const body = await readJson(req, isCrash ? CRASH_MAX_BODY : EVENT_MAX_BODY);
  if (body.error) return body.error;
  const b = body.value;
  const base = {
    v: 2, ts: Date.now(), deviceId: auth.device.id,
    mac: sanitizeText(b.mac, 32) || null, // étiquette fournie par l'appareil, non fiable
    version: sanitizeText(b.version, 32) || null, versionCode: int(b.versionCode),
    device: sanitizeText(b.device, 128) || null,
    // Compte et pays : relier un relevé à son propriétaire dans la vue d'exploitation (jamais l'adresse IP).
    acct: auth.acct.login, country: countryOf(req),
  };
  const key = `${kind}:${base.ts}:${rand(6)}`;
  const entry = isCrash
    ? { ...base, androidSdk: int(b.androidSdk), stack: sanitizeText(b.stack, 32 * 1024) }
    : { ...base, level: LEVELS.includes(b.level) ? b.level : "info", tag: sanitizeText(b.tag, 32) || "app", message: sanitizeText(b.message, 4000) };
  await env.CONFIG.put(key, JSON.stringify(entry), { expirationTtl: isCrash ? CRASH_TTL_S : EVENT_TTL_S });
  return json({ ok: true });
}

// ---- tableaux de bord crashs / journaux (secret serveur) -----------------------

async function checkSecret(req, env, secret, lockName) {
  if (!secret || secret.length < 32) throw new ConfigError(`${lockName} absent ou trop court`);
  const ip = clientIp(req);
  const lock = await lockedOut(env, `lock:${lockName}:${ip}`);
  if (lock) return { res: lock };
  const given = parseOpsSecret(req);
  if (given && timingSafeEqual(given, secret)) return { ok: true };
  if (given) {
    const f = await guardStub(env, `lock:${lockName}:${ip}`).lockFail(SECRET_LOCK);
    if (f.locked) return { res: tooMany(f.retryAfter) };
  }
  return { res: new Response("Unauthorized", { status: 401, headers: { "www-authenticate": 'Basic realm="ultratv-ops", charset="UTF-8"' } }) };
}

async function opsPage(req, env, kind) {
  const c = await checkSecret(req, env, env.OPS_TOKEN, "ops");
  if (c.res) return c.res;
  const limit = Math.max(10, Math.min(500, parseInt(new URL(req.url).searchParams.get("limit") || "100", 10) || 100));
  const keys = [];
  let cursor;
  do {
    const r = await env.CONFIG.list({ prefix: `${kind}:`, cursor, limit: 1000 });
    keys.push(...r.keys.map((k) => k.name));
    cursor = r.list_complete ? undefined : r.cursor;
  } while (cursor && keys.length < 5000);
  const recent = keys.sort().reverse().slice(0, limit);
  const items = (await Promise.all(recent.map(async (k) => {
    try { const v = JSON.parse(await env.CONFIG.get(k)); return v?.v === 2 ? v : null; } catch { return null; }
  }))).filter(Boolean);
  const n = nonce();
  return kind === "crash" ? crashesPage(n, items) : eventsPage(n, items);
}

// ---- migration depuis l'ancien format ------------------------------------------

const LEGACY_MAC = /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/;

/**
 * POST /api/admin/migrate  (Authorization: Bearer <ADMIN_TOKEN>)
 *  - comptes protégés par un mot de passe : réécrits au nouveau format (fournisseurs
 *    chiffrés), l'ancien mot de passe reste valable, login = ancienne MAC ;
 *  - entrées SANS mot de passe : supprimées (elles étaient lisibles par n'importe qui) ;
 *  - crashs/journaux hérités (non nettoyés) et compteurs `lk:*` : supprimés.
 * Idempotent et paginé (?cursor=…&limit=…).
 */
async function adminMigrate(req, env, url) {
  if (!env.ADMIN_TOKEN) return new Response("Not found", { status: 404 });
  const c = await checkSecret(req, env, env.ADMIN_TOKEN, "admin");
  if (c.res) return c.res;
  const limit = Math.max(1, Math.min(1000, parseInt(url.searchParams.get("limit") || "500", 10) || 500));
  const page = await env.CONFIG.list({ cursor: url.searchParams.get("cursor") || undefined, limit });
  const rep = { migrated: 0, purgedUnprotected: 0, purgedLogs: 0, purgedLocks: 0, conflicts: 0, skipped: 0 };
  for (const { name } of page.keys) {
    if (LEGACY_MAC.test(name)) await migrateLegacy(env, name, rep);
    else if (name.startsWith("lk:")) { await env.CONFIG.delete(name); rep.purgedLocks++; }
    else if (name.startsWith("crash:") || name.startsWith("event:")) {
      let v = null;
      try { v = JSON.parse(await env.CONFIG.get(name)); } catch { /* corrompu : supprimé */ }
      if (v?.v === 2) rep.skipped++; else { await env.CONFIG.delete(name); rep.purgedLogs++; }
    } else rep.skipped++;
  }
  return json({ ...rep, done: page.list_complete, cursor: page.list_complete ? null : page.cursor });
}

async function migrateLegacy(env, mac, rep) {
  let old;
  try { old = JSON.parse(await env.CONFIG.get(mac)); } catch { old = null; }
  if (!old?.passwordHash) { await env.CONFIG.delete(mac); rep.purgedUnprotected++; return; }
  if (!(await guardStub(env, `acct:${mac}`).claim())) { await env.CONFIG.delete(mac); rep.conflicts++; return; }
  const acct = { login: mac, passwordHash: old.passwordHash, salt: old.salt, devices: [], sessEpoch: 0, createdAt: Date.now(), migrated: true };
  const providers = (Array.isArray(old.providers) ? old.providers : []).slice(0, MAX_PROVIDERS).flatMap((p) => {
    const fd = new URLSearchParams({ kind: p?.kind ?? "", name: p?.name ?? "", url: p?.url ?? "", username: p?.username ?? "", password: p?.password ?? "", mac: p?.mac ?? "" });
    const r = parseProvider(fd);
    return r.provider ? [r.provider] : [];
  });
  await saveProviders(env, acct, providers);
  await putAccount(env, acct);
  await env.CONFIG.delete(mac); // le clair disparaît seulement une fois la copie chiffrée écrite
  rep.migrated++;
}
