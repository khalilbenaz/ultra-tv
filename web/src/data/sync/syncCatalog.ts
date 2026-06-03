// Full catalog sync — equivalent of SyncManager.kt's run() entrypoint.
// Strategy:
//   1. Fetch categories (3 small calls)
//   2. Fetch live streams in one call (light)
//   3. Fetch VOD + series PER CATEGORY (the "all" endpoint returns 50-200MB on big
//      providers and times out at the edge). Per-category fetches are ~100KB-2MB
//      each and complete reliably.
// Progress is reported via the onProgress callback so the UI can show "syncing
// 23/120 movie categories…".

import type {
  Category,
  Channel,
  Episode,
  Movie,
  Provider,
  Season,
  Series,
} from "@domain/model";
import { xtream, type XtreamCreds } from "@data/providers/xtream";
import {
  categoryRepo,
  channelRepo,
  episodeRepo,
  movieRepo,
  seriesRepo,
  settingsRepo,
} from "@data/db/repositories";
import { buildCategoryFilter, filterCategoriesByWhitelist, MOVIE_ID_OFFSET, SERIES_ID_OFFSET } from "@data/sync/filterCategories";

interface XtreamSeriesInfo {
  info?: {
    cover?: string;
    backdrop_path?: string[];
    plot?: string;
    cast?: string;
    director?: string;
    genre?: string;
    releaseDate?: string;
    rating?: string;
    episode_run_time?: string;
  };
  seasons?: Array<{ season_number?: number; name?: string; cover?: string; air_date?: string; episode_count?: number }>;
  episodes?: Record<string, Array<{ id: string; title?: string; container_extension?: string; info?: { duration_secs?: number; plot?: string; rating?: number; movie_image?: string; releasedate?: string }; episode_num?: number; season?: number }>>;
}

const ADULT_HINTS = ["xxx", "adult", "18+", "porn", "ero", "adulte", "+18", "للكبار"];
function looksAdult(name: string): boolean {
  const lc = name.toLowerCase();
  return ADULT_HINTS.some((h) => lc.includes(h));
}

// Auto-create parental locks for adult-detected categories on first sync, so the
// user has at least a baseline protection without having to opt in manually.
async function autoLockAdultCategories(
  providerId: number,
  cats: Array<{ id: number; type: string; isAdult: boolean }>,
): Promise<void> {
  const { parentalLockRepo } = await import("@data/db/repositories");
  const existing = await parentalLockRepo.list(providerId);
  const existingIds = new Set(existing.map((l) => l.categoryId));
  const adultCats = cats.filter((c) => c.isAdult && !existingIds.has(c.id));
  for (const c of adultCats) {
    await parentalLockRepo.add({
      providerId,
      categoryId: c.id,
      contentType: c.type as "LIVE" | "MOVIE" | "SERIES",
      createdAt: Date.now(),
    });
  }
}

export type SyncProgress = (message: string, current?: number, total?: number) => void;

export interface SyncResult {
  channelsCount: number;
  moviesCount: number;
  seriesCount: number;
  liveCategoriesSkipped: number;
  movieCategoriesSkipped: number;
  seriesCategoriesSkipped: number;
}

