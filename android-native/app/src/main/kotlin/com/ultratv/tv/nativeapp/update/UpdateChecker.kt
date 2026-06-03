package com.ultratv.tv.nativeapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.ultratv.tv.nativeapp.BuildConfig
import com.ultratv.tv.nativeapp.RemoteLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub-Releases-backed self-updater. No Play Store, no Firebase.
 *
 * Flow:
 *   1) [checkForUpdate] hits GitHub's REST API and parses tag_name + the
 *      UltraTV-debug.apk asset URL from the *latest* release. We compare
 *      versionCode (declared in build.gradle.kts) — if the remote tag's name
 *      maps to a higher one we surface an [UpdateInfo].
 *   2) [downloadAndInstall] streams the APK into the app's filesDir and uses
 *      PackageInstaller to commit a session. The system prompts the user the
 *      first time (Settings → Install unknown apps for Ultra TV).
 */
object UpdateChecker {

    private const val TAG = "update"
    private const val REPO = "khalilbenaz/ultra-tv"
    private const val APK_NAME = "UltraTV-debug.apk"

    data class UpdateInfo(
        val tag: String,
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val notes: String,
        /** Download URL of the sibling "<apk>.sha256" asset, if the release
         *  publishes one. Null → no integrity check available (backward compat). */
        val sha256Url: String? = null,
    )

    private val _state = MutableStateFlow<UpdateInfo?>(null)
    /** Latest known available update, or null if app is up-to-date / not checked. */
    val state: StateFlow<UpdateInfo?> = _state.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    RemoteLog.warn(TAG, "github releases ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim()           // e.g. "v1.0.5"
                val notes = json.optString("body").take(280)
                val verName = tag.removePrefix("v")
                val remoteCode = versionCodeFromName(verName) ?: run {
                    RemoteLog.warn(TAG, "unparseable tag $tag")
                    return@use null
                }
                // Compare both sides on the same scale (semver → packed int).
                // BuildConfig.VERSION_CODE is a small sequential number (17),
                // while remoteCode encodes "x.y.z" as x*10000+y*100+z (10007).
                // Earlier we were comparing 10007 > 17 every time, so the
                // dialog kept popping even on the newest install.
                val localCode = versionCodeFromName(BuildConfig.VERSION_NAME) ?: BuildConfig.VERSION_CODE
                if (remoteCode <= localCode) {
                    RemoteLog.debug(TAG, "up to date (local=$localCode remote=$remoteCode)")
                    return@use null
                }
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var sha256Url: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        if (name.equals(APK_NAME, ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                        } else if (name.equals("$APK_NAME.sha256", ignoreCase = true)) {
                            // Sibling checksum asset (e.g. "UltraTV-debug.apk.sha256").
                            sha256Url = a.optString("browser_download_url")
                        }
                    }
                }
                if (apkUrl.isNullOrBlank()) {
                    RemoteLog.warn(TAG, "no $APK_NAME asset on $tag")
                    return@use null
                }
                val info = UpdateInfo(tag, verName, remoteCode, apkUrl!!, notes, sha256Url)
                _state.value = info
                RemoteLog.info(TAG, "update available: $tag (code $remoteCode)")
                info
            }
        }.onFailure {
            RemoteLog.warn(TAG, "check failed: ${it.javaClass.simpleName} ${it.message}")
        }.getOrNull()
    }

    /**
     * Encodes a "x.y.z" versionName as one integer for comparison. Matches the
     * scheme used in build.gradle.kts (versionCode is bumped manually but the
     * versionName follows semver), so 1.0.5 → 10005.
     */
    private fun versionCodeFromName(name: String): Int? {
        val parts = name.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return null
        val major = parts.getOrNull(0) ?: 0
        val minor = parts.getOrNull(1) ?: 0
        val patch = parts.getOrNull(2) ?: 0
        return major * 10_000 + minor * 100 + patch
    }

    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            val apk = downloadApk(ctx, info, onProgress)
            verifyChecksum(apk, info)
            installApk(ctx, apk)
        }
    }

    /**
     * If the release published a "<apk>.sha256" sibling asset, fetch the
     * expected digest and compare it against the SHA-256 of the downloaded
     * file; abort (delete + throw) on mismatch. When no checksum is published
     * we proceed but log it — keeps older releases installable (backward
     * compat). Signing is handled separately by the OS installer; this only
     * guards against a corrupted / tampered download.
     */
    private fun verifyChecksum(apk: File, info: UpdateInfo) {
        val url = info.sha256Url
        if (url.isNullOrBlank()) {
            RemoteLog.warn(TAG, "no sha256 asset for ${info.tag}; installing unverified")
            return
        }
        val expected = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "sha256 fetch ${resp.code}" }
                // Accept "<hex>" or "<hex>  filename" (sha256sum format).
                resp.body?.string()?.trim()?.substringBefore(' ')?.lowercase().orEmpty()
            }
        }.getOrElse {
            // Couldn't retrieve the published checksum — fail closed: a release
            // that ships a checksum is asserting integrity matters.
            apk.delete()
            error("checksum download failed for ${info.tag}: ${it.message}")
        }
        if (expected.isBlank()) {
            apk.delete()
            error("empty checksum for ${info.tag}")
        }
        val actual = apk.inputStream().use { input ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!actual.equals(expected, ignoreCase = true)) {
            apk.delete()
            RemoteLog.error(TAG, "checksum mismatch for ${info.tag}: expected=$expected actual=$actual")
            error("APK checksum mismatch — install aborted")
        }
        RemoteLog.info(TAG, "checksum verified for ${info.tag}")
    }

    private fun downloadApk(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        // Clean previous APKs so we don't fill the disk on repeat updates.
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ultra-tv-${info.versionName}.apk")
        val req = Request.Builder().url(info.apkUrl).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "download failed ${resp.code}" }
            val body = resp.body ?: error("empty body")
            val total = body.contentLength().coerceAtLeast(1L)
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        sent += n
                        onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        }
        RemoteLog.info(TAG, "downloaded ${out.length()} bytes for ${info.tag}")
        return out
    }

    /**
     * Hands the downloaded APK to the system's standard install activity via a
     * FileProvider content:// URI. This is the most compatible path across
     * Android TV firmwares (Fire TV, Mecool, vivo boxes) — some of them reject
     * PackageInstaller sessions from non-system apps with
     * "cannot automatically move to internal storage". The OS installer prompts
     * the user once (Allow this source) and handles everything itself.
     */
    private fun installApk(ctx: Context, apk: File) {
        val authority = "${ctx.packageName}.updates"
        val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // On Android 8+ the per-app "install unknown apps" toggle gates this.
            // The system shows its own prompt if the user hasn't allowed it yet.
        }
        RemoteLog.info("update", "launching system installer for ${apk.name} (${apk.length()} B)")
        ctx.startActivity(intent)
    }

}
