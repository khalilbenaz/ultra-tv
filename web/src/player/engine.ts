// Moteur de lecture : HLS (hls.js), MPEG-TS (mpegts.js) et lecture native (MP4/MKV).
// Tout passe par le transport (proxy loopback d'Electron, proxy CORS du navigateur, ou direct).
// Les URL de flux ne sont jamais journalisées ni affichées.

import type Hls from "hls.js";
import type { Level } from "hls.js";
import type MpegtsNs from "mpegts.js";
import { bestTrackIndex, trackLabel } from "./trackChoice";
import { requestHeaders, wrapUrl, type Transport } from "@/net/transport";

export type PlayState = "idle" | "loading" | "playing" | "paused" | "buffering" | "ended" | "error";
export type Format = "hls" | "ts" | "native";

export interface LoadArgs {
  url: string;
  format: Format;
  live: boolean;
  userAgent?: string | null;
  referer?: string | null;
  startAt?: number;
}

export interface TrackInfo { id: number; label: string; lang?: string }
export interface Tracks {
  audio: TrackInfo[]; activeAudio: number;
  text: TrackInfo[]; activeText: number;
  levels: { id: number; label: string }[]; activeLevel: number;
}
export interface Stats { engine: string; width: number; height: number; bitrate: number; buffer: number; dropped: number; codec: string }

export interface EngineEvents {
  onState(s: PlayState): void;
  onError(code: string): void;
  onTracks(t: Tracks): void;
}

/** Heuristique de format d'après l'extension de l'URL. */
export function formatFromUrl(url: string): Format {
  const clean = url.split("?")[0]!.toLowerCase();
  if (clean.endsWith(".m3u8")) return "hls";
  if (clean.endsWith(".ts")) return "ts";
  return "native";
}

export class PlayerEngine {
  private hls: Hls | null = null;
  private mpegts: ReturnType<typeof MpegtsNs.createPlayer> | null = null;
  private engineName = "—";
  private codec = "";
  private token = 0;
  private lastLevel = -1;
  /** Récupérations d'erreur média hls.js récentes (au-delà, on passe au format suivant au lieu de boucler). */
  private mediaRecoveries: number[] = [];
  /** Écouteur de reprise (startAt) du lecteur natif : retiré au changement de média. */
  private pendingSeek: (() => void) | null = null;
  /** Langues préférées (ISO 639-1, ordonnées) et langue de l'interface ; choix manuel = plus d'automatisme sur ce média. */
  preferredAudio: string[] = [];
  uiLang = "en";
  private audioManual = false;

  constructor(private video: HTMLVideoElement, private getTransport: () => Transport, private ev: EngineEvents) {
    const v = video;
    v.addEventListener("playing", () => this.ev.onState("playing"));
    v.addEventListener("pause", () => { if (!v.ended) this.ev.onState("paused"); });
    v.addEventListener("waiting", () => this.ev.onState("buffering"));
    v.addEventListener("ended", () => this.ev.onState("ended"));
    v.addEventListener("error", () => this.ev.onError(`media:${v.error?.code ?? 0}`));
    v.addEventListener("loadedmetadata", () => this.emitTracks());
    // Lecteur natif (MKV/MP4) : pistes audio exposées par le navigateur quand il les prend en charge (Safari, Chromium à drapeau).
    const nat = (v as unknown as { audioTracks?: EventTarget & ArrayLike<{ language: string; label: string; enabled: boolean }> }).audioTracks;
    for (const k of ["addtrack", "removetrack", "change"]) nat?.addEventListener?.(k, () => this.emitTracks());
  }

  async load(a: LoadArgs): Promise<void> {
    const my = ++this.token;
    this.audioManual = false;
    this.teardown();
    this.ev.onState("loading");
    const t = this.getTransport();
    const headers = requestHeaders(t, { userAgent: a.userAgent, referer: a.referer });
    try {
      if (a.format === "hls") await this.loadHls(a, t, headers, my);
      else if (a.format === "ts") await this.loadTs(a, t, headers, my);
      else this.loadNative(a, t);
    } catch {
      if (my === this.token) this.ev.onError("load");
    }
  }

  private loadNative(a: LoadArgs, t: Transport) {
    this.engineName = "HTML5";
    const v = this.video;
    v.src = wrapUrl(t, a.url);
    if (a.startAt && a.startAt > 1) {
      const seek = () => { v.currentTime = a.startAt!; v.removeEventListener("loadedmetadata", seek); this.pendingSeek = null; };
      this.pendingSeek = seek;
      v.addEventListener("loadedmetadata", seek);
    }
    void v.play().catch(() => undefined);
  }

