# Ultra TV — config dashboard

Cloudflare Worker exposing a per-MAC remote config. Each user creates their
own account with `(MAC, mot de passe)` — no master admin, no list of other
users' MACs.

## Deploy

```bash
cd cloudflare-config
npm i -g wrangler   # if not installed

# 1) Create the KV namespace and copy the returned ID into wrangler.toml.
wrangler kv:namespace create CONFIG
wrangler kv:namespace create CONFIG --preview

# 2) Set a session signing secret (used to HMAC the session cookie).
#    Any long random string works; rotate to invalidate all sessions.
#    REQUIRED: with neither SESSION_SECRET nor ADMIN_PASSWORD set, the worker
#    fails closed (HTTP 500) instead of signing with a guessable constant.
wrangler secret put SESSION_SECRET

# 3) (Optional) Set a dedicated token for crash/event ingest + the
#    /crashes and /logs viewers. Falls back to ADMIN_PASSWORD if unset, so
#    existing deployments keep working — but a dedicated token lets you keep
#    crash reporting separate from the admin password.
wrangler secret put CRASH_TOKEN

# 4) Deploy.
wrangler deploy
```

The Worker URL printed by wrangler is what you paste in the app's Settings
under "Cloudflare Worker URL".

> Existing deployments still using `ADMIN_PASSWORD` keep working — the worker
> falls back to it as the session-signing secret if `SESSION_SECRET` is unset.
> If **neither** is set the worker fails closed (HTTP 500) rather than signing
> sessions with a guessable built-in constant.

## Flow

1. User opens the Worker URL → **`/login`** or **`/signup`**.
2. Signup form takes the device's **MAC address** (visible in the Ultra TV
   app under Settings) and a **password** (≥ 4 chars). Storing creates a KV
   entry keyed by the normalised MAC with a salted SHA-256 password hash.
3. Signed in, the dashboard shows only **that one MAC's** providers. The user
   adds Xtream / M3U / Stalker entries, then opens the Ultra TV app, goes to
   Settings → "Config password" and enters the same password.
4. The app calls `GET /api/config/:mac?password=…`; the worker checks the
   password against the stored hash and returns the provider list.

## Endpoints

| Method | Path                                | Auth                    | Purpose                                  |
|--------|-------------------------------------|-------------------------|------------------------------------------|
| GET    | `/api/config/:mac`                  | none, or `?password=…` if `protectReads` | App fetches its own config |
| GET    | `/login`                            | none                    | Login form                               |
| POST   | `/login`                            | (mac, password)         | Issues session cookie                    |
| GET    | `/signup`                           | none                    | Sign-up form                             |
| POST   | `/signup`                           | (mac, password, confirm)| Creates account, issues cookie           |
| GET    | `/logout`                           | none                    | Clears cookie                            |
| GET    | `/`                                 | cookie                  | Dashboard for cookie's MAC               |
| POST   | `/api/provider/:mac`                | cookie (must own :mac)  | Add a provider                           |
| POST   | `/api/provider/:mac/:idx/delete`    | cookie (must own :mac)  | Remove provider at index                 |
| POST   | `/api/password/:mac`                | cookie (must own :mac)  | Change account password                  |
| POST   | `/api/config/:mac/delete`           | cookie (must own :mac)  | Delete the entire account                |
| POST   | `/api/config/:mac`                  | cookie (must own :mac) + CSRF | Raw JSON save (power user)         |
| POST   | `/api/settings/:mac`                | cookie (must own :mac) + CSRF | Toggle `protectReads`              |

The `:mac` in any authenticated path must equal the MAC inside the session
cookie — the worker returns `403` otherwise. No cross-account peeking.

All mutating cookie-authenticated routes are CSRF-protected. Form routes embed
a `csrf` hidden field; the raw-JSON `POST /api/config/:mac` accepts the token
in an `X-CSRF-Token` header (or a `csrf` field in the JSON body). The token is
derived from the session cookie, so logging out invalidates it.

## Read protection (`protectReads`)

By default `GET /api/config/:mac` returns the provider list **anonymously** —
the MAC itself is the bearer, and this lets the app sync without re-prompting
for the password. Owners who want reads gated too can flip the **"Exiger le
mot de passe pour lire la config"** toggle on the dashboard (panel "Protection
des lectures"). When enabled, `GET /api/config/:mac` requires `?password=…`
(verified against the stored hash) and returns `401` otherwise. It requires a
per-MAC password to be set first, and defaults to **off** (existing behavior).

## Provider JSON schema (stored in KV)

```json
{
  "passwordHash": "<sha256 of salt:plaintext>",
  "salt": "<16 random bytes hex>",
  "protectReads": false,
  "providers": [
    { "kind": "XTREAM",  "name": "My Xtream",  "url": "http://host:80",
      "username": "user", "password": "pass" },
    { "kind": "M3U",     "name": "My M3U",     "url": "https://my.host/list.m3u" },
    { "kind": "STALKER", "name": "MAG portal", "url": "http://host:8080",
      "mac": "00:1A:79:XX:XX:XX" }
  ]
}
```

## Migrating an existing MAC entry that has no password yet

The first time the owner of an unprotected MAC hits `/login`, they are
redirected to `/signup` to claim the entry by setting a password. The MAC's
existing provider list is preserved.
