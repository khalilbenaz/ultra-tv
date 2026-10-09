package com.ultratv.tv.nativeapp.ui.player.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Mémoire par chaîne : combinaison moteur/décodage et préréglage de tampon qui ont fonctionné. */
interface ChannelPlaybackMemory {
    fun combo(channelKey: String): Combo?
    fun bufferPreset(channelKey: String): BufferPreset?
    fun remember(channelKey: String, combo: Combo?, preset: BufferPreset?)
    /** Langue audio (ISO 639-1) choisie à la main pour ce titre / cette chaîne. */
    fun audioLang(channelKey: String): String? = null
    fun rememberAudioLang(channelKey: String, lang: String) {}
}

/** Version en mémoire (tests). */
class InMemoryChannelPlaybackMemory : ChannelPlaybackMemory {
    private val combos = mutableMapOf<String, Combo>()
    private val presets = mutableMapOf<String, BufferPreset>()
    private val audio = mutableMapOf<String, String>()
    override fun audioLang(channelKey: String) = audio[channelKey]
    override fun rememberAudioLang(channelKey: String, lang: String) { audio[channelKey] = lang }
    override fun combo(channelKey: String) = combos[channelKey]
    override fun bufferPreset(channelKey: String) = presets[channelKey]
    override fun remember(channelKey: String, combo: Combo?, preset: BufferPreset?) {
        if (combo != null) combos[channelKey] = combo
        if (preset != null) presets[channelKey] = preset
    }
}

/** SharedPreferences : au plus [MAX] chaînes retenues (les plus anciennes sont oubliées). */
@Singleton
class PrefsChannelPlaybackMemory @Inject constructor(@ApplicationContext ctx: Context) : ChannelPlaybackMemory {
    private val sp = ctx.getSharedPreferences("channel_playback", Context.MODE_PRIVATE)

    override fun combo(channelKey: String) = Combo.decode(sp.getString("c:$channelKey", null))
    override fun bufferPreset(channelKey: String) = sp.getString("b:$channelKey", null)?.let { runCatching { BufferPreset.valueOf(it) }.getOrNull() }

    override fun audioLang(channelKey: String) = sp.getString("a:$channelKey", null)

    @Synchronized
    override fun rememberAudioLang(channelKey: String, lang: String) {
        sp.edit().putString("a:$channelKey", lang).putLong("t:$channelKey", System.currentTimeMillis()).apply()
    }

    @Synchronized
    override fun remember(channelKey: String, combo: Combo?, preset: BufferPreset?) {
        val e = sp.edit()
        combo?.let { e.putString("c:$channelKey", it.encode()) }
        preset?.let { e.putString("b:$channelKey", it.name) }
        e.putLong("t:$channelKey", System.currentTimeMillis())
        // Purge : on ne garde que les MAX entrées les plus récentes.
        val stamps = sp.all.filterKeys { it.startsWith("t:") }.mapValues { (it.value as? Long) ?: 0L }
        if (stamps.size >= MAX) {
            stamps.entries.sortedBy { it.value }.take(stamps.size - MAX + 1).forEach { (k, _) ->
                val id = k.removePrefix("t:"); e.remove("t:$id").remove("c:$id").remove("b:$id").remove("a:$id")
            }
        }
        e.apply()
    }

    private companion object { const val MAX = 500 }
}
