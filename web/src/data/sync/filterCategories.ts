// Pure helpers for the Settings → Categories whitelist applied during sync.
//
// Xtream category ids are strings. The Settings UI stores whitelists with an
// offset added to disambiguate MOVIE vs SERIES ids in a shared namespace:
//   MOVIE  ids are offset by +1_000_000
//   SERIES ids are offset by +2_000_000
// so the raw category_id is recovered by subtracting the offset.

export const MOVIE_ID_OFFSET = 1_000_000;
export const SERIES_ID_OFFSET = 2_000_000;

export interface RawCategory {
  category_id: string;
  category_name: string;
}

/**
 * Build a Set of raw (un-offset) category ids from a stored whitelist, or null
 * when there is no whitelist (null = include everything, e.g. first sync).
 */
export function buildCategoryFilter(whitelist: number[] | null | undefined, offset: number): Set<number> | null {
  if (!whitelist) return null;
  return new Set(whitelist.map((id) => id - offset));
}

/**
 * Filter raw categories against a whitelist Set. A null filter means "include
 * everything" and returns the input unchanged.
 */
export function filterCategoriesByWhitelist<T extends RawCategory>(cats: T[], filter: Set<number> | null): T[] {
  if (!filter) return cats;
  return cats.filter((c) => filter.has(Number.parseInt(c.category_id, 10)));
}
