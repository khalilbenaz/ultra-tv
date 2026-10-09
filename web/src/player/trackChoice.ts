// Choix et libellés des pistes audio / sous-titres : fonctions pures (testées), communes à hls.js et au lecteur natif.

const B_TO_1: Record<string, string> = {
  fre: "fr", fra: "fr", eng: "en", ara: "ar", spa: "es", ger: "de", deu: "de", ita: "it", por: "pt", dut: "nl", nld: "nl", tur: "tr",
  rus: "ru", chi: "zh", zho: "zh", jpn: "ja", kor: "ko", pol: "pl", rum: "ro", ron: "ro", gre: "el", ell: "el", per: "fa", fas: "fa",
};
const UNDETERMINED = new Set(["", "und", "zxx", "mis", "mul", "qaa"]);

/** « fre », « fra », « fr-FR », « FR » → « fr » ; indéterminé → null. */
export function normalizeLang(raw: string | null | undefined): string | null {
  const tag = (raw ?? "").trim().toLowerCase().replace(/_/g, "-").split("-")[0]!;
  if (UNDETERMINED.has(tag)) return null;
  if (tag.length === 2) return tag;
  return B_TO_1[tag] ?? (tag.length === 3 ? tag : null);
}

const NAME_TO_LANG: Record<string, string> = {
  french: "fr", francais: "fr", vf: "fr", vff: "fr", vfq: "fr", vfi: "fr", truefrench: "fr", english: "en", anglais: "en", arabic: "ar", arabe: "ar",
  spanish: "es", espanol: "es", espagnol: "es", german: "de", deutsch: "de", allemand: "de", italian: "it", italiano: "it", portuguese: "pt", portugues: "pt",
};
const fold = (s: string) => s.toLowerCase().normalize("NFD").replace(/\p{M}+/gu, "");

/** Langue déduite d'un nom libre (« French », « VFF 5.1 », « Français ») ; null si inconnue. */
export function langFromLabel(label: string | null | undefined): string | null {
  for (const w of fold(label ?? "").split(/[^\p{L}0-9]+/u)) if (w && NAME_TO_LANG[w]) return NAME_TO_LANG[w]!;
  return null;
}

export interface TrackLike { lang?: string | null; label?: string | null }

/** Langues préférées effectives : réglage éventuel, à défaut la langue de l'interface. */
export function effectivePreferred(setting: string[], uiLang: string): string[] {
  const s = setting.map(normalizeLang).filter((x): x is string => !!x);
  return [...new Set(s.length ? s : [normalizeLang(uiLang)].filter((x): x is string => !!x))];
}

/** Indice de la meilleure piste : balise de langue d'abord, nom libre ensuite, pour chaque langue préférée dans l'ordre. */
export function bestTrackIndex(tracks: TrackLike[], preferred: string[]): number | null {
  for (const p of preferred.map(normalizeLang)) {
    if (!p) continue;
    const byTag = tracks.findIndex((t) => normalizeLang(t.lang) === p);
    if (byTag >= 0) return byTag;
    const byName = tracks.findIndex((t) => normalizeLang(t.lang) === null && langFromLabel(t.label) === p);
    if (byName >= 0) return byName;
  }
  return null;
}

/** Libellé lisible dans la langue de l'interface : « Français », « English », « Arabe » ; sinon le nom du flux. */
export function trackLabel(t: TrackLike, index: number, uiLang: string): string {
  const code = normalizeLang(t.lang) ?? langFromLabel(t.label);
  if (code) {
    try {
      const n = new Intl.DisplayNames([uiLang], { type: "language" }).of(code);
      if (n && n.toLowerCase() !== code) return n.charAt(0).toUpperCase() + n.slice(1);
    } catch { /* code non reconnu par Intl */ }
  }
  const l = (t.label ?? "").trim();
  return l && !/^(track|piste|audio|#)\s*\d*$/i.test(l) ? l : `#${index + 1}`;
}
