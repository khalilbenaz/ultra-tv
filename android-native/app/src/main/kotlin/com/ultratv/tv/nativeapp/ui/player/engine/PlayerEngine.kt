package com.ultratv.tv.nativeapp.ui.player.engine

import android.view.View
import kotlinx.coroutines.flow.Flow

/** Moteur de lecture. `AUTO` n'existe que comme préférence : une session tourne toujours sur EXO ou VLC. */
enum class EngineKind { EXO, VLC }
enum class EngineChoice { AUTO, EXO, VLC }
enum class DecoderMode { AUTO, HARDWARE, SOFTWARE }

/** Format d'image (pilule « Affichage »). */
enum class AspectMode { FIT, FILL, ZOOM, R16_9, R4_3 }

/** Classes d'erreurs de lecture, indépendantes du moteur. Jamais de message brut : il pourrait contenir l'URL. */
enum class PlayErrorKind {
    NETWORK,        // réseau coupé, délai dépassé, serveur 5xx
    REFUSED,        // 401 / 403 : identifiants refusés ou connexion unique déjà utilisée
    NOT_FOUND,      // 404 / 410 : la chaîne n'existe plus
    FORMAT,         // conteneur illisible / non pris en charge
    DECODER,        // décodeur absent ou en échec
    NO_PICTURE,     // le son passe mais aucune image
    BEHIND_LIVE,    // fenêtre du direct dépassée : on se recale
    UNKNOWN,
}

sealed interface EngineEvent {
    data object Buffering : EngineEvent
    data object Ready : EngineEvent
    data object FirstFrame : EngineEvent
    data object Ended : EngineEvent
    data class Error(val kind: PlayErrorKind) : EngineEvent
}

/** [language] : code ISO 639-1 normalisé si connu (sert à mémoriser le choix manuel par titre). */
data class TrackInfo(val id: String, val label: String, val selected: Boolean, val language: String? = null)

data class EngineStats(
    val resolution: String? = null,
    val videoCodec: String? = null,
    val frameRate: Float? = null,
    val videoBitrateKbps: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val bufferedSeconds: Int? = null,
    val droppedFrames: Int? = null,
    val hardwareDecoding: Boolean? = null,
)

/** Paramètres communs à tous les moteurs. */
data class EngineConfig(
    val decoder: DecoderMode,
    val buffer: BufferParams,
    val isLive: Boolean,
    val autoFrameRate: Boolean,
    val userAgent: String,
    /** Apparence des sous-titres (lot B2). */
    val subtitleStyle: com.ultratv.tv.nativeapp.data.subtitles.SubtitleStyle = com.ultratv.tv.nativeapp.data.subtitles.SubtitleStyle.DEFAULT,
    /** Langues préférées, ordonnées (codes ISO 639-1) : choix automatique de la piste audio / sous-titres. */
    val preferredAudio: List<String> = emptyList(),
    val preferredText: List<String> = emptyList(),
    /** Aucune piste de sous-titres choisie automatiquement (même marquée « par défaut » dans le flux). */
    val textOff: Boolean = false,
    /** Langue de l'interface (ISO 639-1) : noms de langues des pistes affichés dans cette langue. */
    val uiLanguage: String = java.util.Locale.getDefault().language,
)

/**
 * Interface commune des moteurs (Media3 / LibVLC). UN SEUL moteur est instancié à la fois : le fournisseur
 * n'autorise le plus souvent qu'une connexion, donc `release()` précède toujours l'ouverture suivante.
 */
interface PlayerEngine {
    val kind: EngineKind
    val config: EngineConfig
    /** Vue vidéo à placer dans la hiérarchie. */
    val view: View
    val events: Flow<EngineEvent>

    fun load(url: String, startPositionMs: Long = 0)
    /** Vrai si [load] peut enchaîner un autre flux sur le MÊME moteur (zapping sans recréer lecteur, vue et décodeurs). */
    val reusable: Boolean get() = false
    fun play()
    fun pause()
    fun seekTo(ms: Long)

    val isPlaying: Boolean
    val positionMs: Long
    /** -1 si inconnue (direct). */
    val durationMs: Long

    fun audioTracks(): List<TrackInfo>
    fun subtitleTracks(): List<TrackInfo>
    fun selectAudio(id: String)
    /** null = sous-titres désactivés. */
    fun selectSubtitle(id: String?)
    fun setAspect(mode: AspectMode)
    fun setSpeed(speed: Float)
    /** Vrai si le flux contient une piste vidéo (sinon : radio, pas d'alerte « aucune image »). */
    val hasVideo: Boolean
    /** Plafonne la qualité (adaptation au réseau et à l'écran) — sans effet sur les moteurs qui ne le permettent pas. */
    fun limitQuality(maxHeight: Int, maxBitrateBps: Int?)
    fun stats(): EngineStats

    /** Applique le style de sous-titres. Vrai = pris en compte tout de suite ; faux = au prochain démarrage du moteur. */
    fun applySubtitleStyle(style: com.ultratv.tv.nativeapp.data.subtitles.SubtitleStyle): Boolean = false
    /** Décale les sous-titres ; faux si le moteur ne sait pas (Media3). */
    fun setSubtitleDelay(ms: Int): Boolean = false
    /** Ajoute un fichier de sous-titres (SRT) à la lecture en cours et l'active ; faux si non pris en charge. */
    fun addExternalSubtitle(path: String): Boolean = false
    fun release()
}
