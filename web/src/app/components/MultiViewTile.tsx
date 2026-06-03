// One tile of the multi-view grid. Has its own PlayerEngine instance, mute toggle,
// a searchable channel picker, and surfaces the currently-airing program.

import { useEffect, useMemo, useRef, useState } from "react";
import { WebPlayerEngine } from "@player/PlayerEngine";
import { useCurrentPrograms } from "@app/hooks/useCurrentPrograms";
import type { Channel, Provider, StreamInfo } from "@domain/model";
import { streamTypeFromUrl } from "@domain/model";
import { resolveChannelUrl } from "@data/providers/xtream";

interface Props {
  provider: Provider | null;
  channels: Channel[];
  channel: Channel | null;
  onChange: (c: Channel | null) => void;
  audioActive: boolean;
  onActivateAudio: () => void;
}

export function MultiViewTile({ provider, channels, channel, onChange, audioActive, onActivateAudio }: Props) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const engineRef = useRef<WebPlayerEngine | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [query, setQuery] = useState("");

  const epgIds = useMemo(() => (channel?.epgChannelId ? [channel.epgChannelId] : []), [channel]);
  const epgMap = useCurrentPrograms(epgIds);
  const current = channel?.epgChannelId ? epgMap.get(channel.epgChannelId)?.current : null;

  useEffect(() => {
    if (!videoRef.current) return;
    const engine = new WebPlayerEngine();
    engine.attach(videoRef.current);
    engineRef.current = engine;
    return () => engine.release();
  }, []);

  useEffect(() => {
    if (!engineRef.current) return;
    if (!channel) return;
    const url = provider ? resolveChannelUrl(provider, channel) : channel.streamUrl;
    const stream: StreamInfo = {
      url,
      title: channel.name,
      headers: {},
      userAgent: provider?.userAgent || null,
      streamType: streamTypeFromUrl(url),
      containerExtension: null,
      catchUpUrl: null,
      expirationTime: null,
      drmInfo: null,
    };
    void engineRef.current.load(stream).then(() => engineRef.current?.play());
  }, [channel, provider]);

  useEffect(() => {
    if (videoRef.current) videoRef.current.muted = !audioActive;
  }, [audioActive]);

  const filtered = useMemo(() => {
    if (!query) return channels.slice(0, 200);
    const lc = query.toLowerCase();
    return channels.filter((c) => c.name.toLowerCase().includes(lc)).slice(0, 200);
  }, [channels, query]);

  return (
    <div className={`mv-tile${audioActive ? " audio-active" : ""}`} onClick={onActivateAudio}>
      <video ref={videoRef} playsInline muted={!audioActive} style={{ width: "100%", height: "100%", background: "#000" }} />

      {/* Top overlay — channel name + current program */}
      <div className="mv-overlay-top">
        <button
          className="mv-channel-btn"
          onClick={(e) => { e.stopPropagation(); setPickerOpen(true); }}
        >
          {channel?.name ?? "Select channel"} ▾
        </button>
        {current && (
          <span className="mv-program">▶ {current.title}</span>
        )}
      </div>

      {/* Bottom overlay — audio indicator */}
      <div className="mv-overlay-bottom">
        <span className="mv-audio-indicator">{audioActive ? "🔊" : "🔇"}</span>
      </div>

      {pickerOpen && (
        <div className="mv-picker" onClick={(e) => e.stopPropagation()}>
          <input
            autoFocus
            placeholder="Search channel…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <ul>
            {filtered.map((c) => (
              <li key={c.id}>
                <button onClick={() => { onChange(c); setPickerOpen(false); setQuery(""); }}>
                  <span style={{ color: "var(--fg-muted)", width: 32 }}>{c.number || ""}</span>
                  <span>{c.name}</span>
                </button>
              </li>
            ))}
          </ul>
          <div style={{ display: "flex", justifyContent: "space-between", marginTop: 6 }}>
            <button onClick={() => { onChange(null); setPickerOpen(false); }}>Clear</button>
            <button onClick={() => setPickerOpen(false)}>Close</button>
          </div>
        </div>
      )}
    </div>
  );
}
