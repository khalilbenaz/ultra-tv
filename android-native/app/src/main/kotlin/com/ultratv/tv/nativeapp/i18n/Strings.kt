package com.ultratv.tv.nativeapp.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Lightweight inline translation table. We deliberately avoid the Android
 * resource framework (values-fr / values-ar / …) because the bulk of UI text
 * is inline in Compose — externalising every literal would be a multi-day
 * rewrite. Instead we surface the most visible strings here, fetched via
 * `LocalStrings.current` in the composables that opt-in.
 *
 * For untranslated strings the UI keeps the English literal — Russian /
 * Spanish / etc users still see the app, just partially translated.
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
    val navHome: String,
    val navLive: String,
    val navGuide: String,
    val navMovies: String,
    val navSeries: String,
    val navFavorites: String,
    val navSearch: String,
    val navCategories: String,
    val navMultiview: String,
    val navSettings: String,

    val homeWelcome: String,
    val homeSubtitle: String,
    val homeContinueWatching: String,
    val homeRecentlyWatched: String,
    val homeFeaturedChannels: String,

    val settingsTitle: String,
    val settingsDisplay: String,
    val settingsParental: String,
    val settingsBackup: String,
    val settingsLanguage: String,

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
)

private val EN = Strings(
    navHome = "Home", navLive = "Live TV", navGuide = "Guide", navMovies = "Movies",
    navSeries = "Series", navFavorites = "Favorites", navSearch = "Search",
    navCategories = "Categories", navMultiview = "Multi-View", navSettings = "Settings",
    homeWelcome = "Welcome to Ultra TV",
    homeSubtitle = "Native build · D-pad ready",
    homeContinueWatching = "Continue watching",
    homeRecentlyWatched = "Recently watched",
    homeFeaturedChannels = "Featured channels",
    settingsTitle = "Settings", settingsDisplay = "Display & playback",
    settingsParental = "Parental controls", settingsBackup = "Backup & restore",
    settingsLanguage = "Language",
    live = "Live TV", movies = "Movies", series = "Series", categories = "Categories",
    tvGuide = "TV Guide", favorites = "Favorites",
    play = "Play", resume = "Resume", cancel = "Cancel", close = "Close",
    save = "Save", delete = "Delete", confirm = "Confirm",
)

private val FR = Strings(
    navHome = "Accueil", navLive = "TV en direct", navGuide = "Guide", navMovies = "Films",
    navSeries = "Séries", navFavorites = "Favoris", navSearch = "Recherche",
    navCategories = "Catégories", navMultiview = "Multi-vue", navSettings = "Paramètres",
    homeWelcome = "Bienvenue dans Ultra TV",
    homeSubtitle = "Build native · prêt pour la télécommande",
    homeContinueWatching = "Continuer à regarder",
    homeRecentlyWatched = "Récemment regardé",
    homeFeaturedChannels = "Chaînes en vedette",
    settingsTitle = "Paramètres", settingsDisplay = "Affichage et lecture",
    settingsParental = "Contrôle parental", settingsBackup = "Sauvegarde et restauration",
    settingsLanguage = "Langue",
    live = "TV en direct", movies = "Films", series = "Séries", categories = "Catégories",
    tvGuide = "Guide TV", favorites = "Favoris",
    play = "Lecture", resume = "Reprendre", cancel = "Annuler", close = "Fermer",
    save = "Enregistrer", delete = "Supprimer", confirm = "Confirmer",
)

private val ES = Strings(
    navHome = "Inicio", navLive = "TV en vivo", navGuide = "Guía", navMovies = "Películas",
    navSeries = "Series", navFavorites = "Favoritos", navSearch = "Buscar",
    navCategories = "Categorías", navMultiview = "Multi-vista", navSettings = "Ajustes",
    homeWelcome = "Bienvenido a Ultra TV",
    homeSubtitle = "Build nativo · listo para mando a distancia",
    homeContinueWatching = "Continuar viendo",
    homeRecentlyWatched = "Vistos recientemente",
    homeFeaturedChannels = "Canales destacados",
    settingsTitle = "Ajustes", settingsDisplay = "Pantalla y reproducción",
    settingsParental = "Control parental", settingsBackup = "Copia y restauración",
    settingsLanguage = "Idioma",
    live = "TV en vivo", movies = "Películas", series = "Series", categories = "Categorías",
    tvGuide = "Guía TV", favorites = "Favoritos",
    play = "Reproducir", resume = "Reanudar", cancel = "Cancelar", close = "Cerrar",
    save = "Guardar", delete = "Eliminar", confirm = "Confirmar",
)

private val AR = Strings(
    navHome = "الرئيسية", navLive = "البث المباشر", navGuide = "الدليل", navMovies = "الأفلام",
    navSeries = "المسلسلات", navFavorites = "المفضلة", navSearch = "بحث",
    navCategories = "الفئات", navMultiview = "عرض متعدد", navSettings = "الإعدادات",
    homeWelcome = "مرحبًا بكم في Ultra TV",
    homeSubtitle = "نسخة أصلية · جاهزة لجهاز التحكم",
    homeContinueWatching = "متابعة المشاهدة",
    homeRecentlyWatched = "شوهد مؤخرًا",
    homeFeaturedChannels = "قنوات مميزة",
    settingsTitle = "الإعدادات", settingsDisplay = "العرض والتشغيل",
    settingsParental = "الرقابة الأبوية", settingsBackup = "النسخ الاحتياطي والاستعادة",
    settingsLanguage = "اللغة",
    live = "البث المباشر", movies = "الأفلام", series = "المسلسلات", categories = "الفئات",
    tvGuide = "دليل التلفاز", favorites = "المفضلة",
    play = "تشغيل", resume = "استئناف", cancel = "إلغاء", close = "إغلاق",
    save = "حفظ", delete = "حذف", confirm = "تأكيد",
)

@Composable
fun stringsFor(lang: AppLang): Strings {
    val resolved = if (lang == AppLang.System) {
        // Pick the closest match for the device locale.
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
