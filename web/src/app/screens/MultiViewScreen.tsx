// Split-screen multi-view, redesigned:
//   • Layout selector: 2×2, 1+3 (one big + three small), 1+2 vertical, 1×2 side-by-side
//   • Each tile has its own player + searchable channel picker
//   • One tile owns audio at a time — click to focus its audio (others mute)
//   • Current program overlay per tile
//   • Channel selection persists per slot for the session

import { useLiveQuery } from "dexie-react-hooks";
import { useMemo, useState } from "react";
import { channelRepo } from "@data/db/repositories";
import { useProviderStore } from "@app/stores/providers";
import { useActiveProvider } from "@app/hooks/useActiveProvider";
import { useUiStore, type MultiViewLayout } from "@app/stores/ui";
import { MultiViewTile } from "@app/components/MultiViewTile";
import { CategoryFilter } from "@app/components/CategoryFilter";
import type { Channel } from "@domain/model";

const SLOT_COUNT: Record<MultiViewLayout, number> = {
  "2x2": 4,
  "1+3": 4,
  "1+2": 3,
  "1x2": 2,
};

export function MultiViewScreen() {
  const providerId = useProviderStore((s) => s.activeProviderId);
  const provider = useActiveProvider();
  const layout = useUiStore((s) => s.multiViewLayout);
  const setLayout = useUiStore((s) => s.setMultiViewLayout);

  const channels = useLiveQuery<Channel[]>(
    async () => providerId == null ? [] : channelRepo.forProvider(providerId),
    [providerId],
  );

  const [slots, setSlots] = useState<(Channel | null)[]>(() => Array(4).fill(null));
  const [audioSlot, setAudioSlot] = useState(0);

  const count = SLOT_COUNT[layout];
  const activeSlots = useMemo(() => {
    const arr = [...slots];
    while (arr.length < count) arr.push(null);
    return arr.slice(0, count);
  }, [slots, count]);

  if (providerId == null) return <div className="empty">Add a provider first.</div>;

  const setSlot = (idx: number, c: Channel | null) => {
    setSlots((cur) => {
      const next = [...cur];
      while (next.length <= idx) next.push(null);
      next[idx] = c;
      return next;
    });
  };

  const gridClass = `mv-grid mv-${layout}`;

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "calc(100vh - 48px)" }}>
      <div style={{ display: "flex", gap: 12, marginBottom: 12, alignItems: "center", flexWrap: "wrap" }}>
        <h2 style={{ margin: 0 }}>Multi-View</h2>
        <div className="mv-layout-picker">
          {(["2x2", "1+3", "1+2", "1x2"] as MultiViewLayout[]).map((l) => (
            <button
              key={l}
              className={layout === l ? "active" : ""}
              onClick={() => void setLayout(l)}
              title={l}
            >
              {l}
            </button>
          ))}
        </div>
        <CategoryFilter providerId={providerId} contentType="LIVE" />
        <span style={{ color: "var(--fg-muted)" }}>Click a tile to switch its audio</span>
      </div>

      <div className={gridClass} style={{ flex: 1, minHeight: 0 }}>
        {activeSlots.map((c, idx) => (
          <MultiViewTile
            key={idx}
            provider={provider ?? null}
            channels={channels ?? []}
            channel={c}
            onChange={(nc) => setSlot(idx, nc)}
            audioActive={audioSlot === idx}
            onActivateAudio={() => setAudioSlot(idx)}
          />
        ))}
      </div>
    </div>
  );
}
