package com.ultratv.tv.nativeapp.data.recording

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ultratv.tv.nativeapp.data.db.RecordingDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Downloads a recording. Dispatches by URL type:
 *   - `.m3u8` (live HLS): polls the manifest and concatenates segments via
 *     [HlsRecorder] until max duration or user-cancel
 *   - everything else: single OkHttp body stream copy (VOD MP4/MKV/TS)
 *
 * Progress is mirrored into RecordingEntity so the UI can render a percent
 * bar. WorkManager survives the user leaving the app and re-issues on
 * retryable failures.
 */
@HiltWorker
class RecordingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val ok: OkHttpClient,
    private val dao: RecordingDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_RECORDING_ID, -1L)
        if (id < 0) return Result.failure()
        val r = dao.byId(id) ?: return Result.failure()
        val maxDurationMs = inputData.getLong(KEY_MAX_DURATION_MS, 60L * 60 * 1000)

        return withContext(Dispatchers.IO) {
            val file = File(r.filePath).apply { parentFile?.mkdirs() }
            try {
                dao.updateProgress(id, "running", 0, 0, null, null)
                val cancelled = if (r.sourceUrl.contains(".m3u8", ignoreCase = true)) {
                    runHls(id, r.sourceUrl, file, maxDurationMs)
                } else {
                    runHttp(id, r.sourceUrl, file)
                }
                // The download path may have short-circuited on user cancel and
                // already written the "cancelled" status — don't clobber it with
                // "done".
                if (cancelled) {
                    Result.success()
                } else {
                    dao.updateProgress(id, "done", file.length(), file.length(), null, System.currentTimeMillis())
                    Result.success()
                }
            } catch (t: Throwable) {
                // Distinguish transient I/O hiccups (worth a WorkManager retry)
                // from terminal errors (bad playlist, 404, disk full) that will
                // never succeed on retry — surface those as "error".
                if (t is IOException || t is InterruptedIOException) {
                    dao.updateProgress(id, "failed", file.length(), 0, t.message, System.currentTimeMillis())
                    Result.retry()
                } else {
                    dao.updateProgress(id, "error", file.length(), 0, t.message, System.currentTimeMillis())
                    Result.failure()
                }
            }
        }
    }

    /** Returns true if the download was cancelled (status already written). */
    private suspend fun runHttp(id: Long, url: String, file: File): Boolean {
        val resp = ok.newCall(Request.Builder().url(url).build()).execute()
        // Non-2xx is terminal (404/410/invalid) — IllegalStateException maps to
        // Result.failure() in doWork, not a retry.
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        val total = resp.body?.contentLength() ?: -1L
        var down = 0L
        resp.body?.byteStream()?.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                var lastPct = -1L
                while (input.read(buf).also { n = it } > 0) {
                    if (isStopped) {
                        dao.updateProgress(id, "cancelled", down, total, "Cancelled", System.currentTimeMillis())
                        return true
                    }
                    out.write(buf, 0, n)
                    down += n
                    val pct = if (total > 0) (down * 100 / total) else -1
                    if (pct != lastPct) {
                        dao.updateProgress(id, "running", down, total, null, null)
                        lastPct = pct
                    }
                }
            }
        }
        return false
    }

    /** Returns true if recording was cancelled (status already written). */
    private suspend fun runHls(id: Long, url: String, file: File, maxDurationMs: Long): Boolean {
        val recorder = HlsRecorder(
            ok = ok,
            sourceM3u8 = url,
            outFile = file,
            maxDurationMs = maxDurationMs,
            shouldStop = { isStopped },
            onProgress = { bytes, _ ->
                // The recorder callback isn't suspendable; the DAO write is fine
                // here because we're already on Dispatchers.IO via doWork.
                runBlocking { dao.updateProgress(id, "running", bytes, 0, null, null) }
            },
        )
        // HlsRecorder.run() returns false when it stopped due to a cancel
        // request; in that case mark the row cancelled so doWork doesn't write
        // "done" over it. true means it ran to completion / max duration.
        val completed = recorder.run()
        if (!completed) {
            dao.updateProgress(id, "cancelled", file.length(), 0, "Cancelled", System.currentTimeMillis())
            return true
        }
        return false
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_MAX_DURATION_MS = "max_duration_ms"
    }
}
