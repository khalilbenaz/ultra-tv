package com.ultratv.tv.nativeapp

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * RemoteLog — fire-and-forget HTTP transport for crashes and ad-hoc events.
 *
 * Direct POSTs, no local buffer file: crashes go to /api/crash inside the
 * UncaughtExceptionHandler with a short timeout (the process is dying — we
 * can't afford to block the OS death dialog); events go to /api/event via a
 * background scope.
 *
 * Endpoint + token come from BuildConfig (BuildConfig.LOG_URL / LOG_TOKEN),
 * populated by Gradle from the ULTRA_LOG_URL / ULTRA_LOG_TOKEN property or env
 * var, falling back to the historical production values (see app/build.gradle.kts).
 * They are still embedded in the APK so every install reports without setup —
 * BuildConfig just lets us override them at build time instead of editing
 * source. Rotate them in lock-step with the worker secret.
 */
object RemoteLog {

    private val WORKER_URL = BuildConfig.LOG_URL
    private val TOKEN      = BuildConfig.LOG_TOKEN

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Two clients: events tolerate slow networks; the crash path must return
    // quickly because the process is about to be killed.
    private val eventClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val crashClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /** Set once on Application.onCreate() so each entry knows who it came from. */
    @Volatile private var contextInfo: ContextInfo? = null
    @Volatile private var appCtx: android.content.Context? = null

    /** Mirrors UserPrefs.telemetryEnabled; toggled at runtime from Settings.
     *  Defaults to true so the dashboard keeps catching crashes out of the box. */
    @Volatile var telemetryEnabled: Boolean = true

    /**
     * Strip embedded credentials and stream URLs from any message bound for
     * the dashboard. Xtream URLs ship the user/pass in the path so logging
     * them verbatim leaks the subscription credentials.
     */
    private fun sanitize(input: String): String {
        var out = input
        // http(s)://host[:port]/<user>/<pass>/...   →   <provider>/…
        out = out.replace(
            Regex("""https?://[^\s/]+(/[^/\s]+){2,}"""),
            "<provider>/…",
        )
        // Standalone "user:password@" / "?username=foo&password=bar"
        out = out.replace(Regex("""(?i)(user(name)?|pass(word)?)=[^&\s]+"""), "$1=<redacted>")
        return out.take(4000)
    }

    data class ContextInfo(
        val mac: String,
        val versionName: String,
        val versionCode: Int,
        val device: String,
        val androidSdk: Int,
    )

    fun init(ctx: android.content.Context, mac: String, versionName: String, versionCode: Int) {
        appCtx = ctx.applicationContext
        contextInfo = ContextInfo(
            mac = mac,
            versionName = versionName,
            versionCode = versionCode,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidSdk = Build.VERSION.SDK_INT,
        )
        // Try to ship anything that survived a previous crash. crashSync's
        // synchronous POST can be cut short by the dying process; this is the
        // safety net that runs from the next clean start.
        scope.launch { flushPendingCrash() }
    }

    private fun pendingCrashFile(): java.io.File? {
        val ctx = appCtx ?: return null
        return ctx.getExternalFilesDir(null)?.resolve("crash.txt")
            ?: java.io.File(ctx.filesDir, "crash.txt")
    }

    private fun flushPendingCrash() {
        val ctx = contextInfo ?: return
        if (!telemetryEnabled) return
        val file = pendingCrashFile() ?: return
        if (!file.exists() || file.length() == 0L) return
        val stack = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (stack.isBlank()) return
        runCatching {
            val body = JSONObject().apply {
                put("mac", ctx.mac)
                put("version", ctx.versionName)
                put("versionCode", ctx.versionCode)
                put("device", ctx.device)
                put("androidSdk", ctx.androidSdk)
                put("stack", sanitize(stack).take(60_000))
            }.toString()
            val req = Request.Builder()
                .url("$WORKER_URL/api/crash")
                .header("X-Crash-Token", TOKEN)
                .post(body.toRequestBody(JSON))
                .build()
            eventClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) file.delete()
            }
        }
    }

    /** Ship a non-fatal log/event. Returns immediately; HTTP happens off-thread. */
    fun event(tag: String, message: String, level: String = "info") {
        val ctx = contextInfo ?: return
        if (!telemetryEnabled) return
        val safeMessage = sanitize(message)
        scope.launch {
            runCatching {
                val body = JSONObject().apply {
                    put("level", level)
                    put("tag", tag)
                    put("message", safeMessage)
                    put("mac", ctx.mac)
                    put("version", ctx.versionName)
                    put("versionCode", ctx.versionCode)
                    put("device", ctx.device)
                }.toString()
                val req = Request.Builder()
                    .url("$WORKER_URL/api/event")
                    .header("X-Crash-Token", TOKEN)
                    .post(body.toRequestBody(JSON))
                    .build()
                eventClient.newCall(req).execute().close()
            }
        }
    }

    fun info(tag: String, message: String) = event(tag, message, "info")
    fun warn(tag: String, message: String) = event(tag, message, "warn")
    fun error(tag: String, message: String) = event(tag, message, "error")
    fun debug(tag: String, message: String) = event(tag, message, "debug")

    /**
     * Called from the uncaught handler. First writes the stack to a local
     * file so the next clean start can re-upload if the process dies before
     * the network round-trip completes — then attempts a best-effort
     * synchronous POST with a 3 s budget.
     */
    fun crashSync(thread: Thread, error: Throwable) {
        val ctx = contextInfo ?: return
        val sw = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(sw))
        val payload = sanitize("${thread.name}: ${error.javaClass.name}: ${error.message}\n$sw")

        // 1. Persist locally so we can retry on next launch if the network leg
        //    doesn't complete in time. The crash.txt file is the source of
        //    truth — flushPendingCrash() deletes it on a successful upload.
        runCatching { pendingCrashFile()?.appendText(payload + "\n\n", Charsets.UTF_8) }

        // 2. Best-effort live upload. Honour the user's telemetry toggle.
        if (!telemetryEnabled) return
        runCatching {
            val body = JSONObject().apply {
                put("mac", ctx.mac)
                put("version", ctx.versionName)
                put("versionCode", ctx.versionCode)
                put("device", ctx.device)
                put("androidSdk", ctx.androidSdk)
                put("stack", payload)
            }.toString()
            val req = Request.Builder()
                .url("$WORKER_URL/api/crash")
                .header("X-Crash-Token", TOKEN)
                .post(body.toRequestBody(JSON))
                .build()
            crashClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) pendingCrashFile()?.delete()
            }
        }
    }

    private val JSON = "application/json".toMediaType()
}
