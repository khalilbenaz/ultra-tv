import { describe, expect, it } from "vitest";
import { bestTrackIndex, effectivePreferred, langFromLabel, normalizeLang, trackLabel } from "./trackChoice";

describe("normalizeLang", () => {
  it("codes_iso639_ramenes_sur_deux_lettres", () => {
    for (const c of ["fre", "fra", "fr-FR", "FR"]) expect(normalizeLang(c)).toBe("fr");
    expect(normalizeLang("eng")).toBe("en");
    expect(normalizeLang("ara")).toBe("ar");
    expect(normalizeLang("spa")).toBe("es");
  });
  it("indetermine_renvoie_null", () => {
    expect(normalizeLang("und")).toBeNull();
    expect(normalizeLang(undefined)).toBeNull();
  });
});

describe("langFromLabel", () => {
  it("etiquettes_de_releases_reconnaissent_le_francais", () => {
    for (const l of ["French", "VFF 5.1", "TRUEFRENCH", "Français"]) expect(langFromLabel(l)).toBe("fr");
    expect(langFromLabel("Track 2")).toBeNull();
  });
});

describe("bestTrackIndex", () => {
  it("mkv_anglais_puis_francais_choisit_le_francais", () => {
    expect(bestTrackIndex([{ lang: "eng" }, { lang: "fre" }], ["fr"])).toBe(1);
  });
  it("sans_balise_mais_nom_vff_choisit_par_le_nom", () => {
    expect(bestTrackIndex([{ lang: "und", label: "Track 1" }, { label: "VFF" }], ["fr"])).toBe(1);
  });
  it("deuxieme_langue_si_la_premiere_est_absente", () => {
    expect(bestTrackIndex([{ lang: "eng" }, { lang: "deu" }], ["fr", "en"])).toBe(0);
  });
  it("aucune_correspondance_renvoie_null", () => {
    expect(bestTrackIndex([{ lang: "eng" }], ["ar"])).toBeNull();
  });
});

describe("effectivePreferred", () => {
  it("sans_reglage_prend_la_langue_de_l_interface", () => {
    expect(effectivePreferred([], "fr")).toEqual(["fr"]);
    expect(effectivePreferred(["ara", "en"], "fr")).toEqual(["ar", "en"]);
  });
});

describe("trackLabel", () => {
  it("langue_connue_nom_dans_la_langue_de_l_interface", () => {
    expect(trackLabel({ lang: "fre" }, 0, "fr")).toBe("Français");
    expect(trackLabel({ lang: "eng" }, 0, "en")).toBe("English");
  });
  it("sans_info_numero_de_piste", () => {
    expect(trackLabel({ label: "Track 3" }, 2, "fr")).toBe("#3");
  });
});
