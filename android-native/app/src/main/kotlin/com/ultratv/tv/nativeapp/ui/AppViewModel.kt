package com.ultratv.tv.nativeapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.prefs.AppTheme
import com.ultratv.tv.nativeapp.data.prefs.DefaultPlayer
import com.ultratv.tv.nativeapp.data.prefs.SidebarPosition
import com.ultratv.tv.nativeapp.data.prefs.UserPrefs
import com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-wide preferences VM. Used by MainActivity to switch theme/layout and by
 *  SettingsScreen to mutate them. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val store: UserPreferencesStore,
) : ViewModel() {
    val prefs: StateFlow<UserPrefs> = store.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPrefs())

    fun setSidebar(pos: SidebarPosition) = viewModelScope.launch { store.setSidebar(pos) }
    fun setTheme(t: AppTheme) = viewModelScope.launch { store.setTheme(t) }
    fun setDefaultPlayer(p: DefaultPlayer) = viewModelScope.launch { store.setDefaultPlayer(p) }
    fun setAutoSync(v: Boolean) = viewModelScope.launch { store.setAutoSync(v) }
    fun setShowChannelNumbers(v: Boolean) = viewModelScope.launch { store.setShowChannelNumbers(v) }
    fun setHideAdult(v: Boolean) = viewModelScope.launch { store.setHideAdult(v) }
    fun setResumePlayback(v: Boolean) = viewModelScope.launch { store.setResumePlayback(v) }
    fun setAutoPlayNext(v: Boolean) = viewModelScope.launch { store.setAutoPlayNext(v) }
    fun setLaunchAtBoot(v: Boolean) = viewModelScope.launch { store.setLaunchAtBoot(v) }
    fun setAutoPlayLast(v: Boolean) = viewModelScope.launch { store.setAutoPlayLast(v) }
}
