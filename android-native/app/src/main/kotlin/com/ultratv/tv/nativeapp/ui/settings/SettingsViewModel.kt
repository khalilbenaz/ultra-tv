package com.ultratv.tv.nativeapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val providers: List<ProviderEntity> = emptyList(),
    val syncing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: ProviderRepository,
    private val remoteConfig: com.ultratv.tv.nativeapp.data.config.RemoteConfigImporter,
    private val deviceMac: com.ultratv.tv.nativeapp.data.config.DeviceMac,
    private val prefs: com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore,
) : ViewModel() {

    val deviceMacAddress: String = deviceMac.mac

    /**
     * Effective Worker URL: user override (DataStore) takes precedence over
     * the project default below. Each user can still self-host their own
     * worker and paste its URL in Settings.
     */
    val workerBaseUrl: StateFlow<String> = prefs.flow
        .map { it.workerBaseUrl.ifBlank { DEFAULT_WORKER_URL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_WORKER_URL)

    companion object {
        // Default project-hosted Cloudflare Worker. Anyone using this default
        // shares the same KV namespace (rows are scoped per MAC, so it's
        // technically isolated, but the admin password can read everything —
        // self-host for full privacy).
        const val DEFAULT_WORKER_URL = "https://ultratv-config.khalilbenaz.workers.dev"
    }

    fun saveWorkerBase(url: String) {
        viewModelScope.launch { prefs.setWorkerBase(url) }
    }

    fun importByMac(workerBase: String) {
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Asking dashboard for config matching ${deviceMac.mac}…"
            try {
                val res = remoteConfig.importByMac(workerBase, deviceMac.mac) { _message.value = it }
                // Ensure something becomes default so the UI has a provider to use.
                if (res.imported > 0 && repo.firstActive() == null) {
                    repo.observeProviders().first().firstOrNull()?.id?.let { repo.setDefault(it) }
                }
                _message.value = when {
                    res.imported == 0 && res.errors.isEmpty() ->
                        "Dashboard knows no config for this MAC. Go to ${workerBase.trimEnd('/')} and provision ${deviceMac.mac}."
                    res.errors.isEmpty() -> "Imported ${res.imported} provider(s) ✓"
                    else -> "Imported ${res.imported} provider(s) · ${res.errors.size} error(s): ${res.errors.first()}"
                }
            } catch (t: Throwable) {
                _message.value = "Error: ${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun importFromRemoteConfig(url: String) {
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Fetching config from $url…"
            try {
                val res = remoteConfig.importFromUrl(url) { _message.value = it }
                if (res.imported > 0 && repo.firstActive() == null) {
                    repo.observeProviders().first().firstOrNull()?.id?.let { repo.setDefault(it) }
                }
                val errs = if (res.errors.isEmpty()) "" else "  ·  ${res.errors.size} error(s): ${res.errors.first()}"
                _message.value = "Imported ${res.imported} provider(s)$errs"
            } catch (t: Throwable) {
                _message.value = "Error: ${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    private val _syncing = MutableStateFlow(false)

    val providers: StateFlow<List<ProviderEntity>> =
        repo.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val message: StateFlow<String?> = _message.asStateFlow()
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Promotes the new provider to default iff nothing else is active yet. */
    private suspend fun makeDefaultIfNone(newId: Long) {
        if (repo.firstActive() == null) repo.setDefault(newId)
    }

    fun setDefault(id: Long) {
        viewModelScope.launch {
            repo.setDefault(id)
            _message.value = "Default provider changed."
        }
    }

    fun addAndSync(name: String, baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Adding provider…"
            try {
                val id = repo.addXtream(name, baseUrl, username, password)
                makeDefaultIfNone(id)
                _message.value = "Syncing live channels…"
                val n = repo.syncAll(id) { _message.value = it }
                _message.value = "Done — $n channels"
            } catch (t: Throwable) {
                _message.value = "Error: ${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun addM3uLocal(name: String, label: String, text: String) {
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Importing local M3U…"
            try {
                val id = repo.addM3uFromText(name, label, text)
                makeDefaultIfNone(id)
                _message.value = "Imported — restart the Live tab to see channels."
            } catch (t: Throwable) {
                _message.value = "Error: ${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun addStalkerAndSync(name: String, portalUrl: String, mac: String) {
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Adding Stalker portal…"
            try {
                val id = repo.addStalker(name, portalUrl, mac)
                makeDefaultIfNone(id)
                val n = repo.syncAll(id) { _message.value = it }
                _message.value = "Done — $n channels"
            } catch (t: Throwable) {
                _message.value = "Error: ${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun addM3uAndSync(name: String, url: String) {
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Adding M3U provider…"
            try {
                val id = repo.addM3u(name, url)
                makeDefaultIfNone(id)
                val n = repo.syncAll(id) { _message.value = it }
                _message.value = "Done — $n channels"
            } catch (t: Throwable) {
                _message.value = "Error: ${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun resync(providerId: Long) {
        viewModelScope.launch {
            _syncing.value = true
            try {
                val n = repo.syncAll(providerId) { _message.value = it }
                _message.value = "Re-synced — $n channels"
            } catch (t: Throwable) {
                _message.value = "Error: ${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repo.delete(id)
            _message.value = "Provider deleted"
        }
    }
}
