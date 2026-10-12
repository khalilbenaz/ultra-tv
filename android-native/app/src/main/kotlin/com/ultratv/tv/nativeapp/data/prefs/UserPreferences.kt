package com.ultratv.tv.nativeapp.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefsDs by preferencesDataStore(name = "user_prefs")

enum class SidebarPosition { LEFT, TOP }
enum class AppTheme {
    DARK, LIGHT, AUTO;

    companion object {
        /** Tolérant : les anciennes valeurs (AMOLED, BLUE, SYSTEM…) retombent sur Sombre, la valeur inconnue aussi. */
        fun parse(raw: String?): AppTheme = when (raw?.uppercase()) {
            "LIGHT" -> LIGHT
            "AUTO", "SYSTEM" -> AUTO
            else -> DARK
        }
    }
}
enum class DefaultPlayer { INTERNAL, EXTERNAL }

data class UserPrefs(
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    val theme: AppTheme = AppTheme.DARK,
    val defaultPlayer: DefaultPlayer = DefaultPlayer.INTERNAL,
    val autoSyncOnLaunch: Boolean = true,
    val showChannelNumbers: Boolean = true,
    val hideAdultCategories: Boolean = false,
    val resumePlayback: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    /** Launch the app automatically once Android TV finishes booting. */
    val launchAtBoot: Boolean = false,
    /** On app start, automatically open the most recently watched item. */
    val autoPlayLastOnLaunch: Boolean = false,
    /** Minimum hours between auto-syncs; 0 = sync on every launch. Only used
     *  when [autoSyncOnLaunch] is true. */
    val syncIntervalHours: Int = 0,
    /** Last successful auto-sync timestamp (ms). Internal; not user-visible. */
    val lastSyncAtMs: Long = 0L,
    /** Cloudflare Worker base URL used by "Sync from cloud". Empty until the
     *  user enters their own — never hard-coded in source. */
    val workerBaseUrl: String = "",
    /** Suppresses the onboarding wizard. Flipped to `true` when the user
     *  dismisses or completes it. */
    val hasSeenOnboarding: Boolean = false,
    /** UI language code: "system", "en", "fr", "es", "ar". */
    val language: String = "system",
    /** Fuseau horaire de l'horloge et du guide : "" = celui du système, sinon un identifiant (« GMT », « Africa/Casablanca »…). */
    val timeZone: String = "",
    /** Guide complémentaire (XMLTV) pour les chaînes que la source ne couvre pas : URL, "" = désactivé. */
    val extraEpg: String = com.ultratv.tv.nativeapp.data.xmltv.ExtraEpg.DEFAULT,
    /** Per-MAC password used when fetching config from the worker. Optional —
     *  empty for unprotected entries. Persists across launches; never logged. */
    val configPassword: String = "",
    /** Telemetry opt-in. When false, RemoteLog drops every event/crash POST
     *  silently. Defaults to true because the app surfaces this in Settings
     *  and the diagnostic flow is what keeps the redesign honest; flipping
     *  it off stops the dashboard cold for that install. */
    val telemetryEnabled: Boolean = true,
    /** Partage des langues et catégories affichées avec les autres appareils du compte cloud. */
    val syncDisplayPrefs: Boolean = true,

    // Playback / TV-quality knobs — exposed in Settings.
    /** Buffer target in seconds. Media3's default is 15 s, which is decent but
     *  too tight for shaky IPTV providers. We expose 8/15/30/60 chips. */
    val bufferSeconds: Int = 30,
    /** Auto-switch the TV's refresh rate to match the stream's frame rate
     *  (24/25/29.97/30/50/59.94/60). Avoids judder on motion. */
    val autoFrameRate: Boolean = true,
    /** Rendu vidéo : "auto" (TextureView sur NVIDIA Shield, SurfaceView ailleurs), "surface" ou "texture". */
    val videoSurface: String = "auto",
    /** Prefer the software (FFmpeg / libavcodec via system) decoder over the
     *  hardware one. Useful for channels with codec quirks the hardware
     *  refuses (e.g. HEVC main10 on cheap boxes). */
    val preferSoftwareDecoder: Boolean = false,
    /** EPG times shifted by ± N minutes. Some providers ship EPG at UTC while
     *  channels stream in local time; this lets the user nudge it. */
    val epgTimeOffsetMin: Int = 0,
    /** Optional SAF tree URI for a folder of local channel logos that override
     *  whatever the provider ships. Empty = no override. */
    val localLogosFolderUri: String = "",
    // ── Lecture : « auto » partout par défaut ; tout choix manuel est PRIORITAIRE sur l'automatique. ──
    /** "auto" | "exo" | "vlc" */
    val playerEngine: String = "auto",
    /** "auto" | "hw" | "sw" */
    val decoderMode: String = "auto",
    /** "auto" | "low_latency" | "balanced" | "stable" | "custom" */
    val bufferPreset: String = "auto",
    val bufMinSec: Int = 5,
    val bufMaxSec: Int = 30,
    val bufStartSec: Int = 2,
    val bufRebufferSec: Int = 3,
    val bufMaxMb: Int = 32,
    // ── Langues des contenus (CSV de codes ; vide = toutes) ──
    val languages: String = "",
    val includeMulti: Boolean = true,
    /** Qualité préférée des chaînes disponibles en plusieurs qualités : "auto" | "4k" | "fhd" | "hd" | "sd". */
    val preferredQuality: String = "auto",
    val includeUnknownLang: Boolean = true,
    // ── Synchronisation ──
    /** "auto" | "launch" | "scheduled" | "manual" */
    val syncMode: String = "auto",
    val syncHour: Int = 3,
    val syncLive: Boolean = true,
    val syncEpg: Boolean = true,
    val syncVod: Boolean = true,
    val syncSeries: Boolean = true,
    val syncUnmeteredOnly: Boolean = true,
)

