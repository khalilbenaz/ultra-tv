// Pure helpers for detecting gzip-encoded EPG payloads.
//
// We can't trust the URL extension or Content-Type alone: many providers serve
// gzipped XMLTV from a ".xml" URL or with "application/octet-stream". The most
// reliable signal is the gzip magic number at the start of the payload.

// gzip files begin with the two-byte magic 0x1f 0x8b.
export function hasGzipMagic(bytes: Uint8Array): boolean {
  return bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
}

/**
 * Decide whether an EPG payload is gzipped, combining cheap hints (URL
 * extension, Content-Type) with an authoritative magic-byte sniff.
 */
export function isGzipEpg(url: string, contentType: string, bytes: Uint8Array): boolean {
  if (hasGzipMagic(bytes)) return true;
  return url.endsWith(".gz") || contentType.includes("gzip");
}
