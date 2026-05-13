package com.ultratv.tv.nativeapp.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Lightweight inline translation table. We deliberately avoid the Android
 * resource framework because most of the UI text is inline in Compose;
 * externalising every literal would be a multi-day rewrite. Strings not in
 * the table fall through to their English literal — partial coverage is
 * better than no coverage.
 */
enum class AppLang(val code: String, val displayName: String, val rtl: Boolean = false) {
    System("system", "System (auto)"),
    English("en", "English"),
    French("fr", "Français"),
    Spanish("es", "Español"),
    Arabic("ar", "العربية", rtl = true);

    companion object {
        fun fromCode(code: String): AppLang = entries.firstOrNull { it.code == code } ?: System
    }
}

data class Strings(
    // Nav
    val navHome: String,
    val navLive: String,
    val navGuide: String,
    val navMovies: String,
    val navSeries: String,
    val navFavorites: String,
    val navSearch: String,
    val navCategories: String,
    val navMultiview: String,
    val navRecordings: String,
    val navSettings: String,

    // Home
    val homeWelcome: String,
    val homeSubtitle: String,
    val homeContinueWatching: String,
    val homeRecentlyWatched: String,
    val homeFeaturedChannels: String,
    val homeFeaturedMovies: String,
    val homeFeaturedSeries: String,
    val onboardingMacLabel: String,
    val onboardingOpenSettings: String,
    val onboardingFirstTime: String,
    val onboardingTwoPaths: String,
    val onboardingPathManual: String,
    val onboardingPathCloud: String,

    // Settings sections
    val settingsTitle: String,
    val settingsDisplay: String,
    val settingsParental: String,
    val settingsBackup: String,
    val settingsLanguage: String,
    val settingsTheme: String,
    val settingsMenuPosition: String,
    val settingsAutoSync: String,
    val settingsRefreshPlaylists: String,

    // Movies / Series labels
    val movieDetailPlot: String,
    val seriesDetailEpisodes: String,
    val moviesTitle: String,
    val seriesTitle: String,
    val noMovies: String,
    val noSeries: String,

    // Player overlay
    val playerSleep: String,
    val playerStats: String,
    val playerTracks: String,
    val playerDisplay: String,
    val playerExternal: String,
    val playerCast: String,
    val playerRecord: String,
    val playerAspect: String,
    val playerSpeed: String,
    val playerZapHint: String,

    // Search
    val searchPlaceholder: String,
    val searchRecent: String,
    val searchClear: String,
    val searchNoMatches: String,

    // Recordings
    val recordingsTitle: String,
    val recordingsEmpty: String,
    val recordingStatusQueued: String,
    val recordingStatusRunning: String,
    val recordingStatusDone: String,
    val recordingStatusFailed: String,
    val recordingStatusCancelled: String,

    // Common buttons
    val live: String,
    val movies: String,
    val series: String,
    val categories: String,
    val tvGuide: String,
    val favorites: String,
    val play: String,
    val resume: String,
    val cancel: String,
    val close: String,
    val save: String,
    val delete: String,
    val confirm: String,
    val dismiss: String,
    val change: String,
)

