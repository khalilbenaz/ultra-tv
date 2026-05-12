package com.ultratv.tv.nativeapp.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefsDs by preferencesDataStore(name = "user_prefs")

enum class SidebarPosition { LEFT, TOP }
enum class AppTheme { DARK, AMOLED, BLUE }
enum class DefaultPlayer { INTERNAL, EXTERNAL }

data class UserPrefs(
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    val theme: AppTheme = AppTheme.DARK,
    val defaultPlayer: DefaultPlayer = DefaultPlayer.INTERNAL,
    val autoSyncOnLaunch: Boolean = false,
    val showChannelNumbers: Boolean = true,
    val hideAdultCategories: Boolean = false,
    val resumePlayback: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    /** Launch the app automatically once Android TV finishes booting. */
    val launchAtBoot: Boolean = false,
    /** On app start, automatically open the most recently watched item. */
    val autoPlayLastOnLaunch: Boolean = false,
)

@Singleton
class UserPreferencesStore @Inject constructor(@ApplicationContext private val ctx: Context) {
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
    }

    val flow: Flow<UserPrefs> = ctx.userPrefsDs.data.map { p ->
        UserPrefs(
            sidebarPosition = enumValueOf<SidebarPosition>(p[Keys.sidebar] ?: SidebarPosition.LEFT.name),
            theme = enumValueOf<AppTheme>(p[Keys.theme] ?: AppTheme.DARK.name),
            defaultPlayer = enumValueOf<DefaultPlayer>(p[Keys.player] ?: DefaultPlayer.INTERNAL.name),
            autoSyncOnLaunch = p[Keys.autoSync] ?: false,
            showChannelNumbers = p[Keys.channelNums] ?: true,
            hideAdultCategories = p[Keys.hideAdult] ?: false,
            resumePlayback = p[Keys.resume] ?: true,
            autoPlayNextEpisode = p[Keys.autoPlayNext] ?: true,
            launchAtBoot = p[Keys.launchAtBoot] ?: false,
            autoPlayLastOnLaunch = p[Keys.autoPlayLast] ?: false,
        )
    }

    suspend fun setSidebar(pos: SidebarPosition) = update { it[Keys.sidebar] = pos.name }
    suspend fun setTheme(t: AppTheme) = update { it[Keys.theme] = t.name }
    suspend fun setDefaultPlayer(p: DefaultPlayer) = update { it[Keys.player] = p.name }
    suspend fun setAutoSync(v: Boolean) = update { it[Keys.autoSync] = v }
    suspend fun setShowChannelNumbers(v: Boolean) = update { it[Keys.channelNums] = v }
    suspend fun setHideAdult(v: Boolean) = update { it[Keys.hideAdult] = v }
    suspend fun setResumePlayback(v: Boolean) = update { it[Keys.resume] = v }
    suspend fun setAutoPlayNext(v: Boolean) = update { it[Keys.autoPlayNext] = v }
    suspend fun setLaunchAtBoot(v: Boolean) = update { it[Keys.launchAtBoot] = v }
    suspend fun setAutoPlayLast(v: Boolean) = update { it[Keys.autoPlayLast] = v }

    private suspend inline fun update(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ctx.userPrefsDs.edit { block(it) }
    }
}
