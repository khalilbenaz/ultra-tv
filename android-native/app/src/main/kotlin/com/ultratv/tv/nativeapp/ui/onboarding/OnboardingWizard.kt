package com.ultratv.tv.nativeapp.ui.onboarding

import com.ultratv.tv.nativeapp.data.repo.atMostEvery
import com.ultratv.tv.nativeapp.ui.common.responsiveWidth
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.db.MovieDao
import com.ultratv.tv.nativeapp.data.db.SeriesDao
import com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.data.repo.SyncStatusBus
import com.ultratv.tv.nativeapp.i18n.LocalStrings
import com.ultratv.tv.nativeapp.i18n.WizardStrings
import com.ultratv.tv.nativeapp.ui.common.LocalLowRam
import com.ultratv.tv.nativeapp.ui.common.RequestInitialFocus
import com.ultratv.tv.nativeapp.ui.common.design
import com.ultratv.tv.nativeapp.ui.design.DIcon
import com.ultratv.tv.nativeapp.ui.design.FocusSurface
import com.ultratv.tv.nativeapp.ui.design.Icons
import com.ultratv.tv.nativeapp.ui.design.KeyHint
import com.ultratv.tv.nativeapp.ui.design.LogoMark
import com.ultratv.tv.nativeapp.ui.design.Manrope
import com.ultratv.tv.nativeapp.ui.design.Sora
import com.ultratv.tv.nativeapp.ui.design.Ux
import com.ultratv.tv.nativeapp.ui.design.spx
import com.ultratv.tv.nativeapp.ui.settings.M3uDialog
import com.ultratv.tv.nativeapp.ui.settings.SettingsViewModel
import com.ultratv.tv.nativeapp.ui.settings.XtreamDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Règle d'affichage de l'assistant : première ouverture ET aucune playlist (ou assistant en cours). */
internal fun shouldShowOnboarding(hasSeenOnboarding: Boolean, providerCount: Int, started: Boolean = false): Boolean =
    !hasSeenOnboarding && (providerCount == 0 || started)

/** Récapitulatif réel de la source qui vient d'être ajoutée (étape 3). */
data class SourceSummary(val name: String, val kind: String, val channels: Int, val movies: Int, val series: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: UserPreferencesStore,
    private val provider: ProviderRepository,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val sync: com.ultratv.tv.nativeapp.data.sync.SyncCoordinator,
    bus: SyncStatusBus,
) : ViewModel() {

    /** Vrai dès que l'assistant a été affiché : il ne disparaît pas quand la 1re source est ajoutée. */
    private val started = MutableStateFlow(false)
    fun markStarted() { started.value = true }

    /**
     * `null` tant que DataStore et Room n'ont pas répondu : ni l'assistant ni l'application ne
     * sont alors affichés (pas de clignotement, pas d'accueil composé derrière l'assistant).
     */
    val show: StateFlow<Boolean?> = combine(prefs.flow, provider.observeProviders(), started) { p, ps, st ->
        shouldShowOnboarding(p.hasSeenOnboarding, ps.size, st)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val summary: StateFlow<SourceSummary?> = provider.observeProviders().flatMapLatest { ps ->
        val p = ps.lastOrNull() ?: return@flatMapLatest flowOf(null)
        combine(channelDao.observeCount(p.id), movieDao.observeCount(p.id), seriesDao.observeCount(p.id)) { c, m, s ->
            SourceSummary(p.name, p.kind, c, m, s)
        }.atMostEvery(500)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncStatus = bus.status
    val failure = bus.failure

    fun dismiss() { viewModelScope.launch { prefs.markOnboardingSeen() } }

    suspend fun previewLanguages(pid: Long) = provider.previewLanguages(pid)

    /** Enregistre le choix (vide = toutes les langues) PUIS démarre la synchro, qui n'activera que les catégories retenues. */
    fun chooseLanguages(pid: Long, langs: Set<String>) {
        viewModelScope.launch { prefs.setLanguages(langs.sorted().joinToString(",")); sync.request(pid, force = true) }
    }
}

private enum class Step { Welcome, Source, Languages, Ready }
private enum class Form { None, Xtream, M3uUrl }

@Composable
fun OnboardingWizard(
    onOpenSettings: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val W = LocalStrings.current.wiz
    var step by remember { mutableStateOf(Step.Welcome) }
    var pendingProvider by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.markStarted() }
    // BACK remonte d'une étape au lieu de quitter l'application en plein assistant.
    BackHandler(enabled = step != Step.Welcome) {
        step = when (step) { Step.Ready -> Step.Source; Step.Languages -> Step.Source; else -> Step.Welcome }
    }
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    Column(
        Modifier.fillMaxSize().background(Ux.Bg)
            .then(if (touch) Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 12.dp) else Modifier.padding(horizontal = 96.design, vertical = 54.design)),
    ) {
        Header(W, step)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = if (touch) Alignment.TopStart else Alignment.CenterStart) {
            when (step) {
                Step.Welcome -> WelcomeStep(W, onStart = { step = Step.Source }, onSkip = { vm.dismiss() })
                Step.Source -> SourceStep(W, onAdded = { step = Step.Ready }, onXtreamAdded = { id -> pendingProvider = id; step = Step.Languages }, onLater = { vm.dismiss() })
                Step.Languages -> LanguagesStep(pendingProvider ?: 0L, vm, onDone = { step = Step.Ready })
                Step.Ready -> ReadyStep(
                    W, vm,
                    onWatch = { vm.dismiss() },
                    onAddAnother = { step = Step.Source },
                )
            }
        }
        if (!touch) Footer(W, step)
    }
}