@Singleton
class UserPreferencesStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val profiles: com.ultratv.tv.nativeapp.data.profile.ProfileRepository,
) {
    private object Keys {
        val sidebar = stringPreferencesKey("sidebar_position")
        val theme = stringPreferencesKey("theme")
        val player = stringPreferencesKey("default_player")
        val autoSync = booleanPreferencesKey("auto_sync_on_launch")
        val channelNums = booleanPreferencesKey("show_channel_numbers")
        val hideAdult = booleanPreferencesKey("hide_adult")
        val resume = booleanPreferencesKey("resume_playback")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val launchAtBoot = booleanPreferencesKey("launch_at_boot")
        val autoPlayLast = booleanPreferencesKey("auto_play_last_on_launch")
        val syncInterval = intPreferencesKey("sync_interval_hours")
        val lastSyncAt = longPreferencesKey("last_sync_at_ms")
        val workerBase = stringPreferencesKey("worker_base_url")
        val seenOnboarding = booleanPreferencesKey("has_seen_onboarding")
        val language = stringPreferencesKey("language")
        val timeZone = stringPreferencesKey("time_zone")
        val extraEpg = stringPreferencesKey("extra_epg")
        val configPassword = stringPreferencesKey("config_password")
        val telemetry = booleanPreferencesKey("telemetry_enabled")
        val syncDisplayPrefs = booleanPreferencesKey("sync_display_prefs")
        val bufferSec = intPreferencesKey("buffer_seconds")
        val autoFrameRate = booleanPreferencesKey("auto_frame_rate")
        val videoSurface = stringPreferencesKey("video_surface")
        val preferSwDec = booleanPreferencesKey("prefer_software_decoder")
        val epgOffsetMin = intPreferencesKey("epg_offset_min")
        val localLogosUri = stringPreferencesKey("local_logos_uri")
        val playerEngine = stringPreferencesKey("player_engine")
        val decoderMode = stringPreferencesKey("decoder_mode")
        val bufferPreset = stringPreferencesKey("buffer_preset")
        val bufMin = intPreferencesKey("buf_min_sec")
        val bufMax = intPreferencesKey("buf_max_sec")
        val bufStart = intPreferencesKey("buf_start_sec")
        val bufRebuffer = intPreferencesKey("buf_rebuffer_sec")
        val bufMb = intPreferencesKey("buf_max_mb")
        val languages = stringPreferencesKey("languages")
        val syncMode = stringPreferencesKey("sync_mode")
        val syncHour = intPreferencesKey("sync_hour")
        val syncLive = booleanPreferencesKey("sync_live")
        val syncEpg = booleanPreferencesKey("sync_epg")
        val syncVod = booleanPreferencesKey("sync_vod")
        val syncSeries = booleanPreferencesKey("sync_series")
        val syncUnmetered = booleanPreferencesKey("sync_unmetered_only")
        val includeMulti = booleanPreferencesKey("include_multi")
        val preferredQuality = stringPreferencesKey("preferred_quality")
        val includeUnknown = booleanPreferencesKey("include_unknown_lang")
    }

    /** Préférences EFFECTIVES : globales, surchargées par celles du profil courant (voir ProfilePrefs). */
    val flow: Flow<UserPrefs> = kotlinx.coroutines.flow.combine(globalFlow(), profiles.currentPrefs, profiles.current) { g, pp, prof ->
        pp.applyTo(g, isKids = prof?.isKids == true)
    }

    private fun globalFlow(): Flow<UserPrefs> = ctx.userPrefsDs.data.map { p ->
        UserPrefs(
            sidebarPosition = enumValueOf<SidebarPosition>(p[Keys.sidebar] ?: SidebarPosition.LEFT.name),
            theme = AppTheme.parse(p[Keys.theme]),
            defaultPlayer = enumValueOf<DefaultPlayer>(p[Keys.player] ?: DefaultPlayer.INTERNAL.name),
            autoSyncOnLaunch = p[Keys.autoSync] ?: true,
            showChannelNumbers = p[Keys.channelNums] ?: true,
            hideAdultCategories = p[Keys.hideAdult] ?: false,
            resumePlayback = p[Keys.resume] ?: true,
            autoPlayNextEpisode = p[Keys.autoPlayNext] ?: true,
            launchAtBoot = p[Keys.launchAtBoot] ?: false,
            autoPlayLastOnLaunch = p[Keys.autoPlayLast] ?: false,
            syncIntervalHours = p[Keys.syncInterval] ?: 0,
            lastSyncAtMs = p[Keys.lastSyncAt] ?: 0L,
            workerBaseUrl = p[Keys.workerBase] ?: "",
            hasSeenOnboarding = p[Keys.seenOnboarding] ?: false,
            language = p[Keys.language] ?: "system",
            timeZone = p[Keys.timeZone] ?: "",
            extraEpg = p[Keys.extraEpg] ?: com.ultratv.tv.nativeapp.data.xmltv.ExtraEpg.DEFAULT,
            configPassword = p[Keys.configPassword] ?: "",
            telemetryEnabled = p[Keys.telemetry] ?: true,
            syncDisplayPrefs = p[Keys.syncDisplayPrefs] ?: true,
            bufferSeconds = p[Keys.bufferSec] ?: 30,
            autoFrameRate = p[Keys.autoFrameRate] ?: true,
            videoSurface = p[Keys.videoSurface] ?: "auto",
            preferSoftwareDecoder = p[Keys.preferSwDec] ?: false,
            epgTimeOffsetMin = p[Keys.epgOffsetMin] ?: 0,
            localLogosFolderUri = p[Keys.localLogosUri] ?: "",
            playerEngine = p[Keys.playerEngine] ?: "auto",
            // Ancienne case « décodeur logiciel » : respectée tant que rien n'a été choisi dans le nouveau réglage.
            decoderMode = p[Keys.decoderMode] ?: if (p[Keys.preferSwDec] == true) "sw" else "auto",
            bufferPreset = p[Keys.bufferPreset] ?: "auto",
            bufMinSec = p[Keys.bufMin] ?: 5, bufMaxSec = p[Keys.bufMax] ?: 30, bufStartSec = p[Keys.bufStart] ?: 2,
            bufRebufferSec = p[Keys.bufRebuffer] ?: 3, bufMaxMb = p[Keys.bufMb] ?: 32,
            syncMode = p[Keys.syncMode] ?: "auto", syncHour = p[Keys.syncHour] ?: 3, syncLive = p[Keys.syncLive] ?: true, syncEpg = p[Keys.syncEpg] ?: true,
            syncVod = p[Keys.syncVod] ?: true, syncSeries = p[Keys.syncSeries] ?: true, syncUnmeteredOnly = p[Keys.syncUnmetered] ?: true,
            languages = p[Keys.languages] ?: "", includeMulti = p[Keys.includeMulti] ?: true, preferredQuality = p[Keys.preferredQuality] ?: "auto", includeUnknownLang = p[Keys.includeUnknown] ?: true,
        )
    }

    suspend fun setSidebar(pos: SidebarPosition) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.SIDEBAR, pos.name)
    suspend fun setTheme(t: AppTheme) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.THEME, t.name)
    suspend fun setDefaultPlayer(p: DefaultPlayer) = update { it[Keys.player] = p.name }
    suspend fun setAutoSync(v: Boolean) = update { it[Keys.autoSync] = v }
    suspend fun setShowChannelNumbers(v: Boolean) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.CHANNEL_NUMBERS, v.toString())
    suspend fun setHideAdult(v: Boolean) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.HIDE_ADULT, v.toString())
    suspend fun setResumePlayback(v: Boolean) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.RESUME, v.toString())
    suspend fun setAutoPlayNext(v: Boolean) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.AUTOPLAY_NEXT, v.toString())
    suspend fun setLaunchAtBoot(v: Boolean) = update { it[Keys.launchAtBoot] = v }
    suspend fun setAutoPlayLast(v: Boolean) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.AUTOPLAY_LAST, v.toString())
    suspend fun setSyncInterval(hours: Int) = update { it[Keys.syncInterval] = hours }
    suspend fun setLastSyncAt(ms: Long) = update { it[Keys.lastSyncAt] = ms }
    suspend fun setWorkerBase(url: String) = update { it[Keys.workerBase] = url.trim() }
    suspend fun markOnboardingSeen() = update { it[Keys.seenOnboarding] = true }
    suspend fun setLanguage(code: String) = update { it[Keys.language] = code }
    suspend fun setTimeZone(id: String) = update { it[Keys.timeZone] = id }
    suspend fun setExtraEpg(url: String) = update { it[Keys.extraEpg] = url }
    suspend fun setConfigPassword(pwd: String) = update { it[Keys.configPassword] = pwd }
    suspend fun setTelemetry(on: Boolean) = update { it[Keys.telemetry] = on }
    suspend fun setSyncDisplayPrefs(on: Boolean) = update { it[Keys.syncDisplayPrefs] = on }
    suspend fun setBufferSeconds(v: Int) = update { it[Keys.bufferSec] = v.coerceIn(5, 300) }
    suspend fun setAutoFrameRate(v: Boolean) = update { it[Keys.autoFrameRate] = v }
    suspend fun setVideoSurface(v: String) = update { it[Keys.videoSurface] = v }
    suspend fun setPreferSoftwareDecoder(v: Boolean) = update { it[Keys.preferSwDec] = v }
    suspend fun setEpgTimeOffsetMin(v: Int) = update { it[Keys.epgOffsetMin] = v.coerceIn(-720, 720) }
    suspend fun setLocalLogosFolderUri(uri: String) = update { it[Keys.localLogosUri] = uri }

    suspend fun setLanguages(csv: String) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.LANGUAGES, csv)
    suspend fun setPreferredQuality(v: String) = update { it[Keys.preferredQuality] = v }
    suspend fun setIncludeMulti(v: Boolean) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.INCLUDE_MULTI, v.toString())
    suspend fun setIncludeUnknownLang(v: Boolean) = profiles.putPref(com.ultratv.tv.nativeapp.data.profile.ProfilePrefs.INCLUDE_UNKNOWN, v.toString())
    suspend fun setSyncMode(v: String) = update { it[Keys.syncMode] = v }
    suspend fun setSyncHour(v: Int) = update { it[Keys.syncHour] = v.coerceIn(0, 23) }
    suspend fun setSyncPart(part: String, on: Boolean) = update {
        when (part) { "live" -> it[Keys.syncLive] = on; "epg" -> it[Keys.syncEpg] = on; "vod" -> it[Keys.syncVod] = on; "series" -> it[Keys.syncSeries] = on }
    }
    suspend fun setSyncUnmeteredOnly(v: Boolean) = update { it[Keys.syncUnmetered] = v }
    suspend fun setPlayerEngine(v: String) = update { it[Keys.playerEngine] = v }
    suspend fun setDecoderMode(v: String) = update { it[Keys.decoderMode] = v }
    suspend fun setBufferPreset(v: String) = update { it[Keys.bufferPreset] = v }
    suspend fun setCustomBuffer(minSec: Int, maxSec: Int, startSec: Int, rebufferSec: Int, maxMb: Int) = update {
        it[Keys.bufMin] = minSec.coerceIn(1, 120); it[Keys.bufMax] = maxSec.coerceIn(2, 300); it[Keys.bufStart] = startSec.coerceIn(1, 30)
        it[Keys.bufRebuffer] = rebufferSec.coerceIn(1, 60); it[Keys.bufMb] = maxMb.coerceIn(4, 256)
    }
    /** « Revenir en automatique » : efface tous les choix manuels de lecture. */
    suspend fun resetPlaybackToAuto() = update {
        it[Keys.playerEngine] = "auto"; it[Keys.decoderMode] = "auto"; it[Keys.bufferPreset] = "auto"; it[Keys.preferSwDec] = false
    }

    private suspend inline fun update(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ctx.userPrefsDs.edit { block(it) }
    }
}
