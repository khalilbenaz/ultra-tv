// Movie detail page — loads richer info (plot, cast, director, backdrop) on demand via
// Xtream's get_vod_info endpoint, persisting the result back into the Movie row so
// repeat opens are instant.

import { useLiveQuery } from "dexie-react-hooks";
import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { historyRepo, movieRepo, providerRepo } from "@data/db/repositories";
import { xtream, resolveMovieUrl } from "@data/providers/xtream";
import { PlayerWithControls } from "@app/components/PlayerWithControls";
import { ProxyImg } from "@app/components/ProxyImg";
import { FavoriteButton } from "@app/components/FavoriteButton";
import { WatchlistButton } from "@app/components/WatchlistButton";
import type { Movie, StreamInfo } from "@domain/model";
import { streamTypeFromExtension } from "@domain/model";

export function MovieDetailScreen() {
  const { id } = useParams();
  const movieId = Number(id);
  const movie = useLiveQuery(() => movieRepo.get(movieId), [movieId]);
  const provider = useLiveQuery(async () => movie ? await providerRepo.get(movie.providerId) : undefined, [movie?.providerId]);
  const history = useLiveQuery(async () => {
    if (!movie) return undefined;
    return await historyRepo.byContent(movie.providerId, movieId);
  }, [movie, movieId]);
  const [playing, setPlaying] = useState(false);
  const [loadingInfo, setLoadingInfo] = useState(false);
  const [infoError, setInfoError] = useState<string | null>(null);

  // Trigger a one-shot info hydration if the plot is empty (proxy of "we never fetched")
  useEffect(() => {
    if (!movie) return;
    if (movie.plot) return; // already enriched
    let cancelled = false;
    (async () => {
      const provider = await providerRepo.get(movie.providerId);
      if (!provider) return;
      setLoadingInfo(true);
      try {
        const info = await xtream.vodInfo({
          serverUrl: provider.serverUrl,
          username: provider.username,
          password: provider.password,
          userAgent: provider.userAgent || null,
          referer: provider.httpReferer || null,
        }, movie.streamId);
        if (cancelled) return;
        const i = info.info ?? {};
        const enriched: Movie = {
          ...movie,
          posterUrl: i.cover_big || i.movie_image || movie.posterUrl,
          backdropUrl: i.backdrop_path?.[0] ?? movie.backdropUrl,
          plot: i.plot || i.description || movie.plot,
          cast: i.cast || i.actors || movie.cast,
          director: i.director || movie.director,
          genre: i.genre || movie.genre,
          releaseDate: i.releasedate || movie.releaseDate,
          year: i.releasedate ? i.releasedate.slice(0, 4) : movie.year,
          duration: i.duration || movie.duration,
          durationSeconds: i.duration_secs ?? movie.durationSeconds,
          rating: i.rating ? Number.parseFloat(i.rating) || movie.rating : movie.rating,
          youtubeTrailer: i.youtube_trailer || movie.youtubeTrailer,
          tmdbId: i.tmdb_id ? Number.parseInt(i.tmdb_id, 10) || null : movie.tmdbId,
        };
        await movieRepo.put(enriched);
      } catch (e) {
        if (!cancelled) setInfoError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setLoadingInfo(false);
      }
    })();
    return () => { cancelled = true; };
  }, [movie]);

  const stream: StreamInfo | null = useMemo(() => {
    if (!movie || !playing) return null;
    const url = provider ? resolveMovieUrl(provider, movie) : movie.streamUrl;
    return {
      url,
      title: movie.name,
      headers: {},
      userAgent: provider?.userAgent || null,
      streamType: streamTypeFromExtension(movie.containerExtension),
      containerExtension: movie.containerExtension,
      catchUpUrl: null,
      expirationTime: null,
      drmInfo: null,
    };
  }, [movie, playing, provider]);

  if (!movie) return <div className="empty">Loading…</div>;
  const resumeMs = history?.resumePositionMs ?? 0;

  return (
    <div>
      {playing ? (
        <div style={{ height: 600 }}>
          <PlayerWithControls
            stream={stream}
            tracking={{
              providerId: movie.providerId,
              contentId: movie.id,
              contentType: "MOVIE",
              title: movie.name,
              posterUrl: movie.posterUrl,
              streamUrl: movie.streamUrl,
            }}
            resumeMs={resumeMs}
          />
        </div>
      ) : (
        <div style={{ display: "flex", gap: 24 }}>
          <div style={{ width: 240, height: 360, flexShrink: 0, background: "var(--bg-elev)", borderRadius: 12, overflow: "hidden" }}>
            <ProxyImg src={movie.posterUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} fallback={<div style={{ display: "grid", placeItems: "center", height: "100%" }}>{movie.name.slice(0, 2)}</div>} />
          </div>
          <div style={{ flex: 1 }}>
            <h2 style={{ marginTop: 0 }}>{movie.name}</h2>
            <div style={{ color: "var(--fg-muted)", marginBottom: 12 }}>
              {[movie.year, movie.genre, movie.duration, movie.rating ? `★ ${movie.rating.toFixed(1)}` : null]
                .filter(Boolean).join(" · ")}
            </div>
            {loadingInfo && !movie.plot && <div className="banner">Loading details…</div>}
            {infoError && <div className="banner error">Could not load details: {infoError}</div>}
            {movie.plot && <p style={{ maxWidth: 720 }}>{movie.plot}</p>}
            {movie.cast && <p style={{ color: "var(--fg-muted)" }}><strong>Cast:</strong> {movie.cast}</p>}
            {movie.director && <p style={{ color: "var(--fg-muted)" }}><strong>Director:</strong> {movie.director}</p>}
            <div style={{ display: "flex", gap: 12, marginTop: 16, flexWrap: "wrap" }}>
              <button onClick={() => setPlaying(true)}>{resumeMs > 0 ? `Resume at ${Math.floor(resumeMs/60000)} min` : "Play"}</button>
              {resumeMs > 0 && <button onClick={() => { setPlaying(true); }}>Start over</button>}
              <FavoriteButton providerId={movie.providerId} contentId={movie.id} contentType="MOVIE" />
              <WatchlistButton providerId={movie.providerId} contentId={movie.id} contentType="MOVIE" />
              {movie.youtubeTrailer && (
                <a
                  className="filter-button"
                  href={`https://www.youtube.com/watch?v=${movie.youtubeTrailer}`}
                  target="_blank"
                  rel="noreferrer"
                >▶ Trailer</a>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