export async function syncXtreamCatalog(
  provider: Provider,
  onProgress: SyncProgress = () => {},
  signal?: AbortSignal,
): Promise<SyncResult> {
  const checkAbort = () => { if (signal?.aborted) throw new DOMException("Sync cancelled", "AbortError"); };
  const creds: XtreamCreds = {
    serverUrl: provider.serverUrl,
    username: provider.username,
    password: provider.password,
    userAgent: provider.userAgent || null,
    referer: provider.httpReferer || null,
  };

  checkAbort();
  onProgress("Clearing previous catalog…");
  await Promise.all([
    channelRepo.clearProvider(provider.id),
    movieRepo.clearProvider(provider.id),
    seriesRepo.clearProvider(provider.id),
    categoryRepo.clearProvider(provider.id),
  ]);

  onProgress("Fetching categories…");
  const [liveCats, vodCats, seriesCats] = await Promise.all([
    xtream.liveCategories(creds, signal),
    xtream.vodCategories(creds, signal),
    xtream.seriesCategories(creds, signal),
  ]);
  checkAbort();

  const cats: (Category & { providerId: number })[] = [
    ...liveCats.map((c) => ({
      id: Number.parseInt(c.category_id, 10),
      roomId: Number.parseInt(c.category_id, 10),
      name: c.category_name,
      parentId: c.parent_id || null,
      type: "LIVE" as const,
      isVirtual: false,
      count: 0,
      isAdult: looksAdult(c.category_name),
      isUserProtected: false,
      providerId: provider.id,
    })),
    ...vodCats.map((c) => ({
      id: Number.parseInt(c.category_id, 10) + 1_000_000,
      roomId: Number.parseInt(c.category_id, 10),
      name: c.category_name,
      parentId: c.parent_id || null,
      type: "MOVIE" as const,
      isVirtual: false,
      count: 0,
      isAdult: looksAdult(c.category_name),
      isUserProtected: false,
      providerId: provider.id,
    })),
    ...seriesCats.map((c) => ({
      id: Number.parseInt(c.category_id, 10) + 2_000_000,
      roomId: Number.parseInt(c.category_id, 10),
      name: c.category_name,
      parentId: c.parent_id || null,
      type: "SERIES" as const,
      isVirtual: false,
      count: 0,
      isAdult: looksAdult(c.category_name),
      isUserProtected: false,
      providerId: provider.id,
    })),
  ];
  await categoryRepo.bulkAdd(cats);
  // Best-effort auto-lock — never throws into the sync flow.
  try { await autoLockAdultCategories(provider.id, cats); } catch (e) { console.warn("Auto-lock failed:", e); }

  onProgress("Fetching live channels…");
  const liveStreams = await xtream.liveStreams(creds, undefined, signal);
  checkAbort();
  const channels: Channel[] = liveStreams.map((s) => ({
    id: s.stream_id,
    name: s.name,
    canonicalName: s.name,
    logoUrl: s.stream_icon || null,
    groupTitle: null,
    categoryId: Number.parseInt(s.category_id, 10) || null,
    categoryName: null,
    // M-3: do not persist credentialed URL — rebuilt at play time from streamId.
    streamUrl: "",
    epgChannelId: s.epg_channel_id,
    number: s.num,
    isFavorite: false,
    catchUpSupported: s.tv_archive === 1,
    catchUpDays: s.tv_archive_duration,
    catchUpSource: null,
    providerId: provider.id,
    currentProgram: null,
    nextProgram: null,
    isAdult: false,
    isUserProtected: false,
    logicalGroupId: s.name,
    selectedVariantId: s.stream_id,
    errorCount: 0,
    qualityOptions: [],
    alternativeStreams: [],
    variants: [],
    streamId: s.stream_id,
  }));
  await channelRepo.bulkAdd(channels);

  // Respect Settings → Categories whitelist. Null = include everything (first sync).
  const movieWhitelist = await settingsRepo.get<number[]>(`filters:${provider.id}:MOVIE`);
  const seriesWhitelist = await settingsRepo.get<number[]>(`filters:${provider.id}:SERIES`);
  const movieFilter = buildCategoryFilter(movieWhitelist, MOVIE_ID_OFFSET);
  const seriesFilter = buildCategoryFilter(seriesWhitelist, SERIES_ID_OFFSET);

  const targetedVodCats = filterCategoriesByWhitelist(vodCats, movieFilter);
  const effectiveSeriesCats = filterCategoriesByWhitelist(seriesCats, seriesFilter);

  // Movies — per-category to keep response size manageable for large providers.
  let movieCursor = 0;
  const allMovies: Movie[] = [];
  const vodCatsToFetch = targetedVodCats;
  for (const cat of vodCatsToFetch) {
    checkAbort();
    movieCursor++;
    onProgress(`Movies — ${cat.category_name}`, movieCursor, vodCatsToFetch.length);
    try {
      const vods = await xtream.vodStreams(creds, cat.category_id, signal);
      for (const m of vods) {
        allMovies.push({
          id: m.stream_id,
          name: m.name,
          posterUrl: m.stream_icon || null,
          backdropUrl: null,
          categoryId: Number.parseInt(m.category_id, 10) + 1_000_000 || null,
          categoryName: cat.category_name,
          // M-3: rebuilt at play time from streamId + containerExtension.
          streamUrl: "",
          containerExtension: m.container_extension || null,
          plot: null,
          cast: null,
          director: null,
          genre: null,
          releaseDate: null,
          duration: null,
          durationSeconds: 0,
          rating: m.rating_5based ? Math.min(10, m.rating_5based * 2) : 0,
          year: null,
          tmdbId: null,
          youtubeTrailer: null,
          isFavorite: false,
          providerId: provider.id,
          watchProgress: 0,
          lastWatchedAt: 0,
          isAdult: false,
          isUserProtected: false,
          streamId: m.stream_id,
          addedAt: Number.parseInt(m.added, 10) * 1000 || 0,
        });
      }
    } catch (e) {
      console.warn(`Failed to fetch VOD category ${cat.category_name}:`, e);
    }
  }
  // Flush in 5000-row batches so Dexie doesn't choke on a single huge transaction
  for (let i = 0; i < allMovies.length; i += 5000) {
    await movieRepo.bulkAdd(allMovies.slice(i, i + 5000));
  }

  // Series — per-category
  let seriesCursor = 0;
  const allSeries: Series[] = [];
  for (const cat of effectiveSeriesCats) {
    checkAbort();
    seriesCursor++;
    onProgress(`Series — ${cat.category_name}`, seriesCursor, effectiveSeriesCats.length);
    try {
      const list = await xtream.seriesList(creds, cat.category_id, signal);
      for (const s of list) {
        allSeries.push({
          id: s.series_id,
          name: s.name,
          posterUrl: s.cover || null,
          backdropUrl: s.backdrop_path?.[0] ?? null,
          categoryId: Number.parseInt(s.category_id, 10) + 2_000_000 || null,
          categoryName: cat.category_name,
          plot: s.plot || null,
          cast: s.cast || null,
          director: s.director || null,
          genre: s.genre || null,
          releaseDate: s.releaseDate || null,
          rating: s.rating_5based ? Math.min(10, s.rating_5based * 2) : 0,
          tmdbId: null,
          youtubeTrailer: s.youtube_trailer || null,
          isFavorite: false,
          providerId: provider.id,
          seasons: [],
          episodeRunTime: s.episode_run_time || null,
          lastModified: Number.parseInt(s.last_modified, 10) * 1000 || 0,
          isAdult: false,
          isUserProtected: false,
          seriesId: s.series_id,
          providerSeriesId: String(s.series_id),
        });
      }
    } catch (e) {
      console.warn(`Failed to fetch series category ${cat.category_name}:`, e);
    }
  }
  for (let i = 0; i < allSeries.length; i += 5000) {
    await seriesRepo.bulkAdd(allSeries.slice(i, i + 5000));
  }

  const result: SyncResult = {
    channelsCount: channels.length,
    moviesCount: allMovies.length,
    seriesCount: allSeries.length,
    liveCategoriesSkipped: 0,
    movieCategoriesSkipped: vodCats.length - vodCatsToFetch.length,
    seriesCategoriesSkipped: seriesCats.length - effectiveSeriesCats.length,
  };
  onProgress(`Sync complete — ${channels.length} channels, ${allMovies.length} movies, ${allSeries.length} series`);
  return result;
}