private val EN = Strings(
    navHome = "Home", navLive = "Live TV", navGuide = "Guide", navMovies = "Movies",
    navSeries = "Series", navFavorites = "Favorites", navSearch = "Search",
    navCategories = "Categories", navMultiview = "Multi-View",
    navRecordings = "Recordings", navSettings = "Settings",

    homeWelcome = "Welcome to Ultra TV",
    homeSubtitle = "Native build · D-pad ready",
    homeContinueWatching = "Continue watching",
    homeRecentlyWatched = "Recently watched",
    homeFeaturedChannels = "Featured channels",
    homeFeaturedMovies = "Movies",
    homeFeaturedSeries = "Series",
    onboardingMacLabel = "Your device MAC:",
    onboardingOpenSettings = "Open Settings",
    onboardingFirstTime = "First-time setup",
    onboardingTwoPaths = "Two paths to add a provider:",
    onboardingPathManual = "1. Open Settings → +Xtream / +M3U / +M3U file / +Stalker, fill in the form.",
    onboardingPathCloud = "2. Self-host the Cloudflare Worker from cloudflare-config/, paste this MAC in its dashboard, then in Settings → Set worker URL → Sync from cloud.",

    settingsTitle = "Settings", settingsDisplay = "Display & playback",
    settingsParental = "Parental controls", settingsBackup = "Backup & restore",
    settingsLanguage = "Language", settingsTheme = "Theme",
    settingsMenuPosition = "Menu position", settingsAutoSync = "Auto-sync on launch",
    settingsRefreshPlaylists = "Refresh playlists",

    movieDetailPlot = "Plot", seriesDetailEpisodes = "Episodes",
    moviesTitle = "Movies", seriesTitle = "Series",
    noMovies = "No movies — add a provider in Settings and re-sync.",
    noSeries = "No series — add a provider in Settings and re-sync.",

    playerSleep = "Sleep", playerStats = "Stats", playerTracks = "Tracks",
    playerDisplay = "Display", playerExternal = "External player",
    playerCast = "Cast", playerRecord = "Record",
    playerAspect = "Aspect", playerSpeed = "Speed",
    playerZapHint = "▲ ▼ to zap channels",

    searchPlaceholder = "Type to search channels, movies, series…",
    searchRecent = "Recent:", searchClear = "Clear",
    searchNoMatches = "No matches.",

    recordingsTitle = "Recordings",
    recordingsEmpty = "No recordings yet. Open a movie or episode and press the ⏺ Record button to queue a download.",
    recordingStatusQueued = "Queued",
    recordingStatusRunning = "Downloading…",
    recordingStatusDone = "Saved",
    recordingStatusFailed = "Failed",
    recordingStatusCancelled = "Cancelled",

    live = "Live TV", movies = "Movies", series = "Series", categories = "Categories",
    tvGuide = "TV Guide", favorites = "Favorites",
    play = "Play", resume = "Resume", cancel = "Cancel", close = "Close",
    save = "Save", delete = "Delete", confirm = "Confirm",
    dismiss = "Dismiss", change = "Change",
)

private val FR = Strings(
    navHome = "Accueil", navLive = "TV en direct", navGuide = "Guide", navMovies = "Films",
    navSeries = "Séries", navFavorites = "Favoris", navSearch = "Recherche",
    navCategories = "Catégories", navMultiview = "Multi-vue",
    navRecordings = "Enregistrements", navSettings = "Paramètres",

    homeWelcome = "Bienvenue dans Ultra TV",
    homeSubtitle = "Build native · prêt pour la télécommande",
    homeContinueWatching = "Continuer à regarder",
    homeRecentlyWatched = "Récemment regardé",
    homeFeaturedChannels = "Chaînes en vedette",
    homeFeaturedMovies = "Films",
    homeFeaturedSeries = "Séries",
    onboardingMacLabel = "MAC de l'appareil :",
    onboardingOpenSettings = "Ouvrir les paramètres",
    onboardingFirstTime = "Première configuration",
    onboardingTwoPaths = "Deux façons d'ajouter un fournisseur :",
    onboardingPathManual = "1. Ouvre Paramètres → +Xtream / +M3U / +M3U fichier / +Stalker et remplis le formulaire.",
    onboardingPathCloud = "2. Héberge le worker Cloudflare depuis cloudflare-config/, colle ce MAC dans son tableau de bord, puis Paramètres → Définir l'URL du worker → Sync depuis le cloud.",

    settingsTitle = "Paramètres", settingsDisplay = "Affichage et lecture",
    settingsParental = "Contrôle parental", settingsBackup = "Sauvegarde et restauration",
    settingsLanguage = "Langue", settingsTheme = "Thème",
    settingsMenuPosition = "Position du menu", settingsAutoSync = "Sync auto au lancement",
    settingsRefreshPlaylists = "Rafraîchir les playlists",

    movieDetailPlot = "Synopsis", seriesDetailEpisodes = "Épisodes",
    moviesTitle = "Films", seriesTitle = "Séries",
    noMovies = "Aucun film — ajoute un fournisseur dans les paramètres puis re-sync.",
    noSeries = "Aucune série — ajoute un fournisseur dans les paramètres puis re-sync.",

    playerSleep = "Veille", playerStats = "Stats", playerTracks = "Pistes",
    playerDisplay = "Affichage", playerExternal = "Lecteur externe",
    playerCast = "Cast", playerRecord = "Enregistrer",
    playerAspect = "Format", playerSpeed = "Vitesse",
    playerZapHint = "▲ ▼ pour zapper",

    searchPlaceholder = "Saisis pour chercher chaînes, films, séries…",
    searchRecent = "Récents :", searchClear = "Effacer",
    searchNoMatches = "Aucun résultat.",

    recordingsTitle = "Enregistrements",
    recordingsEmpty = "Aucun enregistrement pour l'instant. Ouvre un film ou un épisode et appuie sur ⏺ Enregistrer.",
    recordingStatusQueued = "En file",
    recordingStatusRunning = "Téléchargement…",
    recordingStatusDone = "Enregistré",
    recordingStatusFailed = "Échec",
    recordingStatusCancelled = "Annulé",

    live = "TV en direct", movies = "Films", series = "Séries", categories = "Catégories",
    tvGuide = "Guide TV", favorites = "Favoris",
    play = "Lecture", resume = "Reprendre", cancel = "Annuler", close = "Fermer",
    save = "Enregistrer", delete = "Supprimer", confirm = "Confirmer",
    dismiss = "Retirer", change = "Modifier",
)

