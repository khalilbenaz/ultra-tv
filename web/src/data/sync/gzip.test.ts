import { describe, it, expect } from "vitest";
import { hasGzipMagic, isGzipEpg } from "@data/sync/gzip";

const gzipBytes = new Uint8Array([0x1f, 0x8b, 0x08, 0x00]);
const xmlBytes = new TextEncoder().encode("<?xml version=\"1.0\"?><tv></tv>");

describe("hasGzipMagic", () => {
  it("detects the gzip magic number", () => {
    expect(hasGzipMagic(gzipBytes)).toBe(true);
  });
  it("rejects plain XML and too-short buffers", () => {
    expect(hasGzipMagic(xmlBytes)).toBe(false);
    expect(hasGzipMagic(new Uint8Array([0x1f]))).toBe(false);
  });
});

describe("isGzipEpg", () => {
  it("sniffs gzip even when URL ends .xml and content-type lies", () => {
    expect(isGzipEpg("http://x/epg.xml", "application/octet-stream", gzipBytes)).toBe(true);
  });
  it("falls back to extension / content-type hints", () => {
    expect(isGzipEpg("http://x/epg.xml.gz", "text/plain", xmlBytes)).toBe(true);
    expect(isGzipEpg("http://x/epg", "application/gzip", xmlBytes)).toBe(true);
  });
  it("treats plain XML as not gzipped", () => {
    expect(isGzipEpg("http://x/epg.xml", "application/xml", xmlBytes)).toBe(false);
  });
});
