import { useLiveQuery } from "dexie-react-hooks";
import { useEffect, useMemo, useState } from "react";
import { db } from "@data/db/database";
import { useProviderStore } from "@app/stores/providers";
import { useActiveProvider } from "@app/hooks/useActiveProvider";
import { useCategoryFilters } from "@app/stores/categoryFilters";
import { PlayerWithControls } from "@app/components/PlayerWithControls";
import { CategoryFilter } from "@app/components/CategoryFilter";
import { ProxyImg } from "@app/components/ProxyImg";
import { useCurrentPrograms } from "@app/hooks/useCurrentPrograms";
import { useNumericRemote } from "@app/hooks/useNumericRemote";
import { useI18n } from "@app/i18n";
import { useDebounced } from "@app/hooks/useDebounced";
import type { Channel, Provider, StreamInfo } from "@domain/model";
import { streamTypeFromUrl } from "@domain/model";
import { resolveChannelUrl } from "@data/providers/xtream";

export function LiveTvScreen() {
  const providerId = useProviderStore((s) => s.activeProviderId);
  const provider = useActiveProvider() as Provider | undefined;
  const t = useI18n((s) => s.t);
  const lang = useI18n((s) => s.lang); void lang;
  const loadFilters = useCategoryFilters((s) => s.load);
  const allowed = useCategoryFilters((s) => providerId == null ? null : s.allowed[`${providerId}:LIVE`]);
  const [search, setSearch] = useState("");

  useEffect(() => {
    if (providerId != null) void loadFilters(providerId);
  }, [providerId, loadFilters]);

  const channels = useLiveQuery<Channel[]>(
    async () => providerId == null ? [] : db.channels.where("providerId").equals(providerId).toArray(),
    [providerId],
  );
  const [selected, setSelected] = useState<Channel | null>(null);

  useNumericRemote((num) => {
    const target = (channels ?? []).find((c) => c.number === num);
    if (target) setSelected(target);
  });

  const epgIds = useMemo(() => (channels ?? []).map((c) => c.epgChannelId ?? "").filter(Boolean), [channels]);
  const epgMap = useCurrentPrograms(epgIds);

  const debouncedSearch = useDebounced(search, 250);
  const visible = useMemo(() => {
    if (!channels) return [];
    let list = channels;
    if (allowed != null) {
      const set = new Set(allowed);
      list = list.filter((c) => c.categoryId != null && set.has(c.categoryId));
    }
    const lc = debouncedSearch.toLowerCase().trim();
    if (lc) {
      list = list.filter((c) => {
        if (c.name.toLowerCase().includes(lc)) return true;
        if (!c.epgChannelId) return false;
        const slot = epgMap.get(c.epgChannelId);
        if (slot?.current?.title.toLowerCase().includes(lc)) return true;
        if (slot?.next?.title.toLowerCase().includes(lc)) return true;
        return false;
      });
    }
    return list;
  }, [channels, allowed, debouncedSearch, epgMap]);

  const stream: StreamInfo | null = useMemo(() => {
    if (!selected) return null;
    const headers: Record<string, string> = {};
    if (provider?.httpReferer) headers["Referer"] = provider.httpReferer;
    const url = provider ? resolveChannelUrl(provider, selected) : selected.streamUrl;
    return {
      url,
      title: selected.name,
      headers,
      userAgent: provider?.userAgent || null,
      streamType: streamTypeFromUrl(url),
      containerExtension: null,
      catchUpUrl: null,
      expirationTime: null,
      drmInfo: null,
    };
  }, [selected, provider]);

  if (providerId == null) {
    return <div className="empty">{t("home.noProvider")}</div>;
  }
  if (!channels) return <div className="empty">{t("common.loading")}</div>;
  if (channels.length === 0) return <div className="empty">{t("live.noChannels")}</div>;

  return (
    <div>
      <div style={{ display: "flex", gap: 8, marginBottom: 12, alignItems: "center" }}>
        <h2 style={{ margin: 0 }}>{t("live.title")}</h2>
        <CategoryFilter providerId={providerId} contentType="LIVE" />
        <input placeholder={t("live.searchPlaceholder")} value={search} onChange={(e) => setSearch(e.target.value)} />
        <span style={{ color: "var(--fg-muted)" }}>{t("live.channelsCount", { count: visible.length })}</span>
      </div>
      <div className="channel-grid">
        <div className="channel-list">
          {visible.map((c) => {
            const slot = c.epgChannelId ? epgMap.get(c.epgChannelId) : undefined;
            const cur = slot?.current ?? null;
            const next = slot?.next ?? null;
            const progress = cur ? Math.max(0, Math.min(1, (Date.now() - cur.startTime) / (cur.endTime - cur.startTime))) : 0;
            return (
              <div
                key={c.id}
                className={`channel-row${selected?.id === c.id ? " active" : ""}`}
                role="button"
                tabIndex={0}
                onClick={() => setSelected(c)}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setSelected(c); } }}
              >
                <span className="num">{c.number || ""}</span>
                <ProxyImg src={c.logoUrl} alt="" fallback={<span style={{ width: 36 }} />} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
                    <span style={{ overflow: "hidden", whiteSpace: "nowrap", textOverflow: "ellipsis" }}>{c.name}</span>
                    {cur && (
                      <span style={{ color: "var(--fg-muted)", fontSize: 11, flexShrink: 0 }}>
                        {Math.max(0, Math.round((cur.endTime - Date.now()) / 60000))} min
                      </span>
                    )}
                  </div>
                  <div style={{ fontSize: 12, color: cur ? "var(--accent)" : "var(--fg-muted)", overflow: "hidden", whiteSpace: "nowrap", textOverflow: "ellipsis" }}>
                    {cur ? `▶ ${cur.title}` : (c.categoryName ?? c.groupTitle ?? "")}
                  </div>
                  {cur && (
                    <div style={{ height: 2, background: "var(--border)", borderRadius: 1, marginTop: 4 }}>
                      <div style={{ width: `${progress * 100}%`, height: "100%", background: "var(--accent)", borderRadius: 1 }} />
                    </div>
                  )}
                  {next && (
                    <div style={{ fontSize: 11, color: "var(--fg-muted)", overflow: "hidden", whiteSpace: "nowrap", textOverflow: "ellipsis", marginTop: 2 }}>
                      {new Date(next.startTime).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })} · {next.title}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
        <PlayerWithControls stream={stream} tracking={selected ? {
          providerId: selected.providerId,
          contentId: selected.id,
          contentType: "LIVE",
          title: selected.name,
          posterUrl: selected.logoUrl,
          streamUrl: selected.streamUrl,
        } : null} />
      </div>
    </div>
  );
}