// ───────────────────────── En-tête / pied de page ─────────────────────────

@Composable
private fun Header(W: WizardStrings, step: Step) {
    Row(
        Modifier.fillMaxWidth().height(72.design),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark()
            Spacer(Modifier.width(16.design))
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Row {
                Text("ULTRA ", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 28.spx, letterSpacing = 1.7.sp, color = Ux.Text)
                Text("TV", fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 28.spx, letterSpacing = 1.7.sp, color = Ux.Text3)
                }
            }
        }
        Stepper(W, step)
    }
}

@Composable
private fun Stepper(W: WizardStrings, step: Step) {
    val labels = listOf(W.stepWelcome, W.stepSource, W.stepReady)
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            val ord = when (step) { Step.Welcome -> 0; Step.Source, Step.Languages -> 1; Step.Ready -> 2 }
            val past = i < ord
            val current = i == ord
            if (i > 0) {
                Box(Modifier.padding(horizontal = 10.design).width(if (touch) 20.dp else 56.design).height(2.design)
                    .background(if (i <= (when (step) { Step.Welcome -> 0; Step.Source, Step.Languages -> 1; Step.Ready -> 2 })) Ux.Accent else Ux.Line))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.design).clip(CircleShape)
                        .then(when {
                            current -> Modifier.background(Ux.Accent)
                            past -> Modifier.background(Ux.Surface2)
                            else -> Modifier.border(2.design, Ux.Line, CircleShape)
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (past) DIcon(Icons.Check, 20.design, Ux.Text, strokeWidth = 3f)
                    else Text("${i + 1}", fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 22.spx,
                        color = if (current) Ux.Text else Ux.Text3)
                }
                if (!touch || current) {
                    Spacer(Modifier.width(12.design))
                    Text(label, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = if (touch) 13.sp else 22.spx,
                        color = if (current) Ux.Text else Ux.Text3, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Footer(W: WizardStrings, step: Step) {
    Row(Modifier.fillMaxWidth().height(48.design), verticalAlignment = Alignment.CenterVertically) {
        val ok = when (step) { Step.Welcome -> W.hintValidate; Step.Source, Step.Languages -> W.hintChoose; Step.Ready -> W.hintWatch }
        KeyHint("OK", ok)
        Spacer(Modifier.width(36.design))
        if (step != Step.Ready) KeyHint(null, if (step == Step.Welcome) W.hintBack else W.hintPrevious, chevron = true)
    }
}

// ───────────────────────── Composants communs ─────────────────────────

/** CTA principal : 88 px de haut, focalisé par défaut, blanc/accent (état focus de la maquette). */
@Composable
private fun PrimaryCta(label: String, iconPath: String, fillIcon: Boolean, onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    RequestInitialFocus(requester, hasFocus = { focused })
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(44.design),
        bg = Ux.Surface,
        modifier = Modifier.height(88.design).focusRequester(requester).onFocusChanged { focused = it.isFocused },
    ) { f ->
        Row(
            Modifier.padding(horizontal = 48.design).height(88.design),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fillIcon) { DIcon(iconPath, 28.design, if (f) Ux.TextOnLight else Ux.Text, fill = true); Spacer(Modifier.width(16.design)) }
            Text(label, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 32.spx, color = if (f) Ux.TextOnLight else Ux.Text)
            if (!fillIcon) { Spacer(Modifier.width(16.design)); DIcon(iconPath, 28.design, if (f) Ux.TextOnLight else Ux.Text, strokeWidth = 2.5f) }
        }
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(36.design), bg = Ux.Surface, modifier = Modifier.height(72.design)) { f ->
        Box(Modifier.padding(horizontal = 36.design).height(72.design), contentAlignment = Alignment.Center) {
            Text(label, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 26.spx, color = if (f) Ux.TextOnLight else Ux.Text)
        }
    }
}

@Composable
private fun Eyebrow(text: String) =
    Text(text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx, letterSpacing = 3.sp, color = Ux.Accent)

/** Taille du titre : 84 px de la maquette, réduite pour tenir sur UNE ligne si le texte est long. */
internal fun fitTitleSizePx(text: String, basePx: Int = 84, maxWidthPx: Int = 1180): Int {
    val estimated = text.length * basePx * 0.56f
    return if (estimated <= maxWidthPx) basePx else (basePx * maxWidthPx / estimated).toInt().coerceAtLeast(40)
}

// ───────────────────────── Étape 1 : Bienvenue ─────────────────────────

@Composable
private fun WelcomeStep(W: WizardStrings, onStart: () -> Unit, onSkip: () -> Unit) {
    if (com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current) {
        TouchStepColumn {
            Eyebrow(W.stepLabel.format(1))
            Text(W.welcomeTitle, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, color = Ux.Text)
            Text(W.welcomeTagline, fontFamily = Manrope, fontSize = 16.sp, lineHeight = 24.sp, color = Ux.Text2)
            // Xtream Codes, lien M3U, fichier M3U : les trois seuls types de source.
            Bullet(Icons.Monitor, W.bulletXtream)
            Bullet(Icons.List, W.bulletM3u)
            TouchCta(W.start, true, onStart)
            TouchCta(W.skip, false, onSkip)
        }
        return
    }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.responsiveWidth(900), verticalArrangement = Arrangement.spacedBy(40.design)) {
            Column(verticalArrangement = Arrangement.spacedBy(20.design)) {
                Eyebrow(W.stepLabel.format(1))
                Text(
                    W.welcomeTitle,
                    fontFamily = Sora, fontWeight = FontWeight.Bold,
                    fontSize = fitTitleSizePx(W.welcomeTitle).spx,
                    letterSpacing = (-2).spx,
                    color = Ux.Text, maxLines = 1, softWrap = false,
                    modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
                )
                Text(W.welcomeTagline, fontFamily = Manrope, fontSize = 32.spx, lineHeight = 45.spx,
                    color = Ux.Text2, modifier = Modifier.responsiveWidth(820))
            }
            Column(verticalArrangement = Arrangement.spacedBy(20.design)) {
                Bullet(Icons.Monitor, W.bulletXtream)
                Bullet(Icons.List, W.bulletM3u)
            }
            Row(Modifier.padding(top = 8.design), verticalAlignment = Alignment.CenterVertically) {
                PrimaryCta(W.start, Icons.Arrow, fillIcon = false, onClick = onStart)
                Spacer(Modifier.width(28.design))
                SecondaryButton(W.skip, onSkip)
            }
        }
        Spacer(Modifier.width(96.design))
        // Maquette à la lettre : section flex 1 de 760/1080 de haut ; rendu statique (une simple
        // rotation graphicsLayer, négligeable même sur une box d'entrée de gamme).
        Box(Modifier.weight(1f).height(760.design)) { PreviewGrid(W.previewTiles, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Bullet(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.design).clip(RoundedCornerShape(12.design)).background(Ux.Surface), contentAlignment = Alignment.Center) {
            DIcon(icon, 28.design, Ux.Text)
        }
        Spacer(Modifier.width(20.design))
        Text(text, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 28.spx, color = Ux.Text)
    }
}

/** Parse « titre|sous-titre;… » en 9 tuiles (voir [WizardStrings.previewTiles]). */
internal fun parsePreviewTiles(raw: String): List<Pair<String, String>> =
    raw.split(';').map { it.substringBefore('|') to it.substringAfter('|', "") }

/**
 * Grille 3×3 de tuiles décoratives : gap 24, rotation −6°, décalée de 60 px vers la droite,
 * opacité 0,95 ; tuile 220 de haut, rayon 20, padding 20, contenu en bas (titre 22 gras puis
 * sous-titre 18 #A1A1AA) ; fonds alternés #1C1C21 / #26262D ; tuile 5 pleine couleur accent.
 */
@Composable
private fun PreviewGrid(raw: String, modifier: Modifier) {
    val tiles = parsePreviewTiles(raw)
    // Fond alterné de la maquette, dans l'ordre : S, M, S, M, accent, S, S, M, S.
    val tones = listOf(0, 1, 0, 1, 2, 0, 0, 1, 0)
    Column(
        modifier.graphicsLayer { rotationZ = -6f; translationX = 60.design.toPx(); alpha = 0.95f },
        verticalArrangement = Arrangement.spacedBy(24.design),
    ) {
        for (r in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.design)) {
                for (c in 0 until 3) {
                    val i = r * 3 + c
                    val (t, sub) = tiles.getOrElse(i) { "" to "" }
                    Column(
                        Modifier.weight(1f).height(220.design).clip(RoundedCornerShape(20.design))
                            .background(when (tones[i]) { 2 -> Ux.Accent; 1 -> Ux.Surface2; else -> Ux.Surface })
                            .padding(20.design),
                        verticalArrangement = Arrangement.spacedBy(8.design, Alignment.Bottom),
                    ) {
                        if (i == 0) Box(Modifier.width(64.design).height(8.design).clip(RoundedCornerShape(4.design)).background(Ux.Accent))
                        if (i == 4) DIcon(Icons.Play, 40.design, Ux.White, fill = true)
                        Text(t, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx,
                            color = Ux.White, maxLines = 1)
                        if (sub.isNotEmpty()) Text(sub, fontFamily = Manrope, fontSize = 22.spx, color = Ux.Text3, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Étape 2 : Source ─────────────────────────

@Composable
private fun SourceStep(W: WizardStrings, onAdded: () -> Unit, onXtreamAdded: (Long) -> Unit, onLater: () -> Unit) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(Form.None) }
    // « Depuis le cloud » : appairage plein écran ; une fois la config importée, la box poursuit vers la synchro.
    var cloudStarted by remember { mutableStateOf(false) }
    val pairingUi by settingsVm.pairing.collectAsState()
    val cloudPaired by settingsVm.paired.collectAsState()
    val cloudProviders by settingsVm.providers.collectAsState()
    androidx.compose.runtime.LaunchedEffect(cloudStarted, cloudPaired, cloudProviders.size) {
        if (cloudStarted && cloudPaired && cloudProviders.isNotEmpty()) {
            cloudStarted = false
            com.ultratv.tv.nativeapp.data.config.CloudOnboarding.deferXtreamSync = false
            // Source Xtream reçue du cloud : même parcours qu'une saisie manuelle (choix des langues,
            // qui lance la synchro) ; M3U : synchro déjà demandée, on passe à « Prêt ».
            val xtream = cloudProviders.firstOrNull { it.active && it.kind == "XTREAM" } ?: cloudProviders.firstOrNull { it.kind == "XTREAM" }
            if (xtream != null) onXtreamAdded(xtream.id) else onAdded()
        }
    }

    // Même départ sur TV et smartphone : l'assistant suit l'appairage et enchaîne sur les langues puis la synchro
    // (avant, la carte du smartphone lançait l'appairage seule : synchro en arrière-plan, écran resté sur le choix).
    val startCloud = {
        cloudStarted = true
        com.ultratv.tv.nativeapp.data.config.CloudOnboarding.deferXtreamSync = true
        settingsVm.startPairing()
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (label, text) = withContext(Dispatchers.IO) {
                val display = runCatching {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
                    }
                }.getOrNull() ?: uri.toString()
                val body = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
                display to body
            }
            settingsVm.addM3uLocal("", label, text)
            onAdded()
        }
    }

    if (com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current) {
        TouchStepColumn {
            Eyebrow(W.stepLabel.format(2))
            Text(W.sourceTitle, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, color = Ux.Text)
            Text(W.sourceSubtitle, fontFamily = Manrope, fontSize = 15.sp, lineHeight = 22.sp, color = Ux.Text2)
            TouchSourceCard(Icons.Monitor, W.cardXtream, W.cardXtreamDesc) { form = Form.Xtream }
            TouchSourceCard(Icons.Link, W.cardM3uUrl, W.cardM3uUrlDesc) { form = Form.M3uUrl }
            TouchSourceCard(Icons.File, W.cardM3uFile, W.cardM3uFileDesc) { pickFile.launch(arrayOf("*/*")) }
            TouchSourceCard(Icons.Globe, com.ultratv.tv.nativeapp.ui.mobile.LocalMobileStrings.current.fromCloud, com.ultratv.tv.nativeapp.i18n.LocalDs.current.cloudSyncHint) { startCloud() }
            TouchCta(W.later, false, onLater)
        }
    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.design)) {
            Eyebrow(W.stepLabel.format(2))
            Text(W.sourceTitle, fontFamily = Sora, fontWeight = FontWeight.Bold,
                fontSize = fitTitleSizePx(W.sourceTitle, basePx = 72).spx, color = Ux.Text, maxLines = 1, softWrap = false,
                modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true))
            Text(W.sourceSubtitle, fontFamily = Manrope, fontSize = 30.spx, color = Ux.Text2)
        }
        Spacer(Modifier.height(56.design))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.design), horizontalArrangement = Arrangement.spacedBy(32.design)) {
            SourceCard(Modifier.weight(1f), Icons.Monitor, W.cardXtream, W.cardXtreamDesc, first = true) { form = Form.Xtream }
            SourceCard(Modifier.weight(1f), Icons.Link, W.cardM3uUrl, W.cardM3uUrlDesc) { form = Form.M3uUrl }
            SourceCard(Modifier.weight(1f), Icons.File, W.cardM3uFile, W.cardM3uFileDesc) { pickFile.launch(arrayOf("*/*")) }
            SourceCard(Modifier.weight(1f), Icons.Link, com.ultratv.tv.nativeapp.i18n.LocalDs.current.cardCloud, com.ultratv.tv.nativeapp.i18n.LocalDs.current.cardCloudDesc) { startCloud() }
        }
        Spacer(Modifier.height(40.design))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryButton(W.later, onLater)
        }
    }

    com.ultratv.tv.nativeapp.ui.settings.CloudPairingDialog(pairingUi, onCancel = { cloudStarted = false; com.ultratv.tv.nativeapp.data.config.CloudOnboarding.deferXtreamSync = false; settingsVm.cancelPairing() }, onRetry = { settingsVm.startPairing() })
    when (form) {
        Form.Xtream -> XtreamDialog(
            onDismiss = { form = Form.None },
            onSubmit = { n, u, user, pw -> settingsVm.addXtreamOnly(n, u, user, pw) { id -> onXtreamAdded(id) }; form = Form.None },
        )
        Form.M3uUrl -> M3uDialog(
            onDismiss = { form = Form.None },
            onSubmit = { n, u -> settingsVm.addM3uAndSync(n, u); form = Form.None; onAdded() },
        )
        Form.None -> Unit
    }
}