  private async loadHls(a: LoadArgs, t: Transport, headers: Record<string, string>, my: number) {
    const mod = await import("hls.js");
    if (my !== this.token) return;
    const HlsCtor = mod.default;
    if (!HlsCtor.isSupported()) { this.loadNative({ ...a, format: "native" }, t); return; }
    const BaseLoader = HlsCtor.DefaultConfig.loader;
    const wrap = (u: string) => (t.mode === "electron" && u.startsWith(t.base) ? u : wrapUrl(t, u));
    class ProxyLoader extends BaseLoader {
      override load(context: { url: string }, config: unknown, callbacks: unknown) {
        context.url = wrap(context.url);
        super.load(context as never, config as never, callbacks as never);
      }
    }
    const hls = new HlsCtor({
      enableWorker: true,
      lowLatencyMode: false,
      backBufferLength: a.live ? 30 : 90,
      maxBufferLength: a.live ? 20 : 40,
      liveSyncDurationCount: 3,
      manifestLoadingTimeOut: 20000,
      manifestLoadingMaxRetry: 2,
      fragLoadingMaxRetry: 4,
      startPosition: a.startAt && a.startAt > 1 ? a.startAt : -1,
      loader: ProxyLoader as never,
      xhrSetup: (xhr) => { for (const [k, val] of Object.entries(headers)) { try { xhr.setRequestHeader(k, val); } catch { /* interdit */ } } },
    });
    this.engineName = "hls.js";
    hls.on(HlsCtor.Events.MANIFEST_PARSED, () => { void this.video.play().catch(() => undefined); this.emitTracks(); });
    hls.on(HlsCtor.Events.LEVEL_SWITCHED, (_e, d) => { this.lastLevel = d.level; this.emitTracks(); });
    hls.on(HlsCtor.Events.AUDIO_TRACKS_UPDATED, () => this.emitTracks());
    hls.on(HlsCtor.Events.SUBTITLE_TRACKS_UPDATED, () => this.emitTracks());
    hls.on(HlsCtor.Events.ERROR, (_e, d) => {
      if (!d.fatal) return;
      if (d.type === HlsCtor.ErrorTypes.NETWORK_ERROR && d.response?.code === undefined && d.details === "manifestLoadError") { this.ev.onError("manifest"); return; }
      if (d.type === HlsCtor.ErrorTypes.MEDIA_ERROR) {
        // Flux au codec cassé : recoverMediaError sans limite bouclait indéfiniment et le repli de format ne venait jamais.
        const now = Date.now();
        this.mediaRecoveries = this.mediaRecoveries.filter((t) => now - t < 30_000);
        this.mediaRecoveries.push(now);
        if (this.mediaRecoveries.length > 3) { this.ev.onError("hls:media"); return; }
        if (this.mediaRecoveries.length === 2) hls.swapAudioCodec();
        hls.recoverMediaError();
        return;
      }
      this.ev.onError(`hls:${d.details}`);
    });
    hls.attachMedia(this.video);
    hls.loadSource(wrap(a.url));
    this.hls = hls;
  }

  private async loadTs(a: LoadArgs, t: Transport, headers: Record<string, string>, my: number) {
    const mod = (await import("mpegts.js")).default;
    if (my !== this.token) return;
    if (!mod.isSupported()) { this.ev.onError("mpegts-unsupported"); return; }
    const player = mod.createPlayer(
      { type: "mpegts", isLive: a.live, url: wrapUrl(t, a.url), headers } as never,
      { enableWorker: true, liveBufferLatencyChasing: true, liveBufferLatencyMaxLatency: 8, liveBufferLatencyMinRemain: 1.5, lazyLoadMaxDuration: 60 } as never,
    );
    this.engineName = "mpegts.js";
    player.on(mod.Events.ERROR, () => this.ev.onError("mpegts"));
    player.on(mod.Events.MEDIA_INFO, (i: unknown) => {
      const info = i as { videoCodec?: string; audioCodec?: string };
      this.codec = [info.videoCodec, info.audioCodec].filter(Boolean).join(" · ");
    });
    player.attachMediaElement(this.video);
    player.load();
    void Promise.resolve(player.play()).catch(() => undefined);
    this.mpegts = player;
  }