export async function loadSeriesEpisodes(provider: Provider, seriesId: number): Promise<{ seasons: Season[]; enriched: Partial<Series> }> {
  const creds: XtreamCreds = {
    serverUrl: provider.serverUrl,
    username: provider.username,
    password: provider.password,
    userAgent: provider.userAgent || null,
    referer: provider.httpReferer || null,
  };
  const info = (await xtream.seriesInfo(creds, seriesId)) as XtreamSeriesInfo;

  const episodes: Episode[] = [];
  const seasons: Season[] = [];

  for (const [seasonKey, eps] of Object.entries(info.episodes ?? {})) {
    const seasonNumber = Number.parseInt(seasonKey, 10) || 0;
    const seasonMeta = info.seasons?.find((s) => (s.season_number ?? 0) === seasonNumber);
    const seasonEpisodes: Episode[] = (eps ?? []).map((e) => {
      const ep: Episode = {
        id: Number.parseInt(e.id, 10),
        title: e.title ?? `Episode ${e.episode_num ?? 0}`,
        episodeNumber: e.episode_num ?? 0,
        seasonNumber,
        // M-3: rebuilt at play time from episodeId + containerExtension.
        streamUrl: "",
        containerExtension: e.container_extension ?? null,
        coverUrl: e.info?.movie_image ?? null,
        plot: e.info?.plot ?? null,
        duration: null,
        durationSeconds: e.info?.duration_secs ?? 0,
        rating: e.info?.rating ?? 0,
        releaseDate: e.info?.releasedate ?? null,
        seriesId,
        providerId: provider.id,
        watchProgress: 0,
        lastWatchedAt: 0,
        isAdult: false,
        isUserProtected: false,
        episodeId: Number.parseInt(e.id, 10),
      };
      episodes.push(ep);
      return ep;
    });

    seasons.push({
      seasonNumber,
      name: seasonMeta?.name ?? `Season ${seasonNumber}`,
      coverUrl: seasonMeta?.cover ?? null,
      episodes: seasonEpisodes,
      airDate: seasonMeta?.air_date ?? null,
      episodeCount: seasonEpisodes.length,
    });
  }

  await episodeRepo.clearSeries(seriesId);
  await episodeRepo.bulkAdd(episodes);

  const meta = info.info ?? {};
  const enriched: Partial<Series> = {
    posterUrl: meta.cover ?? undefined,
    backdropUrl: meta.backdrop_path?.[0] ?? undefined,
    plot: meta.plot ?? undefined,
    cast: meta.cast ?? undefined,
    director: meta.director ?? undefined,
    genre: meta.genre ?? undefined,
    releaseDate: meta.releaseDate ?? undefined,
    rating: meta.rating ? Number.parseFloat(meta.rating) || undefined : undefined,
    episodeRunTime: meta.episode_run_time ?? undefined,
  };

  return { seasons: seasons.sort((a, b) => a.seasonNumber - b.seasonNumber), enriched };
}
