import { describe, it, expect } from "vitest";
import {
  buildCategoryFilter,
  filterCategoriesByWhitelist,
  MOVIE_ID_OFFSET,
  SERIES_ID_OFFSET,
  type RawCategory,
} from "@data/sync/filterCategories";

const cats: RawCategory[] = [
  { category_id: "1", category_name: "Action" },
  { category_id: "2", category_name: "Comedy" },
  { category_id: "3", category_name: "Drama" },
];

describe("buildCategoryFilter", () => {
  it("returns null when no whitelist (include everything)", () => {
    expect(buildCategoryFilter(null, MOVIE_ID_OFFSET)).toBeNull();
    expect(buildCategoryFilter(undefined, SERIES_ID_OFFSET)).toBeNull();
  });

  it("strips the offset to recover raw category ids", () => {
    const filter = buildCategoryFilter([SERIES_ID_OFFSET + 2, SERIES_ID_OFFSET + 3], SERIES_ID_OFFSET);
    expect(filter).not.toBeNull();
    expect(filter!.has(2)).toBe(true);
    expect(filter!.has(3)).toBe(true);
    expect(filter!.has(1)).toBe(false);
  });
});

describe("filterCategoriesByWhitelist", () => {
  it("returns all categories unchanged when filter is null", () => {
    expect(filterCategoriesByWhitelist(cats, null)).toEqual(cats);
  });

  it("keeps only whitelisted series categories (regression: series used to fall back to vod cats)", () => {
    const filter = buildCategoryFilter([SERIES_ID_OFFSET + 2], SERIES_ID_OFFSET);
    const result = filterCategoriesByWhitelist(cats, filter);
    expect(result).toEqual([{ category_id: "2", category_name: "Comedy" }]);
  });
});
