// Ultra TV Web — Cloudflare Worker proxy.
//
// Solves:
//   1. CORS: most Xtream/M3U servers don't send Access-Control-Allow-Origin.
//   2. User-Agent: browsers block UA overrides; this worker forwards them.
//   3. HTTPS-to-HTTP mixed content.
//
// Known limitation: upstreams that are themselves on Cloudflare may block
// Worker-originated traffic (CF anti-Worker-loop). When that happens the
// upstream returns 403/code:1003 — we surface it as a clearer error.
//
// Usage: GET /?target=<URL-encoded>  with optional X-SV-UA / X-SV-Referer.
// REQUIRED ALLOWED_HOSTS env: comma-separated upstream hostnames. The proxy is
// default-deny — if ALLOWED_HOSTS is unset/empty it refuses all traffic with a
// 403 so the Worker can't be abused as an open relay.

const REALISTIC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return preflight();
    }

    const url = new URL(request.url);
    const target = url.searchParams.get("target");
    if (!target) {
      return json({ error: "Missing ?target=<url>" }, 400);
    }

    let upstream;
    try {
      upstream = new URL(target);
    } catch {
      return json({ error: "Invalid target URL" }, 400);
    }

    // Default-deny: never act as an open relay. ALLOWED_HOSTS must be set.
    const allowed = (env.ALLOWED_HOSTS || "").split(",").map((h) => h.trim()).filter(Boolean);
    if (allowed.length === 0) {
      return json({
        error: "Proxy not configured: ALLOWED_HOSTS is unset. Operator must set the ALLOWED_HOSTS var (comma-separated upstream hostnames) before this proxy will forward any request.",
      }, 403);
    }
    if (!allowed.includes(upstream.hostname)) {
      return json({ error: `Host ${upstream.hostname} not in ALLOWED_HOSTS` }, 403);
    }

    const ua = request.headers.get("x-sv-ua") || REALISTIC_UA;
    const referer = request.headers.get("x-sv-referer");

    const headers = new Headers();
    headers.set("User-Agent", ua);
    // Browser-realistic headers to reduce CF Bot Fight rejections
    headers.set("Accept", "*/*");
    headers.set("Accept-Language", "en-US,en;q=0.9");
    headers.set("Accept-Encoding", "gzip, deflate, br");
    if (referer) headers.set("Referer", referer);
    const range = request.headers.get("range");
    if (range) headers.set("Range", range);

    let body = undefined;
    if (request.method !== "GET" && request.method !== "HEAD") {
      body = await request.arrayBuffer();
    }

    let upstreamRes;
    try {
      upstreamRes = await fetch(upstream.toString(), {
        method: request.method,
        headers,
        body,
        redirect: "follow",
        cf: { cacheTtl: 0 },
      });
    } catch (e) {
      return json({ error: `Upstream fetch failed: ${e.message || e}` }, 502);
    }

    const responseHeaders = new Headers(upstreamRes.headers);
    applyCors(responseHeaders);
    responseHeaders.delete("set-cookie");

    // Detect Cloudflare-on-Cloudflare blocking and add a hint in a diagnostic header
    if (upstreamRes.status === 403) {
      const cfRay = upstreamRes.headers.get("cf-ray");
      if (cfRay) {
        responseHeaders.set("X-SV-Diagnostic", "Upstream appears to be CF-protected and is rejecting Worker traffic (CF→CF anti-loop). Try a non-CF proxy.");
      }
    }

    return new Response(upstreamRes.body, {
      status: upstreamRes.status,
      statusText: upstreamRes.statusText,
      headers: responseHeaders,
    });
  },
};

function applyCors(headers) {
  headers.set("Access-Control-Allow-Origin", "*");
  headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
  headers.set("Access-Control-Allow-Headers", "Content-Type,Range,X-SV-UA,X-SV-Referer");
  headers.set("Access-Control-Expose-Headers", "Content-Length,Content-Range,Content-Type,X-SV-Diagnostic");
}

function preflight() {
  const h = new Headers();
  applyCors(h);
  return new Response(null, { status: 204, headers: h });
}

function json(obj, status = 200) {
  const h = new Headers({ "Content-Type": "application/json" });
  applyCors(h);
  return new Response(JSON.stringify(obj), { status, headers: h });
}
