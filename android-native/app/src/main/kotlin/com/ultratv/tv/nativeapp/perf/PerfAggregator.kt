package com.ultratv.tv.nativeapp.perf

/**
 * Agrégation des images (frames) PAR ÉCRAN pour la télémétrie de fluidité : nombre d'images, images saccadées, pire durée
 * et p95 approché. Logique pure (aucune dépendance Android) : l'horloge est passée en paramètre, ce qui la rend testable.
 *
 * Aucune donnée personnelle : seul le MODÈLE de route (« home », « live », « movies/{id} »…) est utilisé, jamais ses arguments.
 * Thread-safe : les mesures arrivent d'un fil de JankStats, le vidage du fil principal ou d'un fil de fond.
 */
class PerfAggregator(
    /** Écart minimal entre deux rapports d'une même route. */
    private val minIntervalMs: Long = REPORT_INTERVAL_MS,
    /** En deçà, l'échantillon est trop mince pour être parlant : il continue de s'accumuler. */
    private val minFrames: Int = MIN_FRAMES,
) {
    private class Bucket {
        var frames = 0
        var janky = 0
        var worstMs = 0L
        val histogram = IntArray(HISTOGRAM_SIZE)
        var lastReportMs = Long.MIN_VALUE
    }

    private val buckets = LinkedHashMap<String, Bucket>()

    /** Une image terminée sur [route] : durée [frameMs] (ms), [janky] selon l'heuristique de JankStats. */
    @Synchronized
    fun record(route: String, frameMs: Long, janky: Boolean) {
        val b = buckets.getOrPut(normalize(route)) { Bucket() }
        b.frames++
        if (janky) b.janky++
        if (frameMs > b.worstMs) b.worstMs = frameMs
        b.histogram[(frameMs / BUCKET_MS).toInt().coerceIn(0, HISTOGRAM_SIZE - 1)]++
    }

    /**
     * Rapports DUS à l'instant [nowMs] : une ligne par route ayant assez d'images ET dont le dernier rapport date d'au moins
     * [minIntervalMs]. Les compteurs d'une route rapportée repartent de zéro ; les autres continuent de s'accumuler.
     * [only] limite le vidage à une route (celle qu'on quitte).
     */
    @Synchronized
    fun drain(nowMs: Long, only: String? = null): List<String> {
        val out = ArrayList<String>()
        val target = only?.let(::normalize)
        for ((route, b) in buckets) {
            if (target != null && route != target) continue
            if (b.frames < minFrames) continue
            if (b.lastReportMs != Long.MIN_VALUE && nowMs - b.lastReportMs < minIntervalMs) continue
            out += format(route, b)
            b.lastReportMs = nowMs
            b.frames = 0; b.janky = 0; b.worstMs = 0L; b.histogram.fill(0)
        }
        return out
    }

    private fun format(route: String, b: Bucket): String {
        val pct = Math.round(b.janky * 100.0 / b.frames).toInt()
        return "perf $route frames=${b.frames} janky=${b.janky} ($pct%) p95≈${p95(b)}ms worst=${b.worstMs}ms"
    }

    /** Borne haute du seau qui contient le 95e centile. */
    private fun p95(b: Bucket): Long {
        val target = Math.ceil(b.frames * 0.95).toInt().coerceAtLeast(1)
        var seen = 0
        for (i in b.histogram.indices) {
            seen += b.histogram[i]
            if (seen >= target) return if (i == HISTOGRAM_SIZE - 1) b.worstMs else (i + 1L) * BUCKET_MS
        }
        return b.worstMs
    }

    companion object {
        const val REPORT_INTERVAL_MS = 60_000L
        const val MIN_FRAMES = 30
        private const val BUCKET_MS = 4L
        private const val HISTOGRAM_SIZE = 128   // 0..512 ms par seaux de 4 ms ; au-delà, dernier seau (le pire est exact).

        /** « player?url={url}&title={title} » → « player » ; « movies/{id} » reste tel quel (modèle, pas de valeur réelle). */
        fun normalize(route: String?): String = route?.substringBefore('?')?.trim()?.ifEmpty { null } ?: "(none)"
    }
}

/**
 * Anti-spam des longs blocages du fil principal (> [thresholdMs]) : au plus un événement par [minGapMs], plafonné à [maxPerLaunch].
 */
class StallGate(
    private val thresholdMs: Long = STALL_THRESHOLD_MS,
    private val minGapMs: Long = 15_000L,
    private val maxPerLaunch: Int = 20,
) {
    private var last = Long.MIN_VALUE
    private var count = 0

    @Synchronized
    fun shouldReport(frameMs: Long, nowMs: Long): Boolean {
        if (frameMs <= thresholdMs || count >= maxPerLaunch) return false
        if (last != Long.MIN_VALUE && nowMs - last < minGapMs) return false
        last = nowMs; count++
        return true
    }

    companion object { const val STALL_THRESHOLD_MS = 700L }
}

/** Démarrage à froid : un seul rapport par lancement, à la première image affichée sur un écran connu. */
class ColdStartGate {
    private var done = false

    /** [route] null = l'interface n'a pas encore choisi d'écran : on attend. Rend le message, une seule fois. */
    @Synchronized
    fun onFrame(route: String?, sinceProcessStartMs: Long, sinceAppCreateMs: Long): String? {
        if (done || route == null) return null
        done = true
        return "perf coldstart ${sinceProcessStartMs}ms (since app onCreate ${sinceAppCreateMs}ms) route=${PerfAggregator.normalize(route)}"
    }
}
