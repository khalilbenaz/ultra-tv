// Xtream Codes API client.
// Endpoints from the Xtream Codes player API (`player_api.php`).

import { proxiedJson } from "@data/net/proxy";
import type { Channel, Episode, Movie, Provider } from "@domain/model";

export interface XtreamCreds {
  serverUrl: string;
  username: string;
  password: string;
  userAgent?: string | null;
  referer?: string | null;
}

export interface XtreamUserInfo {
  username: string;
  password: string;
  message: string;
  auth: number;
  status: string;
  exp_date: string | null;
  is_trial: string;
  active_cons: string;
  created_at: string;
  max_connections: string;
  allowed_output_formats: string[];
}

export interface XtreamServerInfo {
  url: string;
  port: string;
  https_port: string;
  server_protocol: string;
  rtmp_port: string;
  timezone: string;
  timestamp_now: number;
  time_now: string;
}

export interface XtreamHandshake {
  user_info: XtreamUserInfo;
  server_info: XtreamServerInfo;
}

export interface XtreamCategory {
  category_id: string;
  category_name: string;
  parent_id: number;
}

export interface XtreamLiveStream {
  num: number;
  name: string;
  stream_type: string;
  stream_id: number;
  stream_icon: string;
  epg_channel_id: string | null;
  added: string;
  category_id: string;
  category_ids?: number[];
  custom_sid: string;
  tv_archive: number;
  direct_source: string;
  tv_archive_duration: number;
}

export interface XtreamVod {
  num: number;
  name: string;
  stream_type: string;
  stream_id: number;
  stream_icon: string;
  rating: string;
  rating_5based: number;
  added: string;
  category_id: string;
  container_extension: string;
  custom_sid: string;
  direct_source: string;
}

export interface XtreamSeries {
  num: number;
  name: string;
  series_id: number;
  cover: string;
  plot: string;
  cast: string;
  director: string;
  genre: string;
  releaseDate: string;
  last_modified: string;
  rating: string;
  rating_5based: number;
  backdrop_path: string[];
  youtube_trailer: string;
  episode_run_time: string;
  category_id: string;
}

function buildPlayerApi(c: XtreamCreds, params: Record<string, string | number>): string {
  const url = new URL("player_api.php", normalizeBase(c.serverUrl));
  url.searchParams.set("username", c.username);
  url.searchParams.set("password", c.password);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, String(v));
  return url.toString();
}

function normalizeBase(serverUrl: string): string {
  return serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
}

async function getJson<T>(url: string, creds: XtreamCreds, signal?: AbortSignal): Promise<T> {
  return proxiedJson<T>(url, { signal, userAgent: creds.userAgent ?? null, referer: creds.referer ?? null });
}

