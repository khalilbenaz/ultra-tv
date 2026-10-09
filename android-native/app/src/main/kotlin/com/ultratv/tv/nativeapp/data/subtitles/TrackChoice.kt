package com.ultratv.tv.nativeapp.data.subtitles

import java.util.Locale

/**
 * Choix et libellés des pistes audio / sous-titres : fonctions pures, communes à Media3 et LibVLC.
 *
 * Les flux (MKV, releases MULTI) étiquettent leurs pistes de façons très variées : « fr », « fra », « fre »,
 * « fr-FR », « French », « VFF », « TRUEFRENCH », ou aucune langue du tout. Tout est ramené à un code ISO 639-1.
 */
object TrackChoice {
    /** Une piste candidate : [language] = balise du flux (peut être 639-1, 639-2/B ou 639-2/T), [label] = nom libre. */
    data class Candidate(val language: String?, val label: String?)

    private val undetermined = setOf("und", "zxx", "mis", "mul", "qaa", "")

    /** Codes ISO 639-2/B (bibliographiques) qui diffèrent du 639-2/T : `Locale` ne connaît que le T. */
    private val bibliographic = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "rum" to "ro", "slo" to "sk",
        "per" to "fa", "ice" to "is", "mac" to "mk", "may" to "ms", "wel" to "cy", "arm" to "hy", "baq" to "eu", "geo" to "ka", "alb" to "sq",
    )

    private val iso3to1: Map<String, String> by lazy {
        buildMap { for (c in Locale.getISOLanguages()) runCatching { put(Locale(c).isO3Language.lowercase(), c) } }
    }

    /** Noms de langue (anglais + langue elle-même, sans accents) → code 639-1, pour reconnaître « French », « Français »… */
    private val nameToCode: Map<String, String> by lazy {
        buildMap {
            for (c in Locale.getISOLanguages()) {
                val l = Locale(c)
                listOf(l.getDisplayLanguage(Locale.ENGLISH), l.getDisplayLanguage(l), l.getDisplayLanguage(Locale.FRENCH))
                    .map { fold(it) }.filter { it.length > 3 && it != c }.forEach { putIfAbsent(it, c) }
            }
            // Étiquettes de releases : VF = version française, VO/VOST ne désignent PAS une langue précise.
            for (w in listOf("vf", "vff", "vfq", "vfi", "vf2", "truefrench", "francais")) put(w, "fr")
            put("anglais", "en"); put("arabe", "ar"); put("espagnol", "es"); put("allemand", "de"); put("italien", "it"); put("portugais", "pt")
        }
    }

    private fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** « fre », « fra », « fr-FR », « FR », « French » (balise seule) → « fr » ; indéterminé (« und », vide) → null. */
    fun normalizeLanguage(raw: String?): String? {
        val tag = raw?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()
        if (tag in undetermined) return null
        return when {
            tag.length == 2 -> tag
            tag.length == 3 -> bibliographic[tag] ?: iso3to1[tag] ?: tag
            else -> nameToCode[fold(tag)]
        }
    }

    /** Langue d'une étiquette libre (« Track 2 - [French] », « VFF 5.1 », « Español ») ; null si on ne sait pas dire. */
    fun languageFromLabel(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val words = fold(label).split(Regex("[^\\p{L}0-9]+")).filter { it.isNotEmpty() }
        words.forEach { nameToCode[it]?.let { c -> return c } }
        // Un code seul (« fre », « eng ») n'est cru que s'il constitue l'étiquette entière ou le contenu des crochets.
        Regex("\\[([A-Za-z-]{2,3})]").find(label)?.groupValues?.get(1)?.let { normalizeLanguage(it) }?.takeIf { it.length == 2 }?.let { return it }
        return words.singleOrNull()?.takeIf { it.length in 2..3 }?.let { normalizeLanguage(it) }?.takeIf { it.length == 2 && it in Locale.getISOLanguages() }
    }

    /** Langues préférées effectives : mémorisée pour ce titre, puis réglage de l'utilisateur, à défaut la langue de l'interface. */
    fun effectivePreferred(setting: List<String>, remembered: String?, uiLanguage: String): List<String> {
        val base = setting.mapNotNull { normalizeLanguage(it) }.ifEmpty { listOfNotNull(normalizeLanguage(uiLanguage)) }
        return (listOfNotNull(normalizeLanguage(remembered)) + base).distinct()
    }

    /**
     * Indice (dans [candidates]) de la meilleure piste : pour chaque langue préférée, dans l'ordre, la balise de langue
     * l'emporte, puis le nom libre. Null si aucune ne correspond (on laisse alors le moteur décider).
     */
    fun bestIndex(candidates: List<Candidate>, preferred: List<String>): Int? {
        for (p in preferred.mapNotNull { normalizeLanguage(it) }) {
            candidates.indexOfFirst { normalizeLanguage(it.language) == p }.takeIf { it >= 0 }?.let { return it }
            candidates.indexOfFirst { normalizeLanguage(it.language) == null && languageFromLabel(it.label) == p }.takeIf { it >= 0 }?.let { return it }
        }
        return null
    }

    private val generic = Regex("^(track|piste|audio|subtitle|sous-titre|sous-titres|text|spu|#)\\s*#?\\d*$", RegexOption.IGNORE_CASE)

    fun channelsLabel(n: Int): String? = when {
        n <= 0 -> null; n == 1 -> "1.0"; n == 2 -> "2.0"; n == 6 -> "5.1"; n == 8 -> "7.1"; else -> "${n}ch"
    }

    fun codecLabel(mime: String?): String? {
        val m = mime?.lowercase() ?: return null
        return when {
            m.endsWith("eac3") || m.endsWith("eac3-joc") -> "E-AC3"
            m.endsWith("ac3") -> "AC3"
            m.endsWith("mp4a-latm") || m.endsWith("aac") -> "AAC"
            m.contains("true-hd") || m.contains("truehd") -> "TrueHD"
            m.contains("dts") -> "DTS"
            m.endsWith("opus") -> "Opus"
            m.endsWith("mpeg") || m.endsWith("mp3") -> "MP3"
            m.endsWith("flac") -> "FLAC"
            m.startsWith("text/") || m.contains("subrip") || m.contains("x-ssa") || m.contains("vtt") || m.contains("ttml") || m.contains("pgs") || m.contains("dvb") || m.contains("vobsub") -> null
            else -> m.substringAfter('/').uppercase().takeIf { it.isNotBlank() }
        }
    }

    /**
     * Libellé lisible : « Français (AC3 5.1) », « English », « Arabe » (nom de langue dans la langue de l'interface).
     * Si la langue est inconnue, le nom du flux est gardé tel quel (nettoyé) ; à défaut « #index ».
     */
    fun humanLabel(language: String?, label: String?, codec: String?, channels: Int, forced: Boolean, index: Int, ui: Locale): String {
        val code = normalizeLanguage(language) ?: languageFromLabel(label)
        val langName = code?.let { Locale(it).getDisplayLanguage(ui).replaceFirstChar { c -> c.uppercase() } }?.takeIf { it.isNotBlank() && it.lowercase() != code }
        val cleaned = label?.replace(Regex("^(Track|Piste)\\s*\\d+\\s*-\\s*", RegexOption.IGNORE_CASE), "")?.replace(Regex("^\\[(.*)]$"), "$1")?.trim().orEmpty()
        val name = langName ?: cleaned.takeIf { it.isNotBlank() && !generic.matches(it) } ?: "#$index"
        // Complément du flux (« Commentaire du réalisateur ») conservé s'il n'est pas juste le nom de la langue.
        val extra = cleaned.takeIf { langName != null && it.isNotBlank() && !generic.matches(it) && languageFromLabel(it) == null && normalizeLanguage(it) == null && !it.equals(langName, true) }
        val tech = listOfNotNull(codec, channelsLabel(channels)).joinToString(" ")
        val flags = buildList {
            if (tech.isNotBlank()) add(tech)
            if (forced) add(if (ui.language == "fr") "forcés" else "forced")
        }.joinToString(", ")
        return buildString {
            append(name)
            if (extra != null) append(" – ").append(extra)
            if (flags.isNotBlank()) append(" (").append(flags).append(')')
        }
    }
}
