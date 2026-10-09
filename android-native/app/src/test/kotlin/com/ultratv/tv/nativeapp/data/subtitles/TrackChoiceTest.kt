package com.ultratv.tv.nativeapp.data.subtitles

import com.ultratv.tv.nativeapp.data.subtitles.TrackChoice.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TrackChoiceTest {
    @Test fun normalizeLanguage_codesIso639_ramenesSurDeuxLettres() {
        assertEquals("fr", TrackChoice.normalizeLanguage("fre"))
        assertEquals("fr", TrackChoice.normalizeLanguage("fra"))
        assertEquals("fr", TrackChoice.normalizeLanguage("fr-FR"))
        assertEquals("fr", TrackChoice.normalizeLanguage("FR"))
        assertEquals("en", TrackChoice.normalizeLanguage("eng"))
        assertEquals("ar", TrackChoice.normalizeLanguage("ara"))
        assertEquals("es", TrackChoice.normalizeLanguage("spa"))
        assertEquals("de", TrackChoice.normalizeLanguage("ger"))
    }

    @Test fun normalizeLanguage_indetermine_renvoieNull() {
        assertNull(TrackChoice.normalizeLanguage("und"))
        assertNull(TrackChoice.normalizeLanguage(""))
        assertNull(TrackChoice.normalizeLanguage(null))
    }

    @Test fun languageFromLabel_etiquettesDeReleases_reconnaitLeFrancais() {
        assertEquals("fr", TrackChoice.languageFromLabel("French"))
        assertEquals("fr", TrackChoice.languageFromLabel("Track 2 - [French]"))
        assertEquals("fr", TrackChoice.languageFromLabel("VFF 5.1"))
        assertEquals("fr", TrackChoice.languageFromLabel("TRUEFRENCH"))
        assertEquals("fr", TrackChoice.languageFromLabel("Français"))
        assertEquals("en", TrackChoice.languageFromLabel("English AC3"))
        assertNull(TrackChoice.languageFromLabel("Track 2"))
        assertNull(TrackChoice.languageFromLabel("DTS 5.1"))
    }

    @Test fun effectivePreferred_sansReglage_prendLaLangueDeLInterface() {
        assertEquals(listOf("fr"), TrackChoice.effectivePreferred(emptyList(), null, "fr"))
    }

    @Test fun effectivePreferred_reglageEtMemoire_memoirePuisReglage() {
        assertEquals(listOf("es", "ar", "en"), TrackChoice.effectivePreferred(listOf("ara", "en"), "spa", "fr"))
        assertEquals(listOf("ar"), TrackChoice.effectivePreferred(listOf("ar"), "ara", "fr"))
    }

    @Test fun bestIndex_mkvAnglaisPuisFrancais_choisitLeFrancais() {
        val tracks = listOf(Candidate("eng", "English"), Candidate("fre", "French"))
        assertEquals(1, TrackChoice.bestIndex(tracks, listOf("fr")))
    }

    @Test fun bestIndex_balisesFraEtFre_equivalentes() {
        assertEquals(0, TrackChoice.bestIndex(listOf(Candidate("fra", null), Candidate("eng", null)), listOf("fre")))
    }

    @Test fun bestIndex_sansBaliseMaisNomVff_choisitParLeNom() {
        val tracks = listOf(Candidate("und", "Track 1"), Candidate(null, "VFF AC3 5.1"))
        assertEquals(1, TrackChoice.bestIndex(tracks, listOf("fr")))
    }

    @Test fun bestIndex_deuxiemeLanguePreferee_siLaPremiereAbsente() {
        val tracks = listOf(Candidate("eng", null), Candidate("deu", null))
        assertEquals(0, TrackChoice.bestIndex(tracks, listOf("fr", "en")))
    }

    @Test fun bestIndex_aucuneCorrespondance_renvoieNull() {
        assertNull(TrackChoice.bestIndex(listOf(Candidate("eng", null)), listOf("ar")))
        assertNull(TrackChoice.bestIndex(listOf(Candidate("eng", null)), emptyList()))
    }

    @Test fun bestIndex_balisePrioritaireSurLeNom() {
        // Une piste étiquetée « fra » l'emporte sur une piste sans balise dont le nom évoque le français.
        val tracks = listOf(Candidate(null, "French commentary"), Candidate("fra", null))
        assertEquals(1, TrackChoice.bestIndex(tracks, listOf("fr")))
    }

    @Test fun humanLabel_audioAc3_francaisAvecCodecEtCanaux() {
        assertEquals("Français (AC3 5.1)", TrackChoice.humanLabel("fre", null, "AC3", 6, false, 1, Locale.FRENCH))
    }

    @Test fun humanLabel_langueSeule_anglais() {
        assertEquals("English", TrackChoice.humanLabel("eng", "English", null, 0, false, 1, Locale.ENGLISH))
    }

    @Test fun humanLabel_arabeDansInterfaceFrancaise() {
        assertEquals("Arabe", TrackChoice.humanLabel("ara", null, null, 0, false, 1, Locale.FRENCH))
    }

    @Test fun humanLabel_nomVlcAvecCrochets_nettoye() {
        assertEquals("Français", TrackChoice.humanLabel(null, "Track 2 - [French]", null, 0, false, 2, Locale.FRENCH))
    }

    @Test fun humanLabel_sansAucuneInfo_numeroDePiste() {
        assertEquals("#3", TrackChoice.humanLabel(null, "Track 3", null, 0, false, 3, Locale.FRENCH))
    }

    @Test fun humanLabel_sousTitreForce_marque() {
        assertEquals("Français (forcés)", TrackChoice.humanLabel("fra", null, null, 0, true, 1, Locale.FRENCH))
    }

    @Test fun codecLabel_mimeUsuels() {
        assertEquals("AC3", TrackChoice.codecLabel("audio/ac3"))
        assertEquals("E-AC3", TrackChoice.codecLabel("audio/eac3"))
        assertEquals("AAC", TrackChoice.codecLabel("audio/mp4a-latm"))
        assertNull(TrackChoice.codecLabel("application/x-subrip"))
    }
}