export const xtream = {
  handshake: (c: XtreamCreds, signal?: AbortSignal) =>
    getJson<XtreamHandshake>(buildPlayerApi(c, {}), c, signal),

  liveCategories: (c: XtreamCreds, signal?: AbortSignal) =>
    getJson<XtreamCategory[]>(buildPlayerApi(c, { action: "get_live_categories" }), c, signal),

  vodCategories: (c: XtreamCreds, signal?: AbortSignal) =>
    getJson<XtreamCategory[]>(buildPlayerApi(c, { action: "get_vod_categories" }), c, signal),

  seriesCategories: (c: XtreamCreds, signal?: AbortSignal) =>
    getJson<XtreamCategory[]>(buildPlayerApi(c, { action: "get_series_categories" }), c, signal),

  liveStreams: (c: XtreamCreds, categoryId?: string, signal?: AbortSignal) =>
    getJson<XtreamLiveStream[]>(
      buildPlayerApi(c, categoryId ? { action: "get_live_streams", category_id: categoryId } : { action: "get_live_streams" }),
      c,
      signal,
    ),

  vodStreams: (c: XtreamCreds, categoryId?: string, signal?: AbortSignal) =>
    getJson<XtreamVod[]>(
      buildPlayerApi(c, categoryId ? { action: "get_vod_streams", category_id: categoryId } : { action: "get_vod_streams" }),
      c,
      signal,
    ),

  seriesList: (c: XtreamCreds, categoryId?: string, signal?: AbortSignal) =>
    getJson<XtreamSeries[]>(
      buildPlayerApi(c, categoryId ? { action: "get_series", category_id: categoryId } : { action: "get_series" }),
      c,
      signal,
    ),

  seriesInfo: (c: XtreamCreds, seriesId: number, signal?: AbortSignal) =>
    getJson<unknown>(buildPlayerApi(c, { action: "get_series_info", series_id: seriesId }), c, signal),

  vodInfo: (c: XtreamCreds, vodId: number, signal?: AbortSignal) =>
    getJson<{
      info?: {
        kinopoisk_url?: string;
        tmdb_id?: string;
        name?: string;
        o_name?: string;
        cover_big?: string;
        movie_image?: string;
        releasedate?: string;
        episode_run_time?: string;
        youtube_trailer?: string;
        director?: string;
        actors?: string;
        cast?: string;
        description?: string;
        plot?: string;
        age?: string;
        country?: string;
        genre?: string;
        backdrop_path?: string[];
        duration_secs?: number;
        duration?: string;
        rating?: string;
        rating_kinopoisk?: string;
      };
      movie_data?: {
        stream_id?: number;
        name?: string;
        added?: string;
        category_id?: string;
        container_extension?: string;
        custom_sid?: string;
        direct_source?: string;
      };
    }>(buildPlayerApi(c, { action: "get_vod_info", vod_id: vodId }), c, signal),

  shortEpg: (c: XtreamCreds, streamId: number, limit = 4, signal?: AbortSignal) =>
    getJson<unknown>(
      buildPlayerApi(c, { action: "get_short_epg", stream_id: streamId, limit }),
      c,
      signal,
    ),

  // Stream URL builders — mirror StreamUrlBuilder.kt
  liveStreamUrl(c: XtreamCreds, streamId: number, ext = "m3u8"): string {
    return `${normalizeBase(c.serverUrl)}live/${c.username}/${c.password}/${streamId}.${ext}`;
  },
  // Catch-up / time-shift URL for a past program window.
  // Xtream conventions: streaming/timeshift.php?username=…&password=…&stream=ID&start=YYYY-MM-DD:HH-MM&duration=MIN
  catchUpUrl(c: XtreamCreds, streamId: number, startTimeMs: number, durationMin: number): string {
    const d = new Date(startTimeMs);
    const pad = (n: number) => n.toString().padStart(2, "0");
    const stamp = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}:${pad(d.getHours())}-${pad(d.getMinutes())}`;
    const base = normalizeBase(c.serverUrl);
    return `${base}streaming/timeshift.php?username=${encodeURIComponent(c.username)}&password=${encodeURIComponent(c.password)}&stream=${streamId}&start=${stamp}&duration=${durationMin}`;
  },
  vodStreamUrl(c: XtreamCreds, streamId: number, ext: string): string {
    return `${normalizeBase(c.serverUrl)}movie/${c.username}/${c.password}/${streamId}.${ext}`;
  },
  seriesEpisodeUrl(c: XtreamCreds, episodeId: number, ext: string): string {
    return `${normalizeBase(c.serverUrl)}series/${c.username}/${c.password}/${episodeId}.${ext}`;
  },
};

// --- On-demand stream URL resolution (M-3) ---------------------------------
//
// Xtream catalog rows no longer persist a fully-credentialed stream URL
// (`http://host/movie/user/pass/123.mp4`). Storing user:pass on every row meant
// BackupManager exports leaked credentials in plaintext JSON. Instead, Xtream
// rows store only the lean rebuildable form — `streamId`/`episodeId` plus
// `containerExtension` (already present on the row) — and the URL is built at
// play time from the live Provider record.
//
// Backward compatibility: rows synced before this change still carry a
// non-empty `streamUrl`; resolvers prefer that value when present. New Xtream
// syncs store an empty `streamUrl`. M3U / Stalker providers have no rebuildable
// form, so their stored `streamUrl` is always used as-is.

function credsOf(p: Provider): XtreamCreds {
  return {
    serverUrl: p.serverUrl,
    username: p.username,
    password: p.password,
    userAgent: p.userAgent || null,
    referer: p.httpReferer || null,
  };
}

/** Build the live channel stream URL from a Provider + Channel row. */
export function resolveChannelUrl(provider: Provider, channel: Pick<Channel, "streamUrl" | "streamId">): string {
  if (channel.streamUrl) return channel.streamUrl; // legacy row or M3U/Stalker
  return xtream.liveStreamUrl(credsOf(provider), channel.streamId);
}

/** Build the VOD/movie stream URL from a Provider + Movie row. */
export function resolveMovieUrl(provider: Provider, movie: Pick<Movie, "streamUrl" | "streamId" | "containerExtension">): string {
  if (movie.streamUrl) return movie.streamUrl; // legacy row
  return xtream.vodStreamUrl(credsOf(provider), movie.streamId, movie.containerExtension || "mp4");
}

/** Build the series episode stream URL from a Provider + Episode row. */
export function resolveEpisodeUrl(provider: Provider, episode: Pick<Episode, "streamUrl" | "episodeId" | "containerExtension">): string {
  if (episode.streamUrl) return episode.streamUrl; // legacy row
  return xtream.seriesEpisodeUrl(credsOf(provider), episode.episodeId, episode.containerExtension || "mp4");
}