@Composable
private fun SourceCard(modifier: Modifier, icon: String, title: String, desc: String, first: Boolean = false, onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (first) RequestInitialFocus(requester, hasFocus = { focused })
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(28.design),
        modifier = modifier.height(340.design)
            .then(if (first) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused },
    ) { f ->
        Column(Modifier.fillMaxSize().padding(36.design), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(72.design).clip(RoundedCornerShape(18.design)).background(if (f) Ux.Accent else Ux.Surface2),
                contentAlignment = Alignment.Center,
            ) { DIcon(icon, 38.design, if (f) Ux.White else Ux.Text) }
            Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
                Text(title, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 34.spx, color = if (f) Ux.TextOnLight else Ux.Text)
                Text(desc, fontFamily = Manrope, fontSize = 22.spx, lineHeight = 30.spx, color = if (f) Ux.OnFocus2 else Ux.Text3)
            }
        }
    }
}

// ───────────────────────── Étape 3 : Prêt ─────────────────────────

@Composable
private fun ReadyStep(W: WizardStrings, vm: OnboardingViewModel, onWatch: () -> Unit, onAddAnother: () -> Unit) {
    val summary by vm.summary.collectAsState()
    val status by vm.syncStatus.collectAsState()
    val failure by vm.failure.collectAsState()
    if (com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current) {
        TouchStepColumn {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Ux.Accent), contentAlignment = Alignment.Center) { DIcon(Icons.Check, 28.dp, Ux.White, strokeWidth = 2.5f) }
            Eyebrow(W.stepLabel.format(3))
            Text(W.readyTitle, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Ux.Text)
            val f = failure
            Text(if (f != null) LocalStrings.current.sync.messageFor(f.kind) else W.readyBody, fontFamily = Manrope, fontSize = 16.sp, lineHeight = 24.sp, color = if (f != null) Ux.Err else Ux.Text2)
            SummaryCard(W, summary, status?.percent, status != null, Modifier.fillMaxWidth())
            TouchCta(W.watchTv, true, onWatch)
            TouchCta(if (failure != null) LocalStrings.current.sync.fixSource else W.addAnother, false, onAddAnother)
        }
        return
    }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.responsiveWidth(920), verticalArrangement = Arrangement.spacedBy(40.design)) {
            Box(Modifier.size(112.design).clip(CircleShape).background(Ux.Accent), contentAlignment = Alignment.Center) {
                DIcon(Icons.Check, 56.design, Ux.White, strokeWidth = 2.5f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(20.design)) {
                Eyebrow(W.stepLabel.format(3))
                Text(W.readyTitle, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 84.spx, color = Ux.Text, maxLines = 1, softWrap = false)
                val f = failure
                if (f != null) {
                    Text(LocalStrings.current.sync.messageFor(f.kind), fontFamily = Manrope, fontSize = 30.spx, lineHeight = 42.spx,
                        color = Ux.Err, modifier = Modifier.responsiveWidth(820))
                } else {
                    Text(W.readyBody, fontFamily = Manrope, fontSize = 32.spx, lineHeight = 45.spx, color = Ux.Text2, modifier = Modifier.responsiveWidth(820))
                }
            }
            Row(Modifier.padding(top = 8.design), verticalAlignment = Alignment.CenterVertically) {
                PrimaryCta(W.watchTv, Icons.Play, fillIcon = true, onClick = onWatch)
                Spacer(Modifier.width(28.design))
                SecondaryButton(if (failure != null) LocalStrings.current.sync.fixSource else W.addAnother, onAddAnother)
            }
        }
        Spacer(Modifier.width(96.design))
        SummaryCard(W, summary, status?.percent, status != null, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(W: WizardStrings, s: SourceSummary?, percent: Int?, loading: Boolean, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(28.design)).background(Ux.SurfaceDeep).padding(48.design),
        verticalArrangement = Arrangement.spacedBy(28.design),
    ) {
        Text(W.yourSource, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx, letterSpacing = 2.2.sp, color = Ux.Text3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.design).clip(RoundedCornerShape(16.design)).background(Ux.Surface2), contentAlignment = Alignment.Center) {
                DIcon(when (s?.kind) { "XTREAM" -> Icons.Monitor; "M3U_LOCAL" -> Icons.File; else -> Icons.Link }, 34.design, Ux.Text)
            }
            Spacer(Modifier.width(20.design))
            Column(verticalArrangement = Arrangement.spacedBy(6.design)) {
                Text(s?.name ?: "…", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 32.spx, color = Ux.Text, maxLines = 1)
                Text(kindLabel(W, s?.kind), fontFamily = Manrope, fontSize = 22.spx, color = Ux.Text3)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.design).background(Ux.Surface2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.design)) {
            Stat(Modifier.weight(1f), s?.channels, W.channels)
            Stat(Modifier.weight(1f), s?.movies, W.movies)
            Stat(Modifier.weight(1f), s?.series, W.series)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.design)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(W.epgGuide, fontFamily = Manrope, fontSize = 22.spx, color = Ux.Text2)
                Text(if (loading) (percent?.let { "$it %" } ?: W.epgLoading) else W.epgReady, fontFamily = Manrope, fontSize = 22.spx, color = Ux.Text2)
            }
            Box(Modifier.fillMaxWidth().height(10.design).clip(RoundedCornerShape(5.design)).background(Ux.Surface2)) {
                val frac = if (!loading) 1f else ((percent ?: 0) / 100f).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(frac).height(10.design).background(Ux.Accent))
            }
        }
    }
}