private val ES = Strings(
    navHome = "Inicio", navLive = "TV en vivo", navGuide = "Guía", navMovies = "Películas",
    navSeries = "Series", navFavorites = "Favoritos", navSearch = "Buscar",
    navCategories = "Categorías", navMultiview = "Multi-vista",
    navRecordings = "Grabaciones", navSettings = "Ajustes",

    homeWelcome = "Bienvenido a Ultra TV",
    homeSubtitle = "Build nativo · listo para mando a distancia",
    homeContinueWatching = "Continuar viendo",
    homeRecentlyWatched = "Vistos recientemente",
    homeFeaturedChannels = "Canales destacados",
    homeFeaturedMovies = "Películas",
    homeFeaturedSeries = "Series",
    onboardingMacLabel = "MAC del dispositivo:",
    onboardingOpenSettings = "Abrir ajustes",
    onboardingFirstTime = "Configuración inicial",
    onboardingTwoPaths = "Dos formas de añadir un proveedor:",
    onboardingPathManual = "1. Abre Ajustes → +Xtream / +M3U / +M3U archivo / +Stalker y rellena el formulario.",
    onboardingPathCloud = "2. Aloja el worker de Cloudflare desde cloudflare-config/, pega esta MAC en su panel y luego Ajustes → Definir URL del worker → Sync desde la nube.",

    settingsTitle = "Ajustes", settingsDisplay = "Pantalla y reproducción",
    settingsParental = "Control parental", settingsBackup = "Copia y restauración",
    settingsLanguage = "Idioma", settingsTheme = "Tema",
    settingsMenuPosition = "Posición del menú", settingsAutoSync = "Sync auto al inicio",
    settingsRefreshPlaylists = "Actualizar listas",

    movieDetailPlot = "Sinopsis", seriesDetailEpisodes = "Episodios",
    moviesTitle = "Películas", seriesTitle = "Series",
    noMovies = "Sin películas — añade un proveedor en ajustes y vuelve a sincronizar.",
    noSeries = "Sin series — añade un proveedor en ajustes y vuelve a sincronizar.",

    playerSleep = "Suspender", playerStats = "Stats", playerTracks = "Pistas",
    playerDisplay = "Pantalla", playerExternal = "Reproductor externo",
    playerCast = "Cast", playerRecord = "Grabar",
    playerAspect = "Aspecto", playerSpeed = "Velocidad",
    playerZapHint = "▲ ▼ para cambiar de canal",

    searchPlaceholder = "Escribe para buscar canales, películas, series…",
    searchRecent = "Recientes:", searchClear = "Limpiar",
    searchNoMatches = "Sin coincidencias.",

    recordingsTitle = "Grabaciones",
    recordingsEmpty = "Aún no hay grabaciones. Abre una película o episodio y pulsa ⏺ Grabar.",
    recordingStatusQueued = "En cola",
    recordingStatusRunning = "Descargando…",
    recordingStatusDone = "Guardado",
    recordingStatusFailed = "Falló",
    recordingStatusCancelled = "Cancelado",

    live = "TV en vivo", movies = "Películas", series = "Series", categories = "Categorías",
    tvGuide = "Guía TV", favorites = "Favoritos",
    play = "Reproducir", resume = "Reanudar", cancel = "Cancelar", close = "Cerrar",
    save = "Guardar", delete = "Eliminar", confirm = "Confirmar",
    dismiss = "Descartar", change = "Cambiar",
)

