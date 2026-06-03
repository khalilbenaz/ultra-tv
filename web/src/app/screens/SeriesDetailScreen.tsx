// Series detail with seasons, episode picker, in-player episode switching, and
// auto-play-next-episode equivalent. Loads episodes lazily via Xtream get_series_info.

import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { providerRepo, seriesRepo } from "@data/db/repositories";
import { loadSeriesEpisodes } from "@data/sync/syncCatalog";
import { resolveEpisodeUrl } from "@data/providers/xtream";
import type { Provider } from "@domain/model";
import { PlayerWithControls } from "@app/components/PlayerWithControls";
import { FavoriteButton } from "@app/components/FavoriteButton";
import { WatchlistButton } from "@app/components/WatchlistButton";
import type { Episode, Season, Series, StreamInfo } from "@domain/model";
import { streamTypeFromExtension } from "@domain/model";

export function SeriesDetailScreen() {
  const { id } = useParams();
  const seriesId = Number(id);
  const [series, setSeries] = useState<Series | null>(null);
  const [seasons, setSeasons] = useState<Season[]>([]);
  const [active, setActive] = useState<Episode | null>(null);
  const [provider, setProvider] = useState<Provider | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const s = await seriesRepo.get(seriesId);
      if (!s) return;
      if (cancelled) return;
      setSeries(s);
      const provider = await providerRepo.get(s.providerId);
      if (!provider) return;
      if (!cancelled) setProvider(provider);
      try {
        const { seasons: ses, enriched } = await loadSeriesEpisodes(provider, s.seriesId);
        if (cancelled) return;
        setSeasons(ses);
        // Persist enriched metadata so the next open is instant.
        const merged: Series = {
          ...s,
          posterUrl: enriched.posterUrl ?? s.posterUrl,
          backdropUrl: enriched.backdropUrl ?? s.backdropUrl,
          plot: enriched.plot ?? s.plot,
          cast: enriched.cast ?? s.cast,
          director: enriched.director ?? s.director,
          genre: enriched.genre ?? s.genre,
          releaseDate: enriched.releaseDate ?? s.releaseDate,
          rating: enriched.rating ?? s.rating,
          episodeRunTime: enriched.episodeRunTime ?? s.episodeRunTime,
        };
        await seriesRepo.put(merged);
        setSeries(merged);
      } catch (e) {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [seriesId]);

  const flatEpisodes = useMemo(() => seasons.flatMap((s) => s.episodes), [seasons]);

  const stream: StreamInfo | null = useMemo(() => {
    if (!active) return null;
    const url = provider ? resolveEpisodeUrl(provider, active) : active.streamUrl;
    return {
      url,
      title: active.title,
      headers: {},
      userAgent: provider?.userAgent || null,
      streamType: streamTypeFromExtension(active.containerExtension),
      containerExtension: active.containerExtension,
      catchUpUrl: null,
      expirationTime: null,
      drmInfo: null,
    };
  }, [active, provider]);

  // Auto-play next episode when the current one ends would hook here once the
  // VideoPlayer surfaces an onEnded callback — left as a follow-up.

  if (!series) return <div className="empty">Loading…</div>;

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        <h2 style={{ margin: 0 }}>{series.name}</h2>
        <FavoriteButton providerId={series.providerId} contentId={series.id} contentType="SERIES" size="small" />
        <WatchlistButton providerId={series.providerId} contentId={series.id} contentType="SERIES" />
        {series.youtubeTrailer && (
          <a className="filter-button" href={`https://www.youtube.com/watch?v=${series.youtubeTrailer}`} target="_blank" rel="noreferrer">▶ Trailer</a>
        )}
      </div>
      <div style={{ color: "var(--fg-muted)", marginBottom: 12 }}>
        {[series.releaseDate?.slice(0, 4), series.genre, series.episodeRunTime].filter(Boolean).join(" · ")}
      </div>
      {series.plot && <p style={{ maxWidth: 720 }}>{series.plot}</p>}
      {series.cast && <p style={{ color: "var(--fg-muted)" }}><strong>Cast:</strong> {series.cast}</p>}
      {series.director && <p style={{ color: "var(--fg-muted)" }}><strong>Director:</strong> {series.director}</p>}

      {loadError && <div className="banner error">Episode load failed: {loadError}</div>}

      {active && (
        <div style={{ margin: "12px 0" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 8, flexWrap: "wrap" }}>
            <strong>Now playing:</strong>
            <span>S{active.seasonNumber}E{active.episodeNumber} — {active.title}</span>
            <button style={{ marginLeft: "auto" }} onClick={() => {
              const idx = flatEpisodes.findIndex((e) => e.id === active.id);
              if (idx >= 0 && idx + 1 < flatEpisodes.length) setActive(flatEpisodes[idx + 1]!);
            }}>Next episode →</button>
          </div>
          <div style={{ height: 520 }}>
          <PlayerWithControls
            stream={stream}
            tracking={{
              providerId: series.providerId,
              contentId: active.id,
              contentType: "SERIES_EPISODE",
              title: `${series.name} — S${active.seasonNumber}E${active.episodeNumber}`,
              posterUrl: active.coverUrl ?? series.posterUrl,
              streamUrl: active.streamUrl,
              seriesId: series.id,
              seasonNumber: active.seasonNumber,
              episodeNumber: active.episodeNumber,
            }}
          />
          </div>
        </div>
      )}

      {seasons.map((season) => (
        <section key={season.seasonNumber} style={{ marginBottom: 24 }}>
          <h3>{season.name}</h3>
          <ul style={{ listStyle: "none", padding: 0, display: "grid", gap: 6 }}>
            {season.episodes.map((ep) => (
              <li key={ep.id}>
                <button
                  onClick={() => setActive(ep)}
                  style={{ width: "100%", textAlign: "left", display: "flex", gap: 12, alignItems: "center" }}
                >
                  <span style={{ color: "var(--fg-muted)", width: 48 }}>S{ep.seasonNumber}E{ep.episodeNumber}</span>
                  <span style={{ flex: 1 }}>{ep.title}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
