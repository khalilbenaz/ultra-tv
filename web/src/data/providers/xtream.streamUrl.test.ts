import { describe, expect, it } from "vitest";
import {
  xtream,
  resolveChannelUrl,
  resolveMovieUrl,
  resolveEpisodeUrl,
} from "@data/providers/xtream";
import type { Channel, Episode, Movie, Provider } from "@domain/model";

const provider = {
  id: 1,
  type: "XTREAM_CODES",
  serverUrl: "http://host.example:8080",
  username: "user",
  password: "pass",
  userAgent: "UA/1.0",
  httpReferer: "",
} as unknown as Provider;

describe("xtream low-level URL builders", () => {
  const creds = { serverUrl: provider.serverUrl, username: "user", password: "pass" };

  it("builds a live URL with default m3u8 extension and trailing-slash-normalized base", () => {
    expect(xtream.liveStreamUrl(creds, 42)).toBe("http://host.example:8080/live/user/pass/42.m3u8");
    expect(xtream.liveStreamUrl({ ...creds, serverUrl: "http://host.example:8080/" }, 42)).toBe(
      "http://host.example:8080/live/user/pass/42.m3u8",
    );
  });

  it("builds VOD and series episode URLs with the given container extension", () => {
    expect(xtream.vodStreamUrl(creds, 100, "mp4")).toBe("http://host.example:8080/movie/user/pass/100.mp4");
    expect(xtream.seriesEpisodeUrl(creds, 200, "mkv")).toBe("http://host.example:8080/series/user/pass/200.mkv");
  });
});

describe("resolveChannelUrl (M-3)", () => {
  it("rebuilds the URL from streamId when streamUrl is empty (new lean rows)", () => {
    const ch = { streamUrl: "", streamId: 7 } as Pick<Channel, "streamUrl" | "streamId">;
    expect(resolveChannelUrl(provider, ch)).toBe("http://host.example:8080/live/user/pass/7.m3u8");
  });

  it("uses the stored streamUrl when present (legacy rows / M3U providers)", () => {
    const ch = { streamUrl: "http://other/legacy.m3u8", streamId: 7 } as Pick<Channel, "streamUrl" | "streamId">;
    expect(resolveChannelUrl(provider, ch)).toBe("http://other/legacy.m3u8");
  });
});

describe("resolveMovieUrl (M-3)", () => {
  it("rebuilds from streamId + containerExtension when streamUrl is empty", () => {
    const m = { streamUrl: "", streamId: 55, containerExtension: "mkv" } as Pick<
      Movie,
      "streamUrl" | "streamId" | "containerExtension"
    >;
    expect(resolveMovieUrl(provider, m)).toBe("http://host.example:8080/movie/user/pass/55.mkv");
  });

  it("falls back to mp4 when containerExtension is null", () => {
    const m = { streamUrl: "", streamId: 55, containerExtension: null } as Pick<
      Movie,
      "streamUrl" | "streamId" | "containerExtension"
    >;
    expect(resolveMovieUrl(provider, m)).toBe("http://host.example:8080/movie/user/pass/55.mp4");
  });

  it("prefers a present legacy streamUrl", () => {
    const m = { streamUrl: "http://legacy/movie.mp4", streamId: 55, containerExtension: "mp4" } as Pick<
      Movie,
      "streamUrl" | "streamId" | "containerExtension"
    >;
    expect(resolveMovieUrl(provider, m)).toBe("http://legacy/movie.mp4");
  });
});

describe("resolveEpisodeUrl (M-3)", () => {
  it("rebuilds from episodeId + containerExtension when streamUrl is empty", () => {
    const e = { streamUrl: "", episodeId: 900, containerExtension: "mp4" } as Pick<
      Episode,
      "streamUrl" | "episodeId" | "containerExtension"
    >;
    expect(resolveEpisodeUrl(provider, e)).toBe("http://host.example:8080/series/user/pass/900.mp4");
  });

  it("prefers a present legacy streamUrl", () => {
    const e = { streamUrl: "http://legacy/ep.mp4", episodeId: 900, containerExtension: "mp4" } as Pick<
      Episode,
      "streamUrl" | "episodeId" | "containerExtension"
    >;
    expect(resolveEpisodeUrl(provider, e)).toBe("http://legacy/ep.mp4");
  });
});
