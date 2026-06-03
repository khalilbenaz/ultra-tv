// EPG ingestion. Pulls an XMLTV document (raw XML or gzipped via DecompressionStream)
// and persists Programs scoped to the given provider.

import { parseXmltv } from "@data/parsers/xmltv";
import { programRepo } from "@data/db/repositories";
import { proxiedFetch } from "@data/net/proxy";
import { isGzipEpg } from "@data/sync/gzip";

export async function fetchEpg(url: string, providerId: number, userAgent: string | null = null, referer: string | null = null): Promise<number> {
  const res = await proxiedFetch(url, { userAgent, referer });
  if (!res.ok) throw new Error(`EPG fetch failed: ${res.status}`);

  // Buffer the payload so we can sniff the gzip magic bytes (0x1f 0x8b) rather
  // than trusting only the URL extension / Content-Type, which providers often
  // get wrong (gzipped XMLTV served from a ".xml" URL, etc.).
  const buf = await res.arrayBuffer();
  const bytes = new Uint8Array(buf);
  const ct = res.headers.get("content-type") ?? "";

  let text: string;
  if (isGzipEpg(url, ct, bytes) && "DecompressionStream" in globalThis) {
    const ds = new DecompressionStream("gzip");
    const decompressed = new Response(buf).body?.pipeThrough(ds);
    text = await new Response(decompressed).text();
  } else {
    text = new TextDecoder().decode(bytes);
  }

  const parsed = parseXmltv(text, providerId);
  const programs = parsed.programs.map((p) => ({ ...p, providerId }));

  await programRepo.clearProvider(providerId);
  await programRepo.bulkAdd(programs);
  return programs.length;
}
