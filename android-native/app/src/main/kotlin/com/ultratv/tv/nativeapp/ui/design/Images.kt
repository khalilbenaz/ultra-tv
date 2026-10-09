package com.ultratv.tv.nativeapp.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ultratv.tv.nativeapp.data.tmdb.PosterResolver
import com.ultratv.tv.nativeapp.data.tmdb.TmdbKind
import com.ultratv.tv.nativeapp.ui.common.LocalLowRam
import com.ultratv.tv.nativeapp.ui.common.design

/** Créée une fois (et non à chaque vignette sans image affichée). */
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/**
 * RÈGLE : une image ne dicte JAMAIS la taille de son emplacement. Chaque emplacement est une boîte
 * à taille fixe (imposée par l'appelant : aspectRatio, size, fillMaxSize d'une cellule de hauteur
 * fixe) ; l'image s'y adapte (Crop pour affiches/vignettes/fonds/avatars, Fit centré pour les
 * logos). Absente, en chargement, en erreur ou illisible (SVG, 404, format inconnu) : le
 * placeholder de même taille reste en place — aucun saut de mise en page quand l'image arrive.
 * Coil décode à la taille de l'emplacement (jamais en pleine résolution) avec cache disque.
 */
enum class SlotFit { Crop, Fit }

/** Initiales d'affichage : 2 lettres/chiffres, Unicode (arabe, cyrillique…) conservés. */
fun initialsOf(name: String): String {
    val words = name.split(NON_WORD).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "·"
        words.size == 1 -> words[0].take(2)
        else -> "${words[0].first()}${words[1].first()}"
    }.uppercase()
}

@Composable
fun SlotImage(
    url: String?,
    name: String,
    modifier: Modifier,
    shape: Shape,
    fit: SlotFit,
    background: Color = Ux.Surface2,
    innerPadding: Dp = 0.design,
    initialsSize: Int = 22,
    onFail: (() -> Unit)? = null,
) {
    val lowRam = LocalLowRam.current
    // Fondu seulement au toucher : à la télécommande, chaque vignette chargée en défilement lançait une animation d'alpha.
    val fade = !lowRam && com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(shape).background(background), contentAlignment = Alignment.Center) {
        if (!loaded && name.isNotBlank()) {
            Text(
                initialsOf(name), color = Ux.Text3, fontFamily = Sora, fontWeight = FontWeight.Bold,
                fontSize = initialsSize.spx, maxLines = 1, overflow = TextOverflow.Clip,
            )
        }
        if (!url.isNullOrBlank()) {
            val ctx = LocalContext.current
            // Requête construite une fois par (url, mode) : la recréer à chaque recomposition (focus) coûtait une allocation
            // et une comparaison de modèle par vignette visible.
            val request = androidx.compose.runtime.remember(url, fade) { ImageRequest.Builder(ctx).data(url).crossfade(fade).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = if (fit == SlotFit.Crop) ContentScale.Crop else ContentScale.Fit,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onSuccess = { loaded = true },
                onError = { loaded = false; onFail?.invoke() },
            )
        }
    }
}

/** Type d'œuvre des affiches affichées par les écrans enfants (grilles Films / Séries) : active le repli TMDB sans toucher aux cellules. */
val LocalPosterKind = androidx.compose.runtime.compositionLocalOf<TmdbKind?> { null }

/**
 * Affiche 2:3 (Films, Séries, Recherche) : `Crop`, coins arrondis. L'appelant impose largeur et aspectRatio.
 * Si [kind] est connu (paramètre ou [LocalPosterKind]) et que l'affiche manque ou ne charge pas, elle est
 * cherchée sur TMDB (cache persistant, hors fil principal, appareil appairé seulement).
 */
@Composable
fun PosterImage(url: String?, name: String, modifier: Modifier, radius: Int = 18, kind: TmdbKind? = LocalPosterKind.current, year: Int? = null) {
    var fallback by remember(url, name) { mutableStateOf<String?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    val ctx = LocalContext.current
    if (kind != null && (url.isNullOrBlank() || failed) && name.isNotBlank()) {
        androidx.compose.runtime.LaunchedEffect(url, name, kind, failed) {
            fallback = runCatching { PosterResolver.from(ctx).resolve(kind, name, year) }.getOrNull()
        }
    }
    SlotImage(
        fallback ?: url, name, modifier, RoundedCornerShape(radius.design), SlotFit.Crop, Ux.Surface, initialsSize = 36,
        onFail = if (kind != null && fallback == null) ({ failed = true }) else null,
    )
}

/** Vignette 16:9 (Reprendre, épisodes) : `Crop`. */
@Composable
fun ThumbImage(url: String?, name: String, modifier: Modifier, radius: Int = 20) =
    SlotImage(url, name, modifier, RoundedCornerShape(radius.design), SlotFit.Crop, Ux.Surface2, initialsSize = 40)

/** Logo de chaîne : boîte fixe, `Fit` centré avec marge intérieure, fond de la surface. */
@Composable
fun LogoBox(url: String?, name: String, modifier: Modifier, radius: Int = 10, pad: Int = 6, bg: Color = Ux.Surface2) =
    SlotImage(url, name, modifier, RoundedCornerShape(radius.design), SlotFit.Fit, bg, innerPadding = pad.design, initialsSize = 18)

/** Fond de hero / de fiche : zone fixe, `Crop`. */
@Composable
fun BackdropImage(url: String?, modifier: Modifier, bg: Color = Ux.Tone) =
    SlotImage(url, "", modifier, RoundedCornerShape(0.design), SlotFit.Crop, bg)

/** Avatar de distribution : cercle, `Crop`. */
@Composable
fun AvatarImage(url: String?, name: String, modifier: Modifier) =
    SlotImage(url, name, modifier, CircleShape, SlotFit.Crop, Ux.Surface2, initialsSize = 28)

/** Style de texte tronqué : tout nom (chaîne, titre) tient sur N lignes avec ellipse. */
@Composable
fun EllipsisText(
    text: String, color: Color, fontSizePx: Int, modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal, maxLines: Int = 1, family: androidx.compose.ui.text.font.FontFamily = Manrope,
) = Text(text, modifier = modifier, color = color, fontFamily = family, fontWeight = weight, fontSize = fontSizePx.spx,
    lineHeight = (fontSizePx * 1.2f).spx, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

private val Float.spx get() = (this / 2f).sp