private val AR = Strings(
    navHome = "الرئيسية", navLive = "البث المباشر", navGuide = "الدليل", navMovies = "الأفلام",
    navSeries = "المسلسلات", navFavorites = "المفضلة", navSearch = "بحث",
    navCategories = "الفئات", navMultiview = "عرض متعدد",
    navRecordings = "التسجيلات", navSettings = "الإعدادات",

    homeWelcome = "مرحبًا بكم في Ultra TV",
    homeSubtitle = "نسخة أصلية · جاهزة لجهاز التحكم",
    homeContinueWatching = "متابعة المشاهدة",
    homeRecentlyWatched = "شوهد مؤخرًا",
    homeFeaturedChannels = "قنوات مميزة",
    homeFeaturedMovies = "الأفلام",
    homeFeaturedSeries = "المسلسلات",
    onboardingMacLabel = "عنوان MAC للجهاز:",
    onboardingOpenSettings = "فتح الإعدادات",
    onboardingFirstTime = "الإعداد الأول",
    onboardingTwoPaths = "طريقتان لإضافة موفّر:",
    onboardingPathManual = "1. افتح الإعدادات ← +Xtream / +M3U / +M3U ملف / +Stalker واملأ النموذج.",
    onboardingPathCloud = "2. استضف Cloudflare Worker من cloudflare-config/، ألصق عنوان MAC هذا في لوحة التحكم، ثم الإعدادات ← تعيين رابط الـ worker ← مزامنة من السحابة.",

    settingsTitle = "الإعدادات", settingsDisplay = "العرض والتشغيل",
    settingsParental = "الرقابة الأبوية", settingsBackup = "النسخ الاحتياطي والاستعادة",
    settingsLanguage = "اللغة", settingsTheme = "السمة",
    settingsMenuPosition = "موضع القائمة", settingsAutoSync = "المزامنة التلقائية عند البدء",
    settingsRefreshPlaylists = "تحديث القوائم",

    movieDetailPlot = "القصة", seriesDetailEpisodes = "الحلقات",
    moviesTitle = "الأفلام", seriesTitle = "المسلسلات",
    noMovies = "لا توجد أفلام — أضف موفّرًا من الإعدادات ثم زامن.",
    noSeries = "لا توجد مسلسلات — أضف موفّرًا من الإعدادات ثم زامن.",

    playerSleep = "مؤقت النوم", playerStats = "إحصائيات", playerTracks = "المسارات",
    playerDisplay = "العرض", playerExternal = "مشغّل خارجي",
    playerCast = "بث", playerRecord = "تسجيل",
    playerAspect = "نسبة العرض", playerSpeed = "السرعة",
    playerZapHint = "▲ ▼ لتغيير القناة",

    searchPlaceholder = "اكتب للبحث عن قنوات، أفلام، مسلسلات…",
    searchRecent = "الأخيرة:", searchClear = "مسح",
    searchNoMatches = "لا توجد نتائج.",

    recordingsTitle = "التسجيلات",
    recordingsEmpty = "لا توجد تسجيلات بعد. افتح فيلمًا أو حلقة واضغط ⏺ تسجيل.",
    recordingStatusQueued = "في الانتظار",
    recordingStatusRunning = "جاري التحميل…",
    recordingStatusDone = "محفوظ",
    recordingStatusFailed = "فشل",
    recordingStatusCancelled = "أُلغي",

    live = "البث المباشر", movies = "الأفلام", series = "المسلسلات", categories = "الفئات",
    tvGuide = "دليل التلفاز", favorites = "المفضلة",
    play = "تشغيل", resume = "استئناف", cancel = "إلغاء", close = "إغلاق",
    save = "حفظ", delete = "حذف", confirm = "تأكيد",
    dismiss = "إهمال", change = "تغيير",
)

@Composable
fun stringsFor(lang: AppLang): Strings {
    val resolved = if (lang == AppLang.System) {
        val sys = LocalConfiguration.current.locales.get(0)?.language ?: "en"
        AppLang.entries.firstOrNull { it.code == sys } ?: AppLang.English
    } else lang
    return when (resolved) {
        AppLang.French -> FR
        AppLang.Spanish -> ES
        AppLang.Arabic -> AR
        else -> EN
    }
}

val LocalStrings = compositionLocalOf<Strings> { EN }
