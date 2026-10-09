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
import kotlinx.coroutines.sync.withLock
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
        /** APK choisi (par ABI ou universel) et, pour un APK par ABI, l'URL de SHA256SUMS.txt (vérification obligatoire). */
        val apkName: String = APK_NAME,
        val sumsUrl: String? = null,
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
                .header("Cache-Control", "no-cache")
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
                val assetsJson = json.optJSONArray("assets")
                val assets = buildList {
                    if (assetsJson != null) for (i in 0 until assetsJson.length()) {
                        val a = assetsJson.getJSONObject(i)
                        add(UpdateAssets.Asset(a.optString("name"), a.optString("browser_download_url")))
                    }
                }
                // APK adapté au processeur (plus léger) s'il est publié, sinon l'universel historique.
                val choice = UpdateAssets.choose(assets, verName, Build.SUPPORTED_ABIS.toList())
                if (choice == null || choice.apkUrl.isBlank()) {
                    RemoteLog.warn(TAG, "no installable APK asset on $tag")
                    return@use null
                }
                val info = UpdateInfo(tag, verName, remoteCode, choice.apkUrl, notes, choice.sha256Url, choice.apkName, choice.sumsUrl)
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

    /**
     * Un seul téléchargement à la fois : la mise à jour automatique (30 s après le lancement) et le bouton « Mettre à
     * jour » partageaient le même fichier ; le second appel vidait le dossier pendant que le premier écrivait (APK
     * tronqué → « empreinte différente », « package non valide »). Le second appel attend le premier et réutilise
     * l'APK déjà vérifié.
     */
    private val single = kotlinx.coroutines.sync.Mutex()

    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit = {}) {
        single.withLock {
            withContext(Dispatchers.IO) {
                val apk = fetchVerified(ctx, info, onProgress)
                if (!sameSigner(ctx, apk)) throw SignatureMismatchException()
                installApk(ctx, apk)
            }
        }
    }

    /** APK vérifié de cette version : réutilisé s'il l'est déjà, sinon téléchargé (une seconde tentative si corrompu). */
    internal fun fetchVerified(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit = {}): File =
        verifiedApk(ctx, info) ?: runCatching { downloadAndVerify(ctx, info, onProgress) }
            .getOrElse { first ->
                RemoteLog.warn(TAG, "retrying download for ${info.tag}: ${first.message}")
                downloadAndVerify(ctx, info, onProgress)
            }

    /** Pour les tests : même verrou que downloadAndInstall, sans signature ni installation. */
    internal suspend fun fetchVerifiedLocked(ctx: Context, info: UpdateInfo): File =
        single.withLock { withContext(Dispatchers.IO) { fetchVerified(ctx, info) } }

    private fun downloadAndVerify(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val apk = downloadApk(ctx, info, onProgress)
        verifyChecksum(apk, info)
        File(apk.path + ".ok").writeText(sha256Hex(apk))
        return apk
    }

    /** APK de cette version déjà téléchargé ET vérifié (marqueur `.ok` = empreinte au moment de la vérification). */
    private fun verifiedApk(ctx: Context, info: UpdateInfo): File? {
        val apk = File(File(ctx.filesDir, "updates"), "ultra-tv-${info.versionName}.apk")
        val ok = File(apk.path + ".ok")
        if (!apk.isFile || !ok.isFile) return null
        return apk.takeIf { runCatching { ok.readText().trim() == sha256Hex(it) }.getOrDefault(false) }
    }

    /**
     * Vérification SHA-256 OBLIGATOIRE : sans fichier `<apk>.sha256` publié sur la
     * release, ou avec un condensat qui ne correspond pas, l'APK est supprimé et
     * l'installation refusée (avant : « installing unverified »). Le condensat vient
     * de la même release, donc cela protège contre une corruption/un téléchargement
     * altéré, pas contre une release entièrement compromise : seule la signature de
     * l'APK (vérifiée par Android à l'installation) couvre ce cas.
     */
    private fun verifyChecksum(apk: File, info: UpdateInfo) {
        val url = info.sumsUrl ?: info.sha256Url
        if (url.isNullOrBlank()) {
            apk.delete()
            RemoteLog.error(TAG, "no sha256 asset for ${info.tag}; install refused")
            error("no SHA-256 published for ${info.tag} — install refused")
        }
        val expected = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "sha256 fetch ${resp.code}" }
                val text = resp.body?.string().orEmpty()
                if (info.sumsUrl != null) UpdateAssets.digestFor(text, info.apkName) else parseExpectedDigest(text)
            }
        }.getOrElse {
            apk.delete()
            error("checksum download failed for ${info.tag}: ${it.message}")
        }
        val actual = sha256Hex(apk)
        if (!digestMatches(expected, actual)) {
            apk.delete()
            RemoteLog.error(TAG, "checksum mismatch for ${info.tag}: expected=$expected actual=$actual")
            error("APK checksum mismatch — install aborted")
        }
        RemoteLog.info(TAG, "checksum verified for ${info.tag}")
    }

    /** Accepte `<hex>` ou `<hex>  fichier` (format sha256sum). Renvoie "" si illisible. */
    internal fun parseExpectedDigest(text: String): String {
        val token = text.trim().substringBefore(' ').substringBefore('\t').substringBefore('\n').lowercase()
        return if (token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' }) token else ""
    }

    internal fun digestMatches(expected: String, actual: String): Boolean =
        expected.isNotBlank() && java.security.MessageDigest.isEqual(
            expected.lowercase().toByteArray(), actual.lowercase().toByteArray(),
        )

    internal fun sha256Hex(file: File): String = file.inputStream().use { input ->
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadApk(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "ultra-tv-${info.versionName}.apk")
        // Anciennes versions supprimées (pas celle-ci : un autre appel peut l'avoir déjà vérifiée).
        dir.listFiles()?.filter { !it.name.startsWith(out.name) }?.forEach { it.delete() }
        // Écriture dans un fichier temporaire, renommé seulement une fois complet : jamais d'APK à moitié écrit.
        val part = File(dir, out.name + ".part")
        part.delete(); out.delete(); File(out.path + ".ok").delete()
        val req = Request.Builder().url(info.apkUrl).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "download failed ${resp.code}" }
            val body = resp.body ?: error("empty body")
            val expectedLength = body.contentLength()
            val total = expectedLength.coerceAtLeast(1L)
            body.byteStream().use { input ->
                part.outputStream().use { output ->
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
            if (expectedLength > 0 && part.length() != expectedLength) {
                part.delete()
                error("download incomplete: ${part.length()}/$expectedLength bytes")
            }
        }
        check(part.renameTo(out)) { "cannot finalize ${out.name}" }
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
    /**
     * Android refuse une mise à jour signée par une autre clé (« conflit avec un package
     * existant ») sans que l'app en soit informée. On compare AVANT de lancer l'installateur :
     * en cas d'écart, l'invite explique qu'il faut désinstaller puis réinstaller (une seule fois).
     * Dans le doute (certificats illisibles), on laisse l'installateur système trancher.
     */
    internal fun sameSigner(ctx: Context, apk: File): Boolean = runCatching {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return@runCatching true
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        fun certs(pi: android.content.pm.PackageInfo): Set<String> {
            @Suppress("DEPRECATION")
            val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) pi.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory } else pi.signatures
            return sigs.orEmpty().map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(java.util.Locale.ROOT, b) } }.toSet()
        }
        val a = certs(archive); val i = certs(installed)
        // Rotation de clé (lineage v3) : l'historique du nouvel APK contient l'ancienne clé.
        a.isEmpty() || i.isEmpty() || a.intersect(i).isNotEmpty()
    }.getOrDefault(true)

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

/** La mise à jour est signée par une autre clé : il faut désinstaller puis réinstaller. */
class SignatureMismatchException : IllegalStateException("update signed with a different key")