private fun kindLabel(W: WizardStrings, kind: String?) = when (kind) {
    "XTREAM" -> W.kindXtream
    "M3U" -> W.kindM3u
    "M3U_LOCAL" -> W.kindM3uFile
    else -> ""
}

@Composable
private fun Stat(modifier: Modifier, value: Int?, label: String) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.design)) {
        Text(value?.let { "%,d".format(it) } ?: "—", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 48.spx, color = Ux.Text, maxLines = 1)
        Text(label, fontFamily = Manrope, fontSize = 22.spx, color = Ux.Text3)
    }
}


// ───────────────────────── Choix des langues (1re source) ─────────────────────────

@Composable
private fun LanguagesStep(pid: Long, vm: OnboardingViewModel, onDone: () -> Unit) {
    val D = com.ultratv.tv.nativeapp.i18n.LocalDs.current
    var langs by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.runtime.LaunchedEffect(pid) {
        val found = vm.previewLanguages(pid)
        langs = found
        val sys = java.util.Locale.getDefault().language
        selected = if (found.any { it.first == sys }) setOf(sys) else emptySet()
    }
    val touchLang = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    Column(if (touchLang) Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier.fillMaxSize(), verticalArrangement = if (touchLang) Arrangement.Top else Arrangement.Center) {
        Text(D.langQuestion, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = if (touchLang) 28.sp else 64.spx, color = Ux.Text, maxLines = 1)
        Spacer(Modifier.height(16.design))
        Text(D.langHelp, fontFamily = Manrope, fontSize = if (touchLang) 15.sp else 28.spx, color = Ux.Text2)
        Spacer(Modifier.height(40.design))
        val l = langs
        if (l == null) {
            Text(D.langLoading, fontFamily = Manrope, fontSize = 28.spx, color = Ux.Text3)
        } else {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(if (touchLang) 10.dp else 20.design), verticalArrangement = Arrangement.spacedBy(if (touchLang) 10.dp else 20.design)) {
                LangTile(D.allLanguages, "", selected.isEmpty()) { selected = emptySet() }
                l.take(12).forEach { (code, n) ->
                    LangTile(java.util.Locale(code).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() }, "$n", code in selected) {
                        selected = if (code in selected) selected - code else selected + code
                    }
                }
            }
            Spacer(Modifier.height(48.design))
            PrimaryCta(D.continueLabel, Icons.Check, fillIcon = false) { vm.chooseLanguages(pid, selected); onDone() }
        }
    }
}

