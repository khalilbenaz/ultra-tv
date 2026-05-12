package com.ultratv.tv.nativeapp.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton holding "what's about to play". Filled by the calling screen
 * (Live / MovieDetail / SeriesDetail) right before navigating to the player,
 * then consumed by PlayerScreen to record history with proper context.
 *
 * Using a Hilt singleton (rather than nav arguments) keeps the player route
 * to just `url + title` — passing 6 fields through URL-encoded nav args was
 * brittle and noisy.
 */
@Singleton
class PlaybackContext @Inject constructor() {
    data class Item(
        val providerId: Long,
        val kind: String,          // "LIVE" | "MOVIE" | "EPISODE"
        val remoteId: String,
        val title: String,
        val poster: String?,
        val streamUrl: String,
        val parentRemoteId: String? = null,
    )

    private val _current = MutableStateFlow<Item?>(null)
    val current: StateFlow<Item?> = _current

    fun set(item: Item) { _current.value = item }
    fun clear() { _current.value = null }
}