  /** Applique la langue audio préférée tant que l'utilisateur n'a pas choisi à la main. */
  private autoSelectAudio() {
    if (this.audioManual || this.preferredAudio.length === 0) return;
    const h = this.hls;
    if (h && h.audioTracks.length > 1) {
      const i = bestTrackIndex(h.audioTracks.map((x) => ({ lang: x.lang, label: x.name })), this.preferredAudio);
      if (i !== null && h.audioTrack !== i) h.audioTrack = i;
      return;
    }
    const nat = this.nativeAudio();
    if (nat.length > 1) {
      const i = bestTrackIndex(nat.map((x) => ({ lang: x.language, label: x.label })), this.preferredAudio);
      if (i !== null && !nat[i]!.enabled) nat.forEach((x, j) => { x.enabled = j === i; });
    }
  }

  private nativeAudio(): { language: string; label: string; enabled: boolean }[] {
    const a = (this.video as unknown as { audioTracks?: ArrayLike<{ language: string; label: string; enabled: boolean }> }).audioTracks;
    return a ? Array.from(a) : [];
  }

  private emitTracks() {
    this.autoSelectAudio();
    const h = this.hls;
    const levels = h ? h.levels.map((l: Level, i: number) => ({ id: i, label: l.height ? `${l.height}p` : `${Math.round(l.bitrate / 1000)} kb/s` })) : [];
    let audio = h ? h.audioTracks.map((x, i) => ({ id: i, label: trackLabel({ lang: x.lang, label: x.name }, i, this.uiLang), lang: x.lang })) : [];
    let activeAudio = h?.audioTrack ?? -1;
    if (!h) {
      const nat = this.nativeAudio();
      audio = nat.map((x, i) => ({ id: i, label: trackLabel({ lang: x.language, label: x.label }, i, this.uiLang), lang: x.language }));
      activeAudio = nat.findIndex((x) => x.enabled);
    }
    const text = h ? h.subtitleTracks.map((x, i) => ({ id: i, label: trackLabel({ lang: x.lang, label: x.name }, i, this.uiLang), lang: x.lang })) : [];
    this.ev.onTracks({
      audio, activeAudio, text, activeText: h?.subtitleTrack ?? -1,
      levels, activeLevel: h ? (h.autoLevelEnabled ? -1 : h.currentLevel) : -1,
    });
  }

  setLevel(id: number) { if (this.hls) { this.hls.currentLevel = id; this.emitTracks(); } }
  setAudio(id: number) {
    this.audioManual = true;
    if (this.hls) this.hls.audioTrack = id;
    else this.nativeAudio().forEach((x, j) => { x.enabled = j === id; });
    this.emitTracks();
  }
  setText(id: number) { if (this.hls) { this.hls.subtitleTrack = id; this.emitTracks(); } }

  stats(): Stats {
    const v = this.video;
    const q = v.getVideoPlaybackQuality?.();
    let buffer = 0;
    for (let i = 0; i < v.buffered.length; i++) if (v.currentTime >= v.buffered.start(i) && v.currentTime <= v.buffered.end(i)) buffer = v.buffered.end(i) - v.currentTime;
    const lvl = this.hls && this.lastLevel >= 0 ? this.hls.levels[this.lastLevel] : undefined;
    return {
      engine: this.engineName, width: v.videoWidth, height: v.videoHeight,
      bitrate: lvl?.bitrate ?? Math.round(this.hls?.bandwidthEstimate ?? 0), buffer, dropped: q?.droppedVideoFrames ?? 0,
      codec: lvl ? [lvl.videoCodec, lvl.audioCodec].filter(Boolean).join(" · ") : this.codec,
    };
  }

  private teardown() {
    if (this.hls) { this.hls.destroy(); this.hls = null; }
    if (this.mpegts) {
      try { this.mpegts.pause(); this.mpegts.unload(); this.mpegts.detachMediaElement(); this.mpegts.destroy(); } catch { /* déjà détruit */ }
      this.mpegts = null;
    }
    this.codec = "";
    this.lastLevel = -1;
    this.mediaRecoveries = [];
    if (this.pendingSeek) { this.video.removeEventListener("loadedmetadata", this.pendingSeek); this.pendingSeek = null; }
    this.video.removeAttribute("src");
    this.video.load();
  }

  stop() { this.token++; this.teardown(); this.ev.onState("idle"); }
}