@Composable
private fun LangTile(label: String, count: String, on: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(20.design), bg = if (on) Ux.Surface2 else Ux.Surface, modifier = (if (com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current) Modifier.width(150.dp).height(64.dp) else Modifier.width(260.design).height(112.design))) { f ->
        Row(Modifier.fillMaxSize().padding(horizontal = 24.design), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 28.spx, maxLines = 1, color = if (f) Ux.TextOnLight else Ux.Text)
                if (count.isNotEmpty()) Text(count, fontFamily = Manrope, fontSize = 22.spx, color = if (f) Ux.OnFocus2 else Ux.Text3)
            }
            if (on) DIcon(Icons.Check, 32.design, if (f) Ux.TextOnLight else Ux.Accent, strokeWidth = 2.5f)
        }
    }
}


// ───────────────────────── Tactile ─────────────────────────

/** Colonne d'une étape au toucher : défile, espacement de 16 dp, aucune mise en page TV. */
@Composable
private fun TouchStepColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

/** Bouton plein largeur : principal (blanc / encre, 52 dp) ou secondaire (48 dp). */
@Composable
private fun TouchCta(label: String, primary: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(26.dp), bg = if (primary) Ux.Cta else Ux.Surface, modifier = Modifier.fillMaxWidth().height(if (primary) 52.dp else 48.dp)) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontFamily = Manrope, fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold, fontSize = if (primary) 16.sp else 15.sp, color = if (primary) Ux.TextOnLight else Ux.Text, maxLines = 1)
        }
    }
}

@Composable
private fun TouchSourceCard(icon: String, title: String, desc: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(18.dp), bg = Ux.Surface, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)) { _ ->
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Ux.Surface2), contentAlignment = Alignment.Center) { DIcon(icon, 24.dp, Ux.Text) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ux.Text)
                Text(desc, fontFamily = Manrope, fontSize = 13.sp, lineHeight = 18.sp, color = Ux.Text3)
            }
        }
    }
}
