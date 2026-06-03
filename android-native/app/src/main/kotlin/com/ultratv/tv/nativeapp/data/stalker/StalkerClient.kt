package com.ultratv.tv.nativeapp.data.stalker

import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stalker Portal (MAG-style) client. Stalker uses MAC-address authentication and
 * a per-session token. Real flow:
 *
 *   1. handshake → returns a JWT-style token tied to the MAC
 *   2. get_profile  → side-effect that some portals require before subsequent calls
 *   3. get_genres    → live categories
 *   4. get_all_channels → live channels (URLs are opaque `cmd` strings)
 *   5. create_link  → resolves a `cmd` into a playable URL at play time
 *
 * The `cmd` URLs returned by Stalker are not directly playable. They look like
 * `ffrt http://1.2.3.4/iptv/123/...` — Stalker expects the client to call
 * `create_link` to get the actual stream URL with a per-session token.
 * We resolve lazily right before playback (see [resolvePlayUrl]).
 *
 * Provider fields we use:
 *   baseUrl  — portal root, e.g. http://provider.com:8080 (no /c/ etc)
 *   username — MAC address (XX:XX:XX:XX:XX:XX)
 *   password — unused, kept for schema parity
 */
@Singleton
class StalkerClient @Inject constructor(private val ok: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val ua =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
                "MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    data class Session(val token: String, val portalRoot: String)

    // --- Session cache ------------------------------------------------------
    // The Stalker handshake (MAC → token) used to run on every zap because
    // resolvePlayUrl() called handshake() each time. The token is valid for the
    // whole portal session, so we cache it per provider with a short TTL and
    // reuse it across create_link calls. On a 401 / handshake failure we drop
    // the cached entry and force a fresh handshake (see cachedHandshake()).
    private data class CachedSession(val session: Session, val createdAtMs: Long)

    private val sessionCache = java.util.concurrent.ConcurrentHashMap<Long, CachedSession>()

    private val sessionTtlMs = 5 * 60 * 1000L // 5 minutes

    /** Drops the cached token for a provider so the next call re-handshakes. */
    private fun invalidateSession(providerId: Long) {
        sessionCache.remove(providerId)
    }

    /**
     * Returns a cached [Session] for the provider if it's still within the TTL,
     * otherwise performs a fresh handshake and caches it. Used by the per-zap
     * play path so we don't re-handshake on every channel change.
     */
    private suspend fun cachedHandshake(p: ProviderEntity): Session {
        val now = System.currentTimeMillis()
        sessionCache[p.id]?.let { cached ->
            if (now - cached.createdAtMs < sessionTtlMs) return cached.session
        }
        // Stale or absent — handshake() repopulates the cache itself.
        return handshake(p)
    }

    suspend fun handshake(p: ProviderEntity): Session = withContext(Dispatchers.IO) {
        val portal = portalRoot(p.baseUrl)
        val url = "$portal/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml"
        val body = call(url, p)
        val token = (json.parseToJsonElement(body) as? JsonObject)
            ?.get("js")?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
            ?: error("Stalker handshake failed (no token). Check URL and MAC.")
        // Some portals require a profile fetch right after handshake.
        runCatching { call("$portal/portal.php?type=stb&action=get_profile&token=$token", p, token) }
        // Cache so a subsequent zap (resolvePlayUrl) reuses this token instead
        // of re-handshaking. cachedHandshake()/resolvePlayUrl read this entry.
        Session(token = token, portalRoot = portal).also {
            sessionCache[p.id] = CachedSession(it, System.currentTimeMillis())
        }
    }

    suspend fun fetchLiveCategories(p: ProviderEntity, s: Session): List<CategoryEntity> = runCatching {
        val body = call("${s.portalRoot}/portal.php?type=itv&action=get_genres", p, s.token)
        val arr = (json.parseToJsonElement(body) as? JsonObject)?.get("js") as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rid = o["id"]?.str() ?: return@mapNotNull null
            val name = o["title"]?.str() ?: return@mapNotNull null
            CategoryEntity(providerId = p.id, kind = "LIVE", remoteId = rid, name = name)
        }
    }.getOrDefault(emptyList())

    suspend fun fetchLiveChannels(p: ProviderEntity, s: Session): List<ChannelEntity> = runCatching {
        // page=0 returns all items on most portals. If the portal paginates we'd
        // need to loop on total_items / max_page_items — punted to v2.
        val body = call(
            "${s.portalRoot}/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
            p, s.token,
        )
        val arr = (json.parseToJsonElement(body) as? JsonObject)
            ?.get("js")?.jsonObject?.get("data") as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rid = o["id"]?.str() ?: return@mapNotNull null
            val name = o["name"]?.str() ?: return@mapNotNull null
            val logo = o["logo"]?.str()?.takeIf { it.isNotBlank() }
            // We store the raw `cmd` here — it's NOT a playable URL. resolvePlayUrl()
            // converts it to a playable one at play time.
            val cmd = o["cmd"]?.str() ?: return@mapNotNull null
            val genre = o["tv_genre_id"]?.str()
            ChannelEntity(
                providerId = p.id,
                remoteId = rid,
                name = name,
                logo = logo,
                categoryId = genre,
                streamUrl = "stalker://$cmd",   // sentinel — see resolvePlayUrl()
            )
        }
    }.getOrDefault(emptyList())

    // ---- VOD (Movies) ----

    /**
     * Pulls VOD categories. Some MAG portals use `type=vod`, others `type=video`
     * — we try `vod` first and fall back to `video` if the response is empty.
     */
    suspend fun fetchVodCategories(p: ProviderEntity, s: Session): List<CategoryEntity> {
        val body = runCatching {
            call("${s.portalRoot}/portal.php?type=vod&action=get_categories", p, s.token)
        }.getOrNull().orEmpty()
        val arr = (json.parseToJsonElement(body) as? JsonObject)?.get("js") as? JsonArray
        val items = arr?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rid = o["id"]?.str() ?: return@mapNotNull null
            val name = o["title"]?.str() ?: return@mapNotNull null
            CategoryEntity(providerId = p.id, kind = "MOVIE", remoteId = rid, name = name)
        }.orEmpty()
        if (items.isNotEmpty()) return items
        // Fallback to "video".
        val body2 = runCatching {
            call("${s.portalRoot}/portal.php?type=video&action=get_categories", p, s.token)
        }.getOrNull().orEmpty()
        val arr2 = (json.parseToJsonElement(body2) as? JsonObject)?.get("js") as? JsonArray
        return arr2?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rid = o["id"]?.str() ?: return@mapNotNull null
            val name = o["title"]?.str() ?: return@mapNotNull null
            CategoryEntity(providerId = p.id, kind = "MOVIE", remoteId = rid, name = name)
        }.orEmpty()
    }

    /** Fetches the full VOD list (up to several thousand items). */
    suspend fun fetchVodMovies(p: ProviderEntity, s: Session): List<com.ultratv.tv.nativeapp.data.db.MovieEntity> {
        // get_ordered_list with page=0 returns everything on most portals.
        val out = mutableListOf<com.ultratv.tv.nativeapp.data.db.MovieEntity>()
        for (type in listOf("vod", "video")) {
            val body = runCatching {
                call("${s.portalRoot}/portal.php?type=$type&action=get_ordered_list&JsHttpRequest=1-xml", p, s.token)
            }.getOrNull() ?: continue
            val arr = (json.parseToJsonElement(body) as? JsonObject)
                ?.get("js")?.jsonObject?.get("data") as? JsonArray ?: continue
            arr.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val rid = o["id"]?.str() ?: return@forEach
                val name = o["name"]?.str() ?: return@forEach
                val cmd = o["cmd"]?.str() ?: return@forEach
                out += com.ultratv.tv.nativeapp.data.db.MovieEntity(
                    providerId = p.id,
                    remoteId = rid,
                    name = name,
                    poster = o["screenshot_uri"]?.str()?.takeIf { it.isNotBlank() }
                        ?: o["pic"]?.str()?.takeIf { it.isNotBlank() },
                    categoryId = o["category"]?.str() ?: o["genres_str"]?.str(),
                    streamUrl = "stalker://$cmd",
                    container = "mp4",
                    year = o["year"]?.str()?.toIntOrNull(),
                    rating = o["rating_imdb"]?.str()?.toDoubleOrNull(),
                    plot = o["description"]?.str(),
                )
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    // ---- Series ----

    suspend fun fetchSeriesCategories(p: ProviderEntity, s: Session): List<CategoryEntity> {
        val body = runCatching {
            call("${s.portalRoot}/portal.php?type=series&action=get_categories", p, s.token)
        }.getOrNull().orEmpty()
        val arr = (json.parseToJsonElement(body) as? JsonObject)?.get("js") as? JsonArray
        return arr?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rid = o["id"]?.str() ?: return@mapNotNull null
            val name = o["title"]?.str() ?: return@mapNotNull null
            CategoryEntity(providerId = p.id, kind = "SERIES", remoteId = rid, name = name)
        }.orEmpty()
    }

    suspend fun fetchSeries(p: ProviderEntity, s: Session): List<com.ultratv.tv.nativeapp.data.db.SeriesEntity> {
        val body = runCatching {
            call("${s.portalRoot}/portal.php?type=series&action=get_ordered_list&JsHttpRequest=1-xml", p, s.token)
        }.getOrNull() ?: return emptyList()
        val arr = (json.parseToJsonElement(body) as? JsonObject)
            ?.get("js")?.jsonObject?.get("data") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rid = o["id"]?.str() ?: return@mapNotNull null
            val name = o["name"]?.str() ?: return@mapNotNull null
            com.ultratv.tv.nativeapp.data.db.SeriesEntity(
                providerId = p.id,
                remoteId = rid,
                name = name,
                poster = o["screenshot_uri"]?.str()?.takeIf { it.isNotBlank() }
                    ?: o["pic"]?.str()?.takeIf { it.isNotBlank() },
                categoryId = o["category"]?.str() ?: o["genres_str"]?.str(),
                year = o["year"]?.str()?.toIntOrNull(),
                rating = o["rating_imdb"]?.str()?.toDoubleOrNull(),
                plot = o["description"]?.str(),
            )
        }
    }

    /**
     * Pulls the episode list for a single series. Most MAG portals expose
     * episodes via the same `get_ordered_list` action keyed on the series id:
     *   `?type=series&action=get_ordered_list&movie_id=<seriesId>&category=<seriesId>`
     *
     * Episode-number parsing handles a few common shapes:
     *  - `series` numeric  → S?? E<series>
     *  - `name` "S01 E03"  → parsed via regex
     *
     * Returns rows already keyed to [seriesLocalId] so we can insert them
     * straight into the episode table.
     */
    suspend fun fetchSeriesEpisodes(
        p: ProviderEntity,
        s: Session,
        seriesRemoteId: String,
        seriesLocalId: Long,
    ): List<com.ultratv.tv.nativeapp.data.db.EpisodeEntity> {
        val url = "${s.portalRoot}/portal.php?type=series&action=get_ordered_list" +
            "&movie_id=$seriesRemoteId&category=$seriesRemoteId&JsHttpRequest=1-xml"
        val body = runCatching { call(url, p, s.token) }.getOrNull() ?: return emptyList()
        val arr = (json.parseToJsonElement(body) as? JsonObject)
            ?.get("js")?.jsonObject?.get("data") as? JsonArray ?: return emptyList()

        val seasonEpRegex = Regex("[Ss](\\d+)\\s*[Ee](\\d+)")
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rid = o["id"]?.str() ?: return@mapNotNull null
            val cmd = o["cmd"]?.str() ?: return@mapNotNull null
            val name = o["name"]?.str() ?: "Episode"
            val seriesField = o["series"]?.str()
            // Some portals: "series": "[1, 2, 3]" or "1" or null. Take first int.
            val episodeNo = seriesField
                ?.replace(Regex("[^0-9]"), " ")?.trim()?.split(" ")
                ?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                ?: seasonEpRegex.find(name)?.groupValues?.get(2)?.toIntOrNull()
                ?: 0
            val season = seasonEpRegex.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            com.ultratv.tv.nativeapp.data.db.EpisodeEntity(
                seriesId = seriesLocalId,
                remoteId = rid,
                season = season,
                episode = episodeNo,
                title = name,
                streamUrl = "stalker://$cmd",
                container = null,
                plot = o["description"]?.str(),
            )
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** Turns a stored `stalker://<cmd>` URL into a playable URL via create_link. */
    suspend fun resolvePlayUrl(p: ProviderEntity, channelStreamUrl: String): String =
        withContext(Dispatchers.IO) {
            val cmd = channelStreamUrl.removePrefix("stalker://")
            val encoded = java.net.URLEncoder.encode(cmd, "UTF-8")
            // First try with the cached session token; if create_link fails
            // (expired token → HTTP 401 or empty/garbage body) drop the cache
            // and retry once with a fresh handshake.
            runCatching { createLink(p, encoded, cachedHandshake(p)) }
                .getOrElse {
                    // Likely an expired token (create_link → HTTP 401 / no URL).
                    // Force a fresh handshake (which re-caches) and retry once.
                    invalidateSession(p.id)
                    createLink(p, encoded, handshake(p))
                }
        }

    private suspend fun createLink(p: ProviderEntity, encodedCmd: String, s: Session): String {
        val body = call(
            "${s.portalRoot}/portal.php?type=itv&action=create_link&cmd=$encodedCmd&JsHttpRequest=1-xml",
            p, s.token,
        )
        val raw = (json.parseToJsonElement(body) as? JsonObject)
            ?.get("js")?.jsonObject?.get("cmd")?.jsonPrimitive?.contentOrNull
            ?: error("Stalker create_link returned no URL")
        // create_link typically returns "ffrt http://1.2.3.4/iptv/..." — strip the prefix.
        return raw.substringAfter(' ', raw)
    }

    // ---- Helpers ----

    private fun portalRoot(rawBase: String): String {
        val s = rawBase.trimEnd('/')
        // Common portal paths: "/c", "/stalker_portal/c". We always hit portal.php
        // at the same level, so strip these conveniences.
        return when {
            s.endsWith("/stalker_portal/c") -> s.removeSuffix("/c")
            s.endsWith("/c") -> s.removeSuffix("/c")
            else -> s
        }
    }

    private suspend fun call(url: String, p: ProviderEntity, token: String? = null): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("X-User-Agent", "Model: MAG250; Link: WiFi")
                .header("Accept", "*/*")
                .header("Cookie", "mac=${p.username.urlEnc()}; stb_lang=en; timezone=Europe/London")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            ok.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Stalker HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }

    private fun kotlinx.serialization.json.JsonElement.str(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun String.urlEnc(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
