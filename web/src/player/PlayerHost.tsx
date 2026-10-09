// Lecteur unique de l'application : un seul <video> et un seul moteur, donc UNE connexion au fournisseur
// (la plupart des abonnements n'en autorisent qu'une). Il s'affiche soit dans l'aperçu du Direct
// (mode inline, calé sur un emplacement), soit en plein écran (mode full) avec la surcouche de la maquette.

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { channelsCol } from "@/db/queries";
import { Img } from "@/ui/Img";
import { VList, arrayRows, type VListHandle } from "@/ui/Virtual";
import { db } from "@/db/db";
import type { ChannelRow } from "@/db/types";
import { histKey } from "@/db/queries";
import { useNowNext } from "@/hooks/epg";
import { useT } from "@/i18n";
import { fmtClock } from "@/lib/text";
import { bridge, transportSync } from "@/net/transport";
import { usePrefs } from "@/state/prefs";
import { useSources } from "@/state/sources";
import { hhmm, QBadge } from "@/ui/common";
import { Icon } from "@/ui/Icon";
import { parseEpisodes } from "@/screens/Detail";
import type { SeriesInfo } from "@/net/xtream";
import { LiveReconnect } from "./reconnect";
import { effectivePreferred } from "./trackChoice";
import { PlayerEngine, type PlayState, type Stats, type Tracks } from "./engine";
import { candidates, type Candidate } from "./resolve";
import { AudioWatcher, nextAudioFallback, readAudioSample, revertToSilent } from "./audio";
import { usePlayer, ZAP_DEBOUNCE_MS } from "./store";
import { startScrobble, type Scrobbler } from "./trakt";

const NO_TRACKS: Tracks = { audio: [], activeAudio: -1, text: [], activeText: -1, levels: [], activeLevel: -1 };

async function setOsFullscreen(on: boolean) {
  const b = bridge();
  try {
    if (b?.setFullscreen) await b.setFullscreen(on);
    else if (on && !document.fullscreenElement) await document.documentElement.requestFullscreen();
    else if (!on && document.fullscreenElement) await document.exitFullscreen();
  } catch { /* refusé */ }
}
async function isOsFullscreen(): Promise<boolean> {
  const b = bridge();
  if (b?.isFullscreen) return !!(await b.isFullscreen());
  return !!document.fullscreenElement;
}

