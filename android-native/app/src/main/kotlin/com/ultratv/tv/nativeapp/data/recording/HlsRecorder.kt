package com.ultratv.tv.nativeapp.data.recording

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI

/**
 * Records a single live HLS stream segment-by-segment into a flat .ts file.
 * MPEG-TS segments can be concatenated raw, so we just append each downloaded
 * segment's bytes to the output. Re-polls the manifest on a cadence derived
 * from `#EXT-X-TARGETDURATION`.
 *
 * Stop conditions, in order:
 *  - [shouldStop] returns true (caller cancellation)
 *  - max duration reached
 *  - manifest stops producing new segments for ~3× target duration (silence)
 *
 * The output file is written incrementally; partial recordings remain
 * playable.
 */
class HlsRecorder(
    private val ok: OkHttpClient,
    private val sourceM3u8: String,
    private val outFile: File,
    private val maxDurationMs: Long,
    private val shouldStop: () -> Boolean,
    private val onProgress: (downloadedBytes: Long, elapsedMs: Long) -> Unit,
) {
    suspend fun run(): Boolean {
        outFile.parentFile?.mkdirs()
        // Dedupe by absolute HLS media-sequence number, NOT by segment URI.
        // Sliding live playlists recycle segment file names (seg00.ts, seg01.ts,
        // …) so a URI-keyed set both leaks memory unboundedly and wrongly skips
        // a *new* segment that happens to reuse an old name (causing gaps).
        // #EXT-X-MEDIA-SEQUENCE is the sequence number of the first segment in
        // the current playlist; segment N's absolute sequence is
        // mediaSequence + N. We just track the highest sequence we've written
        // and append anything beyond it — O(1) memory regardless of duration.
        var lastWrittenSeq = -1L
        var totalBytes = 0L
        val startMs = System.currentTimeMillis()
        var lastNewSegmentMs = startMs
        var targetSec = 6.0   // sensible default before we see #EXT-X-TARGETDURATION

        outFile.outputStream().use { sink ->
            while (true) {
                if (shouldStop()) return false
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= maxDurationMs) return true

                val manifest = try {
                    fetchManifest(sourceM3u8)
                } catch (_: Throwable) {
                    delay((targetSec * 1000).toLong())
                    continue
                }
                val segments = manifest.segments
                if (manifest.targetDuration > 0) targetSec = manifest.targetDuration

                var sawNew = false
                segments.forEachIndexed { idx, seg ->
                    if (shouldStop() || System.currentTimeMillis() - startMs >= maxDurationMs) {
                        return true
                    }
                    val seq = manifest.mediaSequence + idx
                    if (seq <= lastWrittenSeq) return@forEachIndexed
                    lastWrittenSeq = seq
                    sawNew = true
                    lastNewSegmentMs = System.currentTimeMillis()
                    runCatching {
                        ok.newCall(Request.Builder().url(seg).build()).execute().use { resp ->
                            if (!resp.isSuccessful) return@use
                            resp.body?.byteStream()?.use { input ->
                                val buf = ByteArray(64 * 1024)
                                var n: Int
                                while (input.read(buf).also { n = it } > 0) {
                                    sink.write(buf, 0, n)
                                    totalBytes += n
                                }
                            }
                        }
                    }
                    onProgress(totalBytes, System.currentTimeMillis() - startMs)
                }

                // Manifest silent for too long: provider stopped publishing
                // segments. Bail out gracefully.
                if (!sawNew && System.currentTimeMillis() - lastNewSegmentMs > (targetSec * 3000).toLong()) {
                    return true
                }
                // Sleep half a target-duration before re-polling.
                delay((targetSec * 500).toLong())
            }
        }
    }

    /** Parsed live playlist: ordered segment URLs plus the playlist-level
     *  #EXT-X-MEDIA-SEQUENCE (sequence of segments[0]) and #EXT-X-TARGETDURATION. */
    private data class Manifest(
        val segments: List<String>,
        val mediaSequence: Long,
        val targetDuration: Double,
    )

    private fun fetchManifest(url: String): Manifest {
        val req = Request.Builder().url(url).build()
        val text = ok.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("manifest HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val segments = mutableListOf<String>()
        var targetDuration = 0.0
        var mediaSequence = 0L   // defaults to 0 when the tag is absent (per RFC 8216)
        val base = URI.create(url)
        var lastWasExtinf = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-TARGETDURATION")) {
                targetDuration = line.substringAfter(":").toDoubleOrNull() ?: targetDuration
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: mediaSequence
            } else if (line.startsWith("#EXTINF")) {
                lastWasExtinf = true
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                if (lastWasExtinf) {
                    val abs = if (line.startsWith("http")) line else base.resolve(line).toString()
                    segments.add(abs)
                    lastWasExtinf = false
                }
            }
        }
        return Manifest(segments, mediaSequence, targetDuration)
    }
}