export function PlayerHost() {
  const t = useT();
  // Sélecteurs ciblés : s'abonner au store entier re-rendait tout le lecteur à chaque mise à jour (ex. position de l'aperçu).
  const target = usePlayer((s) => s.target);
  const mode = usePlayer((s) => s.mode);
  const slot = usePlayer((s) => s.slot);
  const nonce = usePlayer((s) => s.nonce);
  const zapCat = usePlayer((s) => s.zapCat);
  const prefs = usePrefs();
  const sources = useSources((s) => s.list);
  const source = sources.find((s) => s.id === target?.sourceId);

  const wrapRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const engineRef = useRef<PlayerEngine | null>(null);
  const candRef = useRef<{ list: Candidate[]; i: number }>({ list: [], i: 0 });
  const modeRef = useRef(mode);
  modeRef.current = mode;

  const [state, setState] = useState<PlayState>("idle");
  const [error, setError] = useState(false);
  // Image sans son (AC-3/DTS non décodables) : message non bloquant, jamais l'écran d'erreur.
  const [noAudio, setNoAudio] = useState(false);
  const silentIdxRef = useRef<number | null>(null);
  const skipProbeRef = useRef(false);
  const resumeAtRef = useRef<number | null>(null);
  const [tracks, setTracks] = useState<Tracks>(NO_TRACKS);
  const [stats, setStats] = useState<Stats | null>(null);
  const [ui, setUi] = useState(true);
  const [panel, setPanel] = useState<null | "tracks" | "display" | "player" | "stats" | "channels">(null);
  const [cur, setCur] = useState({ t: 0, d: 0, buf: 0 });
  const [clock, setClock] = useState(() => new Date());
  const [osFull, setOsFull] = useState(false);
  const [zapFlash, setZapFlash] = useState<string | null>(null);
  const [rate, setRate] = useState(1);
  const hideTimer = useRef<number>();
  const live = target?.kind === "live" && !target.replay;
  const liveRef = useRef(live);
  liveRef.current = live;
  const [reconnecting, setReconnecting] = useState(false);
  // Direct coupé (session fermée par le serveur, réseau, flux figé) : on rouvre la même adresse tout seul.
  const reconRef = useRef<LiveReconnect | null>(null);

  // Scrobble Trakt du média en cours (null : direct, rediffusion, non appairé).
  const scrobRef = useRef<Scrobbler | null>(null);

  // --- moteur ---
  useEffect(() => {
    const v = videoRef.current!;
    const recon = new LiveReconnect({
      reload: () => { const c = candRef.current; const a = c.list[c.i]; if (a) void startCandidate(a); },
      giveUp: () => { setReconnecting(false); setError(true); setState("error"); },
      onReconnecting: () => setReconnecting(true),
    });
    reconRef.current = recon;
    const eng = new PlayerEngine(v, transportSync, {
      onState: (s) => { setState(s); scrobRef.current?.onState(s, v.currentTime, v.duration); if (s === "playing") { setError(false); setReconnecting(false); } recon.onState(s, liveRef.current); },
      onError: () => {
        if (recon.onError(liveRef.current)) return;
        const c = candRef.current;
        if (c.i + 1 < c.list.length) {
          c.i += 1;
          const a = c.list[c.i]!;
          void startCandidate(a);
        } else {
          // Les replis ont échoué après un candidat qui jouait sans son : mieux vaut l'image sans son que l'erreur.
          const back = revertToSilent(silentIdxRef.current, c.list.length);
          if (back >= 0) { c.i = back; skipProbeRef.current = true; setNoAudio(true); void startCandidate(c.list[back]!); return; }
          setError(true); setState("error");
        }
      },
      onTracks: setTracks,
    });
    engineRef.current = eng;
    eng.uiLang = usePrefs.getState().lang;
    eng.preferredAudio = effectivePreferred([], usePrefs.getState().lang);
    v.volume = usePrefs.getState().volume;
    v.muted = usePrefs.getState().muted;
    return () => { recon.dispose(); eng.stop(); engineRef.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startCandidate = useCallback(async (c: Candidate) => {
    const tg = usePlayer.getState().target;
    const src = useSources.getState().list.find((s) => s.id === tg?.sourceId);
    if (!tg || !src || !engineRef.current) return;
    setError(false);
    const resume = resumeAtRef.current;
    resumeAtRef.current = null;
    await engineRef.current.load({
      url: c.url, format: c.format, live: tg.kind === "live" && !tg.replay,
      userAgent: src.userAgent || null, referer: src.referer || null, startAt: resume ?? tg.startAt,
    });
  }, []);

  useEffect(() => {
    reconRef.current?.reset();
    setReconnecting(false);
    if (!target || !source) { engineRef.current?.stop(); return; }
    setTracks(NO_TRACKS);
    setPanel(null);
    setCur({ t: 0, d: 0, buf: 0 });
    const list = candidates(source, target, { liveFormat: prefs.liveFormat, preferMp4: prefs.preferMp4 });
    candRef.current = { list, i: 0 };
    silentIdxRef.current = null; skipProbeRef.current = false; resumeAtRef.current = null;
    setNoAudio(false);
    if (!list[0]) { setError(true); setState("error"); return; }
    if (usePlayer.getState().zapNonce === nonce) {
      // Zap : on coupe l'ancien flux tout de suite (libère la connexion du fournisseur) et on n'ouvre le nouveau
      // qu'après une courte pause ; un zap suivant annule celui-ci (cleanup) au lieu d'empiler des connexions.
      engineRef.current?.stop();
      setState("loading");
      const to = setTimeout(() => void startCandidate(list[0]!), ZAP_DEBOUNCE_MS);
      return () => clearTimeout(to);
    }
    void startCandidate(list[0]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.refId, target?.kind, target?.replay?.start, nonce, source?.id]);

  // Sonde audio (VOD) : image qui défile mais aucun octet audio décodé -> candidat suivant, à la même position.
  useEffect(() => {
    if (state !== "playing" || !target || target.kind === "live" || skipProbeRef.current) return;
    const v = videoRef.current;
    if (!v) return;
    const w = new AudioWatcher();
    const iv = setInterval(() => {
      const verdict = w.tick(readAudioSample(v));
      if (verdict === "wait") return;
      clearInterval(iv);
      if (verdict !== "silent") return;
      const c = candRef.current;
      if (silentIdxRef.current === null) silentIdxRef.current = c.i;
      const nx = nextAudioFallback(c.list.length, c.i);
      if (nx < 0) { skipProbeRef.current = true; setNoAudio(true); return; }
      c.i = nx;
      resumeAtRef.current = v.currentTime > 1 ? v.currentTime : null;
      void startCandidate(c.list[nx]!);
    }, 1000);
    return () => clearInterval(iv);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, target?.refId, target?.kind]);

  // Scrobble : une session par film / épisode ; la fermeture (changement de cible, fermeture du lecteur) envoie « stop ».
  useEffect(() => {
    if (!target || !source) return;
    const sc = startScrobble(target, source);
    scrobRef.current = sc;
    const iv = sc ? setInterval(() => { const v = videoRef.current; if (v) sc.note(v.currentTime, v.duration); }, 10_000) : undefined;
    return () => {
      clearInterval(iv);
      const v = videoRef.current;
      sc?.close(v?.currentTime ?? 0, v?.duration ?? NaN);
      if (scrobRef.current === sc) scrobRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.refId, target?.kind, target?.replay?.start, source?.id]);

  const saveHistory = useCallback(() => {
    const tg = usePlayer.getState().target;
    const v = videoRef.current;
    if (!tg || !v || tg.kind === "live" || !Number.isFinite(v.duration) || v.duration < 30) return;
    const { profileId } = usePrefs.getState();
    const done = v.currentTime / v.duration > 0.95;
    const key = histKey(profileId, tg.sourceId, tg.kind === "movie" ? "movie" : "series", tg.kind === "movie" ? tg.refId : tg.seriesId ?? tg.refId) + (tg.kind === "episode" ? `:${tg.refId}` : "");
    if (done) { void db.history.delete(key); return; }
    if (v.currentTime < 20) return;
    void db.history.put({
      key, profile: profileId, sourceId: tg.sourceId, kind: tg.kind === "movie" ? "movie" : "series", refId: tg.kind === "movie" ? tg.refId : tg.seriesId ?? tg.refId,
      title: tg.title, image: tg.image ?? null, pos: v.currentTime, dur: v.duration, updatedAt: Date.now(),
      ext: tg.ext, seriesId: tg.seriesId, season: tg.season, episode: tg.episode,
      epId: tg.kind === "episode" ? tg.refId : undefined,
    });
  }, []);
  useEffect(() => {
    if (!target || target.kind === "live") return;
    const iv = setInterval(saveHistory, 10_000);
    return () => { clearInterval(iv); saveHistory(); };
  }, [target?.refId, target?.kind, saveHistory]);

  // --- épisode suivant automatique ---
  useEffect(() => {
    if (state !== "ended" || !target || target.kind !== "episode" || !prefs.autoplayNext || !source) return;
    let dead = false;
    void db.details.get(`series:${source.id}:${target.seriesId}`).then((row) => {
      if (dead || !row) return;
      const all = parseEpisodes(row.json as SeriesInfo).seasons.flatMap((x) => x.eps);
      const k = all.findIndex((e) => e.id === target.refId);
      const nx = all[k + 1];
      if (nx) usePlayer.getState().open({ ...target, refId: nx.id, ext: nx.ext, season: nx.season, episode: nx.num, startAt: 0,
        subtitle: `${t("common.season", { n: nx.season })} · ${t("common.episodeShort", { n: nx.num })} ${nx.title}`.trim() }, "full");
    });
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  // --- surcouche : apparition au mouvement, masquage après 3 s ---
  const poke = useCallback(() => {
    setUi(true);
    window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => setUi(false), 3200);
  }, []);
  useEffect(() => { poke(); }, [mode, target?.refId, poke]);
  const showUi = ui || state !== "playing" || panel !== null;

  // --- progression et statistiques : seulement en plein écran avec l'interface visible (rien à afficher sinon) ---
  const tickOn = mode === "full" && showUi;
  useEffect(() => {
    if (!tickOn) return;
    const v = videoRef.current!;
    const tick = () => {
      let buf = 0;
      for (let i = 0; i < v.buffered.length; i++) if (v.currentTime >= v.buffered.start(i) && v.currentTime <= v.buffered.end(i)) buf = v.buffered.end(i);
      const d = Number.isFinite(v.duration) ? v.duration : 0;
      // Même valeur : on garde l'objet, pas de re-rendu.
      setCur((p) => (Math.abs(p.t - v.currentTime) < 0.25 && p.d === d && Math.abs(p.buf - buf) < 0.5 ? p : { t: v.currentTime, d, buf }));
      if (panel === "stats") setStats(engineRef.current?.stats() ?? null);
    };
    tick();
    const iv = setInterval(tick, 500);
    return () => clearInterval(iv);
  }, [tickOn, panel]);

  // --- horloge : l'affichage est à la minute, on ne se réveille qu'au changement de minute ---
  useEffect(() => {
    if (mode !== "full") return;
    let to: ReturnType<typeof setTimeout>;
    const arm = () => {
      const now = new Date();
      setClock(now);
      to = setTimeout(arm, 60_000 - (now.getTime() % 60_000) + 50);
    };
    arm();
    return () => clearTimeout(to);
  }, [mode]);

  // --- plein écran ---
  useEffect(() => {
    const b = bridge();
    const onFs = () => setOsFull(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", onFs);
    const off = b?.onFullscreenChange?.((v) => setOsFull(v));
    void isOsFullscreen().then(setOsFull);
    return () => { document.removeEventListener("fullscreenchange", onFs); if (typeof off === "function") off(); };
  }, []);

  const goFull = useCallback(() => usePlayer.getState().setMode("full"), []);
  const leaveFull = useCallback(async () => {
    if (await isOsFullscreen()) await setOsFullscreen(false);
    const s = usePlayer.getState();
    if (s.slot) s.setMode("inline"); else s.close();
  }, []);
  const toggleOsFull = useCallback(async () => {
    if (modeRef.current !== "full") usePlayer.getState().setMode("full");
    await setOsFullscreen(!(await isOsFullscreen()));
  }, []);

  const togglePlay = useCallback(() => {
    const v = videoRef.current!;
    if (v.paused) void v.play().catch(() => undefined); else v.pause();
  }, []);
  const seekBy = useCallback((s: number) => {
    const v = videoRef.current!;
    if (Number.isFinite(v.duration)) v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + s));
  }, []);
  const setVol = useCallback((d: number) => {
    const v = videoRef.current!;
    v.volume = Math.max(0, Math.min(1, v.volume + d));
    v.muted = false;
    usePrefs.getState().set({ volume: v.volume, muted: false });
    setZapFlash(`${Math.round(v.volume * 100)} %`);
    window.setTimeout(() => setZapFlash(null), 900);
  }, []);
  const toggleMute = useCallback(() => {
    const v = videoRef.current!;
    v.muted = !v.muted;
    usePrefs.getState().set({ muted: v.muted });
  }, []);
  const pip = useCallback(async () => {
    const v = videoRef.current as HTMLVideoElement & { requestPictureInPicture?: () => Promise<unknown> };
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else await v.requestPictureInPicture?.();
    } catch { /* non pris en charge */ }
  }, []);
  const zap = useCallback(async (d: 1 | -1) => {
    await usePlayer.getState().zap(d);
    const tg = usePlayer.getState().target;
    if (tg) { setZapFlash(`${tg.channel?.num ?? ""} ${tg.title}`); window.setTimeout(() => setZapFlash(null), 1400); }
  }, []);

  // --- raccourcis (actifs en plein écran, ou en aperçu quand la lecture est lancée) ---
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const m = modeRef.current;
      const tg = usePlayer.getState().target;
      if (m === "closed" || !tg) return;
      const el = e.target as HTMLElement | null;
      // Recherche (Ctrl/⌘ + K ou « / ») : on quitte le plein écran ; l'application navigue vers la recherche.
      if (m === "full" && (((e.key === "k" || e.key === "K") && (e.metaKey || e.ctrlKey)) || (e.key === "/" && !e.metaKey && !e.ctrlKey && !(el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA"))))) { void leaveFull(); return; }
      if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable)) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      const isLive = tg.kind === "live" && !tg.replay;
      const full = m === "full";
      switch (e.key) {
        case "Escape":
          if (full) { e.preventDefault(); e.stopPropagation(); if (panelRef.current) setPanel(null); else void leaveFull(); }
          break;
        case "f": case "F": e.preventDefault(); void toggleOsFull(); break;
        case "m": case "M": if (full) toggleMute(); break;
        case "p": case "P": if (full) void pip(); break;
        case "i": case "I": if (full) setPanel((p) => (p === "stats" ? null : "stats")); break;
        case " ": if (full) { e.preventDefault(); togglePlay(); poke(); } break;
        case "ArrowLeft": if (full && !isLive) { e.preventDefault(); seekBy(-10); poke(); } break;
        case "ArrowRight": if (full && !isLive) { e.preventDefault(); seekBy(10); poke(); } break;
        case "ArrowUp": if (full) { e.preventDefault(); if (isLive) void zap(-1); else setVol(0.05); poke(); } break;
        case "ArrowDown": if (full) { e.preventDefault(); if (isLive) void zap(1); else setVol(-0.05); poke(); } break;
        case "PageUp": if (full && isLive) void zap(-1); break;
        case "PageDown": if (full && isLive) void zap(1); break;
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [leaveFull, toggleOsFull, toggleMute, pip, togglePlay, seekBy, setVol, zap, poke]);
  const panelRef = useRef(panel);
  panelRef.current = panel;

  const nn = useNowNext(source, target?.channel?.epg, target?.kind === "live" ? target.refId : undefined);

  if (!target || mode === "closed") {
    return <div className="player hidden"><video ref={videoRef} playsInline /></div>;
  }

  const full = mode === "full";
  const style = !full && slot ? { left: slot.x, top: slot.y, width: slot.w, height: slot.h } : undefined;
  if (!full && !slot) return <div className="player hidden"><video ref={videoRef} playsInline /></div>;

  const prog = nn.now;
  const progRatio = prog ? Math.min(1, Math.max(0, (Date.now() - prog.start) / (prog.end - prog.start))) : 0;
  const vodRatio = cur.d ? cur.t / cur.d : 0;
  const onSeekBar = (e: React.MouseEvent<HTMLDivElement>) => {
    if (live || !cur.d) return;
    const r = e.currentTarget.getBoundingClientRect();
    const f = (e.clientX - r.left) / r.width;
    videoRef.current!.currentTime = (document.dir === "rtl" ? 1 - f : f) * cur.d;
  };
  const locale = prefs.lang;
  const title = live ? (prog?.title || target.title) : target.title;
  const subtitle = live ? `${target.channel?.num ?? ""} · ${target.title}`.replace(/^ · /, "") : target.subtitle;

  return (
    <div
      ref={wrapRef}
      className={`player ${full ? "full" : "inline"}${showUi ? " ui" : ""}${!showUi && full ? " nocursor" : ""}`}
      style={style}
      onMouseMove={poke}
      onDoubleClick={(e) => { if ((e.target as HTMLElement).closest("button,.seek,.pside")) return; if (full) void toggleOsFull(); else goFull(); }}
      onClick={(e) => { if ((e.target as HTMLElement).tagName === "VIDEO") { if (full) togglePlay(); else goFull(); } }}
    >
      <video ref={videoRef} playsInline className={prefs.fit === "cover" ? "fit-cover" : prefs.fit === "fill" ? "fit-fill" : ""} />
      <div className="shade-top" /><div className="shade-bot" />

      {(state === "loading" || state === "buffering") && !error && (
        <div className="pstate">
          <div className="spinner" role="status" aria-label={t(reconnecting ? "player.reconnecting" : "player.buffering")} />
          {reconnecting && <p className="reconnecting">{t("player.reconnecting")}</p>}
        </div>
      )}
      {error && (
        <div className="pstate">
          <div className="msg">
            <b>{t("player.error")}</b>
            <p style={{ margin: "0.5rem 0 1rem", color: "#c4c4cc", fontSize: "0.875rem" }}>{t("player.errorHint")}</p>
            <button className="btn primary sm" onClick={() => { candRef.current.i = 0; usePlayer.getState().reload(); }}>{t("player.retry")}</button>
          </div>
        </div>
      )}
      {noAudio && !error && <div className="noaudio" role="status">{t("player.noAudio")}</div>}
      {zapFlash && <div className="zap">{zapFlash}</div>}

      <div className="ptop">
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", fontSize: "0.8125rem", fontWeight: 700 }}>
            {live && <span className="badge-live">{t("common.live")}</span>}
            <span style={{ color: "#c4c4cc" }}>{subtitle}</span>
            {target.channel && <QBadge q={target.channel.q} />}
          </div>
          <h2>{title}</h2>
        </div>
        {full && <div className="clock mono">{hhmm(clock.getTime(), locale)}</div>}
      </div>

      <div className="pbot">
        {full && (live ? prog : cur.d > 0) && (
          <div className="seek mono">
            <span>{live ? hhmm(prog!.start, locale) : fmtClock(cur.t)}</span>
            <div className="bar" onClick={onSeekBar} role="slider" aria-label={t("a11y.position")} aria-valuenow={Math.round((live ? progRatio : vodRatio) * 100)}>
              {!live && cur.d > 0 && <div className="buf" style={{ width: `${(cur.buf / cur.d) * 100}%` }} />}
              <div className="fill" style={{ width: `${(live ? progRatio : vodRatio) * 100}%` }} />
              <div className="knob" style={{ insetInlineStart: `${(live ? progRatio : vodRatio) * 100}%` }} />
            </div>
            <span>{live ? hhmm(prog!.end, locale) : fmtClock(cur.d)}</span>
          </div>
        )}
        <div className="pctl">
          <div className="grp">
            {full && !live && <button className="pbtn" aria-label={t("player.back10")} onClick={() => seekBy(-10)}><Icon name="rew" size={22} /></button>}
            <button className={full ? "pbtn big" : "pbtn"} aria-label={t("player.pause")} onClick={togglePlay}>
              <Icon name={state === "paused" ? "play" : "pause"} size={full ? 26 : 20} fill />
            </button>
            {full && !live && <button className="pbtn" aria-label={t("player.fwd10")} onClick={() => seekBy(10)}><Icon name="fwd" size={22} /></button>}
            <button className="pbtn" aria-label={t("a11y.volume")} onClick={toggleMute}><Icon name={videoRef.current?.muted ? "mute" : "volume"} size={20} /></button>
          </div>
          <div className="grp">
            {full ? (
              <>
                <button className={`popt${panel === "tracks" ? " on" : ""}`} onClick={() => setPanel(panel === "tracks" ? null : "tracks")}><Icon name="sliders" size={18} />{t("player.tracks")}</button>
                <button className={`popt${panel === "display" ? " on" : ""}`} onClick={() => setPanel(panel === "display" ? null : "display")}><Icon name="display" size={18} />{t("player.display")}</button>
                {live && <button className={`popt${panel === "channels" ? " on" : ""}`} onClick={() => setPanel(panel === "channels" ? null : "channels")}><Icon name="list" size={18} />{t("player.channels")}</button>}
                <button className={`popt${panel === "player" || panel === "stats" ? " on" : ""}`} onClick={() => setPanel(panel === "player" ? null : "player")}><Icon name="info" size={18} />{t("player.player")}</button>
                <button className="pbtn" aria-label={t("player.pip")} onClick={() => void pip()}><Icon name="pip" size={20} /></button>
                <button className="pbtn" aria-label={t("player.fullscreen")} onClick={() => void toggleOsFull()}><Icon name="full" size={20} /></button>
                <button className="pbtn" aria-label={t("common.close")} onClick={() => void leaveFull()}><Icon name="x" size={20} /></button>
              </>
            ) : (
              <button className="pbtn" aria-label={t("player.fullscreen")} onClick={goFull}><Icon name="full" size={20} /></button>
            )}
          </div>
        </div>
      </div>

      {full && panel && panel !== "channels" && (
        <aside className="pside" aria-label={t("player.player")}>
          <div className="tabs" role="tablist">
            {(["tracks", "display", "player", "stats"] as const).map((p) => (
              <button key={p} role="tab" aria-selected={panel === p} onClick={() => setPanel(p)}>{t(`player.${p}` as "player.tracks")}</button>
            ))}
          </div>
          {panel === "tracks" && (
            <>
              <div className="grp-t">{t("player.quality")}</div>
              {tracks.levels.length === 0 ? <div className="muted">{t("player.noTracks")}</div> : (
                <>
                  <button className="opt" role="radio" aria-checked={tracks.activeLevel === -1} onClick={() => engineRef.current?.setLevel(-1)}>{t("player.auto")}</button>
                  {tracks.levels.map((l) => <button key={l.id} className="opt" role="radio" aria-checked={tracks.activeLevel === l.id} onClick={() => engineRef.current?.setLevel(l.id)}>{l.label}</button>)}
                </>
              )}
              {tracks.audio.length > 1 && (
                <>
                  <div className="grp-t">{t("player.audio")}</div>
                  {tracks.audio.map((a) => <button key={a.id} className="opt" role="radio" aria-checked={tracks.activeAudio === a.id} onClick={() => engineRef.current?.setAudio(a.id)}>{a.label}<span className="h">{a.lang}</span></button>)}
                </>
              )}
              {tracks.text.length > 0 && (
                <>
                  <div className="grp-t">{t("player.subtitles")}</div>
                  <button className="opt" role="radio" aria-checked={tracks.activeText === -1} onClick={() => engineRef.current?.setText(-1)}>{t("player.off")}</button>
                  {tracks.text.map((a) => <button key={a.id} className="opt" role="radio" aria-checked={tracks.activeText === a.id} onClick={() => engineRef.current?.setText(a.id)}>{a.label}</button>)}
                </>
              )}
            </>
          )}
          {panel === "display" && (
            <>
              <div className="grp-t">{t("player.fit")}</div>
              {(["contain", "cover", "fill"] as const).map((f) => (
                <button key={f} className="opt" role="radio" aria-checked={prefs.fit === f} onClick={() => prefs.set({ fit: f })}>
                  {t(f === "contain" ? "player.fitContain" : f === "cover" ? "player.fitCover" : "player.fitFill")}
                </button>
              ))}
              {!live && (
                <>
                  <div className="grp-t">{t("player.speed")}</div>
                  {[0.75, 1, 1.25, 1.5, 2].map((r) => (
                    <button key={r} className="opt" role="radio" aria-checked={rate === r} onClick={() => { videoRef.current!.playbackRate = r; setRate(r); }}>{r}×</button>
                  ))}
                </>
              )}
            </>
          )}
          {panel === "player" && (
            <>
              <div className="grp-t">{t("player.shortcuts")}</div>
              <div className="hotkeys">
                <span><span className="kbd">{t("key.space")}</span></span><span>{t("player.sk.space")}</span>
                <span><span className="kbd">←</span> <span className="kbd">→</span></span><span>{t("player.sk.arrows")}</span>
                <span><span className="kbd">↑</span> <span className="kbd">↓</span></span><span>{live ? t("player.sk.zap") : t("player.sk.vol")}</span>
                <span><span className="kbd">F</span></span><span>{t("player.sk.full")}</span>
                <span><span className="kbd">P</span></span><span>{t("player.sk.pip")}</span>
                <span><span className="kbd">M</span></span><span>{t("player.sk.mute")}</span>
                <span><span className="kbd">I</span></span><span>{t("player.stats")}</span>
                <span><span className="kbd">{t("key.esc")}</span></span><span>{t("player.sk.esc")}</span>
              </div>
              <div className="grp-t">{t("player.stats")}</div>
              <button className="opt" onClick={() => setPanel("stats")}>{t("player.stats")}<Icon name="chevron" size={16} /></button>
            </>
          )}
          {panel === "stats" && stats && (
            <>
              <div className="grp-t">{t("player.stats").toUpperCase()}</div>
              <div className="stat"><span>{t("player.stat.engine")}</span><b>{stats.engine}</b></div>
              <div className="stat"><span>{t("player.stat.res")}</span><b>{stats.width && stats.height ? `${stats.width}×${stats.height}` : "—"}</b></div>
              <div className="stat"><span>{t("player.stat.bitrate")}</span><b>{stats.bitrate ? `${(stats.bitrate / 1e6).toFixed(1)} Mb/s` : "—"}</b></div>
              <div className="stat"><span>{t("player.stat.buffer")}</span><b>{stats.buffer.toFixed(1)} s</b></div>
              <div className="stat"><span>{t("player.stat.dropped")}</span><b>{stats.dropped}</b></div>
              {stats.codec && <div className="stat"><span>{t("player.stat.codec")}</span><b>{stats.codec}</b></div>}
            </>
          )}
        </aside>
      )}
      {full && panel === "channels" && live && <ChannelsPanel onZap={() => setPanel(null)} cid={target.cid} cat={zapCat} />}
    </div>
  );
}

/** Ligne de la liste « Chaînes » : mémoïsée, seule la chaîne courante change d'état au zapping. */
const ChannelRowBtn = memo(function ChannelRowBtn({ c, on, onPick }: { c: ChannelRow; on: boolean; onPick: (c: ChannelRow) => void }) {
  return (
    <button className="opt ch" role="radio" aria-checked={on} data-cur={on ? "1" : undefined} onClick={() => onPick(c)}>
      <span className="logo" aria-hidden="true"><Img src={c.logo} contain /></span>
      <span className="num">{c.num}</span>
      <span className="ellipsis nm">{c.display}</span>
    </button>
  );
});

function ChannelsPanel({ cid, cat, onZap }: { cid: number; cat: string | null; onZap: () => void }) {
  const t = useT();
  const [rows, setRows] = useState<ChannelRow[]>([]);
  const cur = usePlayer((s) => s.target);
  const list = useRef<VListHandle>(null);
  const curId = cur?.refId;
  // Toute la catégorie (plafond large) : la chaîne regardée peut être loin du début (ex. n° 10965).
  // Catégorie de la chaîne en cours si aucune n'a été choisie (lancée depuis l'accueil, la recherche…).
  const effCat = cat ?? cur?.channel?.catExt ?? null;
  const [catLabel, setCatLabel] = useState<string | null>(null);
  useEffect(() => { void channelsCol(cid, effCat).filter((c) => !c.sep).limit(5000).toArray().then(setRows); }, [cid, effCat]);
  useEffect(() => {
    if (effCat == null) { setCatLabel(null); return; }
    void db.categories.where("[sourceId+kind]").equals([cid, "live"]).filter((c) => c.extId === effCat).first().then((c) => setCatLabel(c?.label ?? null));
  }, [cid, effCat]);
  // Liste virtualisée : seules les lignes visibles existent dans le DOM (jusqu'à 5000 chaînes).
  const arr = useMemo(() => arrayRows(rows), [rows]);
  const pick = useCallback((c: ChannelRow) => {
    const tg = usePlayer.getState().target;
    if (!tg) return;
    usePlayer.getState().open({
      ...tg, refId: c.streamId, title: c.display, image: c.logo, url: c.url, replay: undefined,
      channel: { ord: c.ord, catExt: c.catExt, num: c.num, epg: c.epg, archive: !!c.archive, q: c.q, logo: c.logo },
    }, "full");
    onZap();
  }, [onZap]);
  // À l'ouverture : centré sur la chaîne regardée, qui a le focus (Entrée = rester, ↑↓ = parcourir).
  const centered = useRef(false);
  useEffect(() => {
    if (centered.current || rows.length === 0) return;
    const k = rows.findIndex((c) => c.streamId === curId);
    if (k < 0) return;
    centered.current = true;
    list.current?.scrollTo(k, "center");
    requestAnimationFrame(() => requestAnimationFrame(() => {
      list.current?.el?.querySelector<HTMLButtonElement>("button[data-cur]")?.focus({ preventScroll: true });
    }));
  }, [rows, curId]);
  return (
    <aside className="pside pch" aria-label={t("player.channels")}>
      <div className="grp-t">{(catLabel ?? t("player.channels")).toUpperCase()} · {rows.length}</div>
      <VList ref={list} rows={arr} rowH={58} className="vlist pch-list" label={t("player.channels")}
        render={(c) => (c ? <ChannelRowBtn c={c} on={curId === c.streamId} onPick={pick} /> : null)} />
    </aside>
  );
}
