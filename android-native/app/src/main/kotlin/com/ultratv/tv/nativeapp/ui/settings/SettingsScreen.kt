package com.ultratv.tv.nativeapp.ui.settings

import androidx.compose.ui.focus.focusRequester
import com.ultratv.tv.nativeapp.i18n.locale
import com.ultratv.tv.nativeapp.ui.common.responsiveWidth
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.ultratv.tv.nativeapp.ui.mobile.openInBrowser
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.prefs.AppTheme
import com.ultratv.tv.nativeapp.data.prefs.DefaultPlayer
import com.ultratv.tv.nativeapp.data.prefs.SidebarPosition
import com.ultratv.tv.nativeapp.i18n.AppLang
import com.ultratv.tv.nativeapp.i18n.DesignStrings
import com.ultratv.tv.nativeapp.i18n.LocalDs
import com.ultratv.tv.nativeapp.i18n.tmdbAttribution
import com.ultratv.tv.nativeapp.i18n.LocalStrings
import com.ultratv.tv.nativeapp.ui.AppViewModel
import com.ultratv.tv.nativeapp.ui.common.ModalFocusScope
import com.ultratv.tv.nativeapp.ui.common.design
import com.ultratv.tv.nativeapp.ui.design.ChoiceDialog
import com.ultratv.tv.nativeapp.ui.design.DIcon
import com.ultratv.tv.nativeapp.ui.design.FocusSurface
import com.ultratv.tv.nativeapp.ui.design.GroupLabel
import com.ultratv.tv.nativeapp.ui.design.Icons
import com.ultratv.tv.nativeapp.ui.design.Manrope
import com.ultratv.tv.nativeapp.ui.design.PaneTitle
import com.ultratv.tv.nativeapp.ui.design.PillButton
import com.ultratv.tv.nativeapp.ui.design.PrefRow
import com.ultratv.tv.nativeapp.ui.design.Sora
import com.ultratv.tv.nativeapp.ui.design.Switch
import com.ultratv.tv.nativeapp.ui.design.SwitchPrefRow
import com.ultratv.tv.nativeapp.ui.design.Ux
import com.ultratv.tv.nativeapp.ui.design.spx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import com.ultratv.tv.nativeapp.ui.profile.TechnicalGate

private enum class OpenDialog { NONE, ADD_CHOOSER, XTREAM, M3U_URL, CLOUD, WORKER, SOURCE_ACTIONS, WORKER_URL, SHARE, RENAME_DEVICE }
private enum class Rub(val icon: String) {
    SOURCES("M3 5h18v12H3zM8 21h8M12 17v4"), SYNC("M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"), CATEGORIES("M4 6h16M4 12h16M4 18h10"),
    DISPLAY("M4 6h16M4 12h16M4 18h16"), PLAYBACK("M7 4v16l13-8z"), PARENTAL("M6 10V8a6 6 0 0 1 12 0v2M5 10h14v11H5z"),
    LANGUAGES("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18"), PROFILES("M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0"), ABOUT("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7h.01"),
}

/** Réglages (maquettes Reglages / Synchro / ReglagesAffichage / Diagnostic) : rubriques à gauche, volet à droite. */
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit = {}, vm: SettingsViewModel = hiltViewModel(), panes: SettingsPanesViewModel = hiltViewModel(), app: AppViewModel = hiltViewModel()) {
    val D = LocalDs.current
    var rub by rememberSaveable { mutableStateOf(0) }
    val dbg by com.ultratv.tv.nativeapp.StartupNav.debugRub.collectAsState()
    var showPane by rememberSaveable { mutableStateOf(false) }
    val pairRequested by com.ultratv.tv.nativeapp.StartupNav.startPairing.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pairRequested) { if (pairRequested) { rub = Rub.SOURCES.ordinal; showPane = true } }
    val rubFocus = remember { List(Rub.entries.size) { androidx.compose.ui.focus.FocusRequester() } }
    // Débogage (captures) : ouvre la rubrique demandée ET y place le focus (la rubrique suit le focus), une fois le focus initial posé.
    androidx.compose.runtime.LaunchedEffect(dbg) {
        dbg?.let { kotlinx.coroutines.delay(1500); val i = it.coerceIn(0, Rub.entries.lastIndex); rub = i; showPane = true; runCatching { rubFocus[i].requestFocus() }; com.ultratv.tv.nativeapp.StartupNav.debugRub.value = null }
    }
    val labels = listOf(D.rubSources, D.rubSync, D.rubCategories, D.rubDisplay, D.rubPlayback, D.rubParental, D.rubLanguages, com.ultratv.tv.nativeapp.ui.profile.ProfileStrings(D.lang).settingsTitle, D.rubAbout)
    val pane: @Composable () -> Unit = {
            when (Rub.entries[rub]) {
                Rub.SOURCES -> TechnicalGate(onDeny = { rub = Rub.DISPLAY.ordinal }) { SourcesPane(vm, panes) }
                Rub.SYNC -> TechnicalGate(onDeny = { rub = Rub.DISPLAY.ordinal }) { SyncPane(panes, onNavigate) }
                Rub.CATEGORIES -> CategoriesPane(panes, onNavigate)
                Rub.DISPLAY -> DisplayPane(vm, panes, app)
                Rub.PLAYBACK -> TechnicalGate(onDeny = { rub = Rub.DISPLAY.ordinal }) { PlaybackPane(panes, app) }
                Rub.PARENTAL -> { PaneTitle(D.rubParental); com.ultratv.tv.nativeapp.ui.parental.ParentalSection(onManageLockedChannels = { onNavigate("locked-channels") }) }
                Rub.LANGUAGES -> LanguagesPane(panes, app)
                Rub.PROFILES -> com.ultratv.tv.nativeapp.ui.profile.ProfilesPane()
                Rub.ABOUT -> TechnicalGate(onDeny = { rub = Rub.DISPLAY.ordinal }) { AboutPane(vm, panes, app, onNavigate) }
            }
    }
    val touch = com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current
    val compact = touch && com.ultratv.tv.nativeapp.ui.common.LocalUiWidthDp.current < 600f
    if (compact) {
        // Téléphone : liste de rubriques, puis la rubrique choisie en plein écran (Retour système = retour à la liste).
        androidx.activity.compose.BackHandler(enabled = showPane) { showPane = false }
        if (!showPane) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(D.settingsTitle, color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 28.sp, maxLines = 1)
                com.ultratv.tv.nativeapp.ui.mobile.SearchAction()
            }
            Rub.entries.forEachIndexed { i, r ->
                FocusSurface(onClick = { rub = i; showPane = true }, shape = RoundedCornerShape(16.dp), bg = Ux.SurfaceDeep, modifier = Modifier.fillMaxWidth().height(56.dp)) { _ ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DIcon(r.icon, 22.dp, Ux.Text2)
                        Text(labels[i], color = Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        DIcon("M9 6l6 6-6 6", 18.dp, Ux.Text3, strokeWidth = 2.5f)
                    }
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.ultratv.tv.nativeapp.ui.mobile.IconCircle(com.ultratv.tv.nativeapp.ui.mobile.MobileIcons.Back, com.ultratv.tv.nativeapp.ui.mobile.LocalMobileStrings.current.a11yBack, Ux.Surface, Ux.Text, { showPane = false })
                Text(labels[rub], color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { pane() }
        }
        return
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(460.design).fillMaxHeight().padding(start = 72.design, end = 32.design, top = 54.design, bottom = 54.design), verticalArrangement = Arrangement.spacedBy(10.design)) {
            Text(D.settingsTitle, color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 48.spx, maxLines = 1, modifier = Modifier.padding(bottom = 14.design))
            Rub.entries.forEachIndexed { i, r ->
                val sel = rub == i
                FocusSurface(
                    onClick = { rub = i }, shape = RoundedCornerShape(18.design), bg = if (sel) Ux.Surface2 else Color.Transparent, ringWidth = 5.design, focusedScale = 1.0f,
                    modifier = Modifier.fillMaxWidth().height(72.design).focusRequester(rubFocus[i]).onFocusChanged { if (it.isFocused) rub = i },
                ) { f ->
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        if (sel && !f) Box(Modifier.width(4.design).fillMaxHeight().background(Ux.Accent)) else Spacer(Modifier.width(4.design))
                        Spacer(Modifier.width(18.design))
                        DIcon(r.icon, 28.design, if (f) Ux.TextOnLight else if (sel) Ux.Text else Ux.Text3)
                        Spacer(Modifier.width(18.design))
                        Text(labels[i], color = if (f) Ux.TextOnLight else if (sel) Ux.Text else Ux.Text2, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 24.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.width(0.5f.dp1()).fillMaxHeight().background(Ux.Surface))
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 56.design, end = 96.design, top = 54.design, bottom = 54.design), verticalArrangement = Arrangement.spacedBy(28.design)) {
            pane()
        }
    }
}

private fun Float.dp1() = androidx.compose.ui.unit.Dp(this)

/** « scheme://hôte » seulement : jamais le chemin (qui peut contenir des identifiants). */
internal fun hostOnly(baseUrl: String): String = runCatching { java.net.URI(baseUrl).let { "${it.scheme}://${it.host}" } }.getOrDefault("")

/** Types de source pris en charge ; un type hérité (ex. Stalker, retiré en 1.1.1) reste affichable et supprimable. */
internal val SUPPORTED_KINDS = setOf("XTREAM", "M3U", "M3U_LOCAL")

private fun kindName(S: com.ultratv.tv.nativeapp.i18n.WizardStrings, kind: String) = when (kind) { "XTREAM" -> S.kindXtream; "M3U" -> S.kindM3u; "M3U_LOCAL" -> S.kindM3uFile; else -> null }

// ───────────────────────── Sources ─────────────────────────

@Composable
private fun SourcesPane(vm: SettingsViewModel, panes: SettingsPanesViewModel) {
    val D = LocalDs.current
    val S = LocalStrings.current
    val providers by vm.providers.collectAsState()
    val counts by panes.counts.collectAsState()
    val paired by vm.paired.collectAsState()
    val pairingUi by vm.pairing.collectAsState()
    val workerBase by vm.workerBaseUrl.collectAsState()
    // Accueil vide › « Depuis le cloud » : ouvre directement l'appairage.
    val cloudReq by com.ultratv.tv.nativeapp.StartupNav.cloudPairRequest.collectAsState()
    androidx.compose.runtime.LaunchedEffect(cloudReq) { if (cloudReq) { com.ultratv.tv.nativeapp.StartupNav.cloudPairRequest.value = false; vm.startPairing() } }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(OpenDialog.NONE) }
    var selected by remember { mutableStateOf<ProviderEntity?>(null) }
    val C = rememberCloudStrings()
    val cloud by vm.cloud.collectAsState()
    val offer by vm.offerShare.collectAsState()
    var connWarn by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(cloud, providers) { connWarn = vm.connectionWarning() }
    var backupPwd by remember { mutableStateOf("") }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = vm.consumeBackup()
        if (uri != null && text != null) scope.launch(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
                .onSuccess { com.ultratv.tv.nativeapp.ui.common.Toaster.ok(S.toastBackupSaved) }.onFailure { com.ultratv.tv.nativeapp.ui.common.Toaster.err(S.toastSaveFailed) }
        }
    }
    val loadBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val txt = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty() }.getOrNull()
            if (txt.isNullOrBlank()) com.ultratv.tv.nativeapp.ui.common.Toaster.err(S.toastEmptyFile)
            else vm.restoreBackup(txt, S.toastRestoredTemplate, S.toastRestoreFailed, backupPwd.takeIf { it.isNotEmpty() })
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val (label, text) = withContext(Dispatchers.IO) {
                val display = runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment } }.getOrNull() ?: "Local"
                display to (ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty())
            }
            vm.addM3uLocal("", label, text)
        }
    }

    PaneTitle(D.rubSources, D.sourcesSubtitle)
    Column(verticalArrangement = Arrangement.spacedBy(14.design)) {
        providers.forEach { p ->
            FocusSurface(onClick = { selected = p; dialog = OpenDialog.SOURCE_ACTIONS }, shape = RoundedCornerShape(22.design), bg = Ux.SurfaceDeep, ringWidth = 5.design, focusedScale = 1.0f, modifier = Modifier.fillMaxWidth().height(112.design)) { f ->
                Row(Modifier.fillMaxSize().padding(horizontal = 32.design), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(60.design).clip(RoundedCornerShape(16.design)).background(if (p.active) Ux.Accent else Ux.Surface2), contentAlignment = Alignment.Center) {
                        DIcon(when (p.kind) { "XTREAM" -> Icons.Monitor; "M3U_LOCAL" -> Icons.File; else -> Icons.List }, 30.design, Ux.White)
                    }
                    Spacer(Modifier.width(24.design))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.design)) {
                        Text(p.name, color = if (f) Ux.TextOnLight else Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 28.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val cloudTag = when {
                            p.kind != "XTREAM" && p.kind != "M3U" -> null
                            vm.cloudIdOf(p.id) != null -> C.cloudShared(vm.sharedWith(p.id).coerceAtLeast(1))
                            paired -> C.localPrivate
                            else -> null
                        }
                        val meta = listOfNotNull(kindName(S.wiz, p.kind) ?: D.unsupportedSource, cloudTag, if (p.active) D.active else null, if (p.active && counts.live > 0) D.channels(counts.live) else null).joinToString(" · ")
                        Text(meta, color = if (f) Ux.OnFocus2 else Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(D.edit, color = if (f) Ux.OnFocus2 else Ux.Text3, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.spx, maxLines = 1)
                }
            }
        }
        // « Ajouter une source » : carte en pointillés de la maquette.
        FocusSurface(onClick = { dialog = OpenDialog.ADD_CHOOSER }, shape = RoundedCornerShape(22.design), bg = Color.Transparent, ringWidth = 5.design, focusedScale = 1.0f, modifier = Modifier.fillMaxWidth().height(96.design).border(2.design, Ux.Line, RoundedCornerShape(22.design))) { f ->
            Row(Modifier.fillMaxSize().padding(horizontal = 32.design), verticalAlignment = Alignment.CenterVertically) {
                DIcon("M12 5v14M5 12h14", 28.design, if (f) Ux.TextOnLight else Ux.Text2, strokeWidth = 2.5f)
                Spacer(Modifier.width(20.design))
                Text(D.addSource, color = if (f) Ux.TextOnLight else Ux.Text2, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 24.spx, maxLines = 1)
            }
        }
    }
    GroupLabel(D.cloudSync)
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        if (paired) PrefRow(D.paired, D.unpair, hint = hostOnly(workerBase)) { vm.unpair() }
        else PrefRow(D.cloudSync, "", hint = D.cloudSyncHint) { vm.startPairing() }
        if (paired) {
            val minutes = if (cloud.lastSyncAt > 0) (System.currentTimeMillis() - cloud.lastSyncAt) / 60_000 else -1L
            val status = when {
                cloud.failed -> C.syncFailed
                minutes >= 0 -> C.syncedWithCloud + " · " + C.ago(minutes) + (if (cloud.deviceCount > 0) " · " + C.devicesCount(cloud.deviceCount) else "")
                else -> C.notSynced
            }
            PrefRow(C.syncNow, "", hint = status) { vm.syncFromCloud() }
            PrefRow(C.thisDevice, cloud.selfName, hint = C.deviceNameHint) { dialog = OpenDialog.RENAME_DEVICE }
            val up by panes.state.collectAsState()
            SwitchPrefRow(C.syncDisplayPrefs, up.syncDisplayPrefs, hint = C.syncDisplayPrefsHint) { panes.setSyncDisplayPrefs(it) }
            if (cloud.devices.isNotEmpty()) PrefRow(C.accountDevices, "", hint = cloud.devices.joinToString(" · ") { com.ultratv.tv.nativeapp.data.config.CloudSyncLogic.deviceDisplayName(it) }) { }
            if (connWarn) Text("⚠ " + C.connectionWarning, color = Ux.Err, fontFamily = Manrope, fontSize = 22.spx, lineHeight = 30.spx, modifier = Modifier.padding(horizontal = 8.design, vertical = 6.design))
        } else PrefRow(D.cloudSyncImport, "", hint = null) { vm.syncFromCloud() }
        PrefRow(D.workerUrlTitle, hostOnly(workerBase).substringAfter("://")) { dialog = OpenDialog.WORKER_URL }
        if (com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current) PrefRow(com.ultratv.tv.nativeapp.ui.mobile.LocalMobileStrings.current.openDashboard, "", hint = hostOnly(workerBase).substringAfter("://")) { ctx.openInBrowser(workerBase) }
        else // « Tableau de bord » : adresse en clair + QR à scanner avec le téléphone (le Worker choisi par l'utilisateur, sinon celui par défaut).
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.design)).background(Ux.SurfaceDeep).padding(24.design), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.design)) {
            com.ultratv.tv.nativeapp.ui.design.QrCode(workerBase.trimEnd('/'), 132.design)
            Column(verticalArrangement = Arrangement.spacedBy(6.design)) {
                Text(D.dashboardTitle, color = Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 26.spx, maxLines = 1)
                Text(hostOnly(workerBase).substringAfter("://"), color = Ux.Text2, fontFamily = Manrope, fontSize = 24.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(D.dashboardScan, color = Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1)
            }
        }
    }
    GroupLabel(D.backup)
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        PrefRow(D.exportBackup, "") { vm.prepareBackup(S.toastBackupReady, password = backupPwd.takeIf { it.isNotEmpty() }); saveBackup.launch("ultra-tv-backup-${System.currentTimeMillis()}.json") }
        PrefRow(D.importBackup, "") { loadBackup.launch(arrayOf("application/json", "*/*")) }
    }

    when (dialog) {
        OpenDialog.ADD_CHOOSER -> ChoiceDialog(D.addSource, listOf(OpenDialog.XTREAM to S.wiz.cardXtream, OpenDialog.M3U_URL to S.wiz.cardM3uUrl, OpenDialog.NONE to S.wiz.cardM3uFile, OpenDialog.CLOUD to (if (com.ultratv.tv.nativeapp.ui.mobile.LocalTouch.current) com.ultratv.tv.nativeapp.ui.mobile.LocalMobileStrings.current.fromCloud else D.cardCloud)), null,
            onPick = { k -> if (k == OpenDialog.NONE) { dialog = OpenDialog.NONE; pickFile.launch(arrayOf("*/*")) } else dialog = k }, onDismiss = { dialog = OpenDialog.NONE })
        OpenDialog.CLOUD -> { dialog = OpenDialog.NONE; vm.startPairing() }
        OpenDialog.XTREAM -> XtreamDialog({ dialog = OpenDialog.NONE }) { n, u, user, pw -> vm.addAndSync(n, u, user, pw); dialog = OpenDialog.NONE }
        OpenDialog.M3U_URL -> M3uDialog({ dialog = OpenDialog.NONE }) { n, u -> vm.addM3uAndSync(n, u); dialog = OpenDialog.NONE }
        OpenDialog.WORKER_URL -> WorkerUrlDialog(workerBase, onSave = { vm.saveWorkerBase(it); dialog = OpenDialog.NONE }, onDismiss = { dialog = OpenDialog.NONE })
        OpenDialog.SOURCE_ACTIONS -> selected?.let { p ->
            val shareable = paired && (p.kind == "XTREAM" || p.kind == "M3U")
            val linked = vm.cloudIdOf(p.id) != null
            val supported = p.kind in SUPPORTED_KINDS
            val opts = buildList {
                if (supported) { add("default" to D.setDefault); add("sync" to D.syncNow) }
                if (supported && shareable && !linked) add("share" to C.shareSource)
                if (supported && shareable && linked) { add("share" to C.editSharing); add("stop" to C.stopSharing) }
                add("delete" to D.deleteSource)
            }
            ChoiceDialog(p.name, opts, if (supported) null else D.unsupportedSourceHint,
                onPick = { k -> when (k) { "default" -> vm.setDefault(p.id); "sync" -> vm.resync(p.id); "delete" -> vm.delete(p.id); "stop" -> vm.stopSharing(p.id) }; dialog = if (k == "share") OpenDialog.SHARE else OpenDialog.NONE }, onDismiss = { dialog = OpenDialog.NONE })
        }
        OpenDialog.SHARE -> selected?.let { p ->
            ShareDevicesDialog(cloud.devices, cloud.selfId, null, onConfirm = { sel -> vm.share(p.id, sel); dialog = OpenDialog.NONE }, onDismiss = { dialog = OpenDialog.NONE })
        }
        OpenDialog.RENAME_DEVICE -> RenameDeviceDialog(cloud.selfName, onSave = { vm.renameThisDevice(it); dialog = OpenDialog.NONE }, onDismiss = { dialog = OpenDialog.NONE })
        else -> Unit
    }
    // Après l'ajout d'une source sur un appareil appairé : proposition (jamais d'envoi automatique).
    offer?.let { id ->
        OfferShareDialog(onShare = { vm.dismissOffer(); scope.launch { selected = vm.providerById(id) }; dialog = OpenDialog.SHARE }, onLater = { vm.dismissOffer() })
    }
    // Sources retirées du cloud pour cet appareil, avec des favoris ou enregistrements : confirmation.
    if (cloud.pending.isNotEmpty()) RemovalDialog(cloud.pending, onRemove = { vm.confirmRemovals(it) }, onKeep = { vm.keepLocal(it) })
    // Accueil vide (tactile) : « Depuis le cloud » ouvre directement l'appairage.
    val wantPairing by com.ultratv.tv.nativeapp.StartupNav.startPairing.collectAsState()
    androidx.compose.runtime.LaunchedEffect(wantPairing) { if (wantPairing) { com.ultratv.tv.nativeapp.StartupNav.startPairing.value = false; vm.startPairing() } }
    CloudPairingDialog(pairingUi, onCancel = { vm.cancelPairing() }, onRetry = { vm.startPairing() })
}

// ───────────────────────── Synchronisation ─────────────────────────

@Composable
private fun SyncPane(panes: SettingsPanesViewModel, onNavigate: (String) -> Unit) {
    val D = LocalDs.current
    val p by panes.state.collectAsState()
    val provider by panes.provider.collectAsState()
    val counts by panes.counts.collectAsState()
    val totals by panes.categoryTotals.collectAsState()
    var purgeFor by remember { mutableStateOf<String?>(null) }
    var extraOpen by remember { mutableStateOf(false) }
    val last = provider?.let { maxOf(it.lastLiveSyncAt, it.lastVodSyncAt, it.lastSeriesSyncAt, it.lastEpgSyncAt) } ?: 0L
    val subtitle = if (last <= 0L) D.neverSynced else D.lastUpdate.format(ago(D, System.currentTimeMillis() - last), nextText(p.syncMode, p.syncHour, p.syncIntervalHours, last))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { PaneTitle(D.rubSync, subtitle) }
        PillButton(D.syncNow, { panes.syncNow() }, heightPx = 64, hPadPx = 32, fontPx = 22, weight = FontWeight.Bold, iconPath = "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5", bg = Ux.Surface2)
    }
    GroupLabel(D.whenToUpdate)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.design)) {
        val modes = listOf("auto" to (D.modeAuto to D.modeAutoDesc), "launch" to (D.modeLaunch to D.modeLaunchDesc), "scheduled" to (D.modeScheduled to D.modeScheduledDesc(p.syncHour)), "manual" to (D.modeManual to D.modeManualDesc))
        modes.forEach { (id, pair) ->
            val sel = p.syncMode == id
            FocusSurface(onClick = { panes.setMode(id) }, shape = RoundedCornerShape(22.design), bg = if (sel) Ux.Surface else Ux.SurfaceDeep, ringWidth = 5.design, modifier = Modifier.weight(1f).height(132.design)) { f ->
                Column(Modifier.fillMaxSize().padding(24.design).then(if (sel && !f) Modifier.border(2.design, Ux.Accent, RoundedCornerShape(22.design)) else Modifier), verticalArrangement = Arrangement.spacedBy(10.design, Alignment.CenterVertically)) {
                    Text(pair.first, color = if (f) Ux.TextOnLight else Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 24.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pair.second, color = if (f) Ux.OnFocus2 else Ux.Text3, fontFamily = Manrope, fontSize = 24.spx, lineHeight = 32.spx, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (p.syncMode == "scheduled") PrefRow(D.modeScheduled, "%02d:00".format(p.syncHour)) { panes.setHour((p.syncHour + 1) % 24) }
    GroupLabel(D.contentToSync)
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        val ttl = com.ultratv.tv.nativeapp.data.sync.SyncPolicy.ttl(p.syncIntervalHours)
        val live = "${D.channels(counts.live)} · ${D.everyHours((ttl.liveMs / 3_600_000L).toInt())}"
        ContentRow(Icons.Live, D.liveTv, live, p.syncLive) { if (!it) purgeFor = "live" else panes.setPart("live", true, false) }
        ContentRow(Icons.Guide, D.stepGuide, D.guideWindow(2, 24) + " · " + D.everyNight, p.syncEpg) { panes.setPart("epg", it, false) }
        if (p.syncEpg) PrefRow(D.extraGuide, com.ultratv.tv.nativeapp.data.xmltv.ExtraEpg.labelOf(p.extraEpg) ?: D.extraGuideOff, hint = D.extraGuideHint) { extraOpen = true }
        ContentRow(Icons.Movies, D.stepMovies, D.moviesOf(counts.movies) + " · " + D.everyDay, p.syncVod) { if (!it) purgeFor = "vod" else panes.setPart("vod", true, false) }
        ContentRow(Icons.Series, D.stepSeries, D.seriesOf(counts.series) + " · " + D.everyDay, p.syncSeries) { if (!it) purgeFor = "series" else panes.setPart("series", true, false) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.design)) {
        SwitchPrefRow(D.wifiOnly, p.syncUnmeteredOnly, Modifier.weight(1f)) { panes.setUnmetered(it) }
        PrefRow(D.manageCategories, D.activeDisabled.format(totals.enabled, totals.disabled), Modifier.weight(1f)) { onNavigate("categories") }
    }
    if (extraOpen) ChoiceDialog(
        D.extraGuide,
        listOf("" to D.extraGuideOff) + com.ultratv.tv.nativeapp.data.xmltv.ExtraEpg.OPTIONS,
        p.extraEpg,
        onPick = { panes.setExtraEpg(it); extraOpen = false },
        onDismiss = { extraOpen = false },
    )
    purgeFor?.let { part ->
        ChoiceDialog(D.purgeTitle, listOf(false to D.keepData, true to D.purgeData), null, onPick = { purge -> panes.setPart(part, false, purge); purgeFor = null }, onDismiss = { purgeFor = null })
    }
}

@Composable
private fun ContentRow(icon: String, label: String, meta: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FocusSurface(onClick = { onChange(!on) }, shape = RoundedCornerShape(18.design), bg = Ux.SurfaceDeep, ringWidth = 5.design, focusedScale = 1.0f, modifier = Modifier.fillMaxWidth().height(84.design)) { f ->
        Row(Modifier.fillMaxSize().padding(horizontal = 28.design), verticalAlignment = Alignment.CenterVertically) {
            DIcon(icon, 28.design, if (f) Ux.TextOnLight else Ux.Text)
            Spacer(Modifier.width(20.design))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.design)) {
                Text(label, color = if (f) Ux.TextOnLight else Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 24.spx, maxLines = 1)
                Text(meta, color = if (f) Ux.OnFocus2 else Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(on, inverted = f)
        }
    }
}

private fun ago(D: DesignStrings, ms: Long): String {
    val m = ms / 60_000
    return when { m < 1 -> D.minutesShort.format(1); m < 60 -> D.minutesShort.format(m.toInt()); m < 48 * 60 -> D.hoursShort.format((m / 60).toInt()); else -> D.hoursShort.format((m / 60).toInt()) }
}

private fun nextText(mode: String, hour: Int, intervalH: Int, last: Long): String = when (mode) {
    "scheduled" -> "%02d:00".format(hour)
    "manual" -> "—"
    else -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last + (if (intervalH > 0) intervalH else 6) * 3_600_000L))
}

// ───────────────────────── Catégories (résumé) ─────────────────────────

@Composable
private fun CategoriesPane(panes: SettingsPanesViewModel, onNavigate: (String) -> Unit) {
    val D = LocalDs.current
    val totals by panes.categoryTotals.collectAsState()
    PaneTitle(D.rubCategories, D.categoriesFooter)
    PrefRow(D.manageCategories, D.activeDisabled.format(totals.enabled, totals.disabled)) { onNavigate("categories") }
}

// ───────────────────────── Affichage ─────────────────────────

@Composable
private fun DisplayPane(vm: SettingsViewModel, panes: SettingsPanesViewModel, app: AppViewModel) {
    val D = LocalDs.current
    val p by panes.state.collectAsState()
    var choice by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val pickLogos = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }; vm.setLocalLogosFolderUri(tree.toString()) }
    }
    val logosUri by vm.localLogosFolderUri.collectAsState()
    PaneTitle(D.rubDisplay)
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        PrefRow(D.theme, themeLabel(D, p.theme)) { choice = "theme" }
        PrefRow(D.language, AppLang.fromCode(p.language).displayName) { choice = "lang" }
        PrefRow(D.menu, if (p.sidebarPosition == SidebarPosition.LEFT) D.menuSidebar else D.menuTop) { choice = "menu" }
        SwitchPrefRow(D.channelNumbers, p.showChannelNumbers) { app.setShowChannelNumbers(it) }
        SwitchPrefRow(D.launchAtBoot, p.launchAtBoot) { app.setLaunchAtBoot(it) }
        PrefRow(D.openOn, if (p.autoPlayLastOnLaunch) D.lastChannel else D.homeScreen) { choice = "open" }
        PrefRow(D.timeZone, timeZoneLabel(D, p.timeZone)) { choice = "tz" }
        PrefRow(D.logosFolder, if (logosUri.isBlank()) D.none else "…" + logosUri.takeLast(24), hint = null) { pickLogos.launch(null) }
    }
    when (choice) {
        "theme" -> ChoiceDialog(D.theme, listOf(AppTheme.DARK to D.themeDark, AppTheme.LIGHT to D.themeLight, AppTheme.AUTO to D.themeAuto), p.theme, { app.setTheme(it); choice = "" }, { choice = "" })
        "lang" -> ChoiceDialog(D.language, AppLang.entries.map { it.code to it.displayName }, p.language, { app.setLanguage(it); choice = "" }, { choice = "" })
        "menu" -> ChoiceDialog(D.menu, listOf(SidebarPosition.LEFT to D.menuSidebar, SidebarPosition.TOP to D.menuTop), p.sidebarPosition, { app.setSidebar(it); choice = "" }, { choice = "" })
        "open" -> ChoiceDialog(D.openOn, listOf(false to D.homeScreen, true to D.lastChannel), p.autoPlayLastOnLaunch, { app.setAutoPlayLast(it); choice = "" }, { choice = "" })
        "tz" -> ChoiceDialog(D.timeZone, TIME_ZONES.map { it to timeZoneLabel(D, it) }, p.timeZone, { app.setTimeZone(it); choice = "" }, { choice = "" })
    }
}

/** Fuseaux proposés : « appareil » par défaut ; GMT pour une box mal réglée (ex. GMT+1 au lieu de GMT). */
private val TIME_ZONES = listOf("", "GMT", "Africa/Casablanca", "Europe/London", "Europe/Paris", "Africa/Algiers", "Africa/Tunis", "Africa/Dakar", "Asia/Riyadh", "America/New_York")

/** Libellé avec le décalage ACTUEL (heure d'été comprise) : « Europe/Paris (GMT+2) ». */
internal fun timeZoneLabel(D: DesignStrings, id: String): String {
    val tz = if (id.isBlank()) java.util.TimeZone.getDefault() else java.util.TimeZone.getTimeZone(id)
    val off = tz.getOffset(System.currentTimeMillis()) / 60_000
    val gmt = if (off == 0) "GMT" else "GMT" + (if (off > 0) "+" else "−") + (kotlin.math.abs(off) / 60) + (if (off % 60 != 0) ":%02d".format(java.util.Locale.ROOT, kotlin.math.abs(off) % 60) else "")
    val name = when (id) { "" -> D.tzSystem; "GMT" -> return "GMT"; else -> id.substringAfter('/').replace('_', ' ') }
    return "$name ($gmt)"
}

private fun qualityLabel(D: DesignStrings, q: String) = when (q) { "4k" -> "4K"; "fhd" -> "FHD"; "hd" -> "HD"; "sd" -> "SD"; else -> D.auto }
private fun themeLabel(D: DesignStrings, t: AppTheme) = when (t) { AppTheme.LIGHT -> D.themeLight; AppTheme.AUTO -> D.themeAuto; AppTheme.DARK -> D.themeDark }

// ───────────────────────── Lecture ─────────────────────────

@Composable
private fun PlaybackPane(panes: SettingsPanesViewModel, app: AppViewModel) {
    val D = LocalDs.current
    val p by panes.state.collectAsState()
    val ad by panes.adaptive.state.collectAsState()
    var choice by remember { mutableStateOf("") }
    val engineLabel = when (p.playerEngine) { "exo" -> D.engineExo; "vlc" -> D.engineVlc; else -> "${D.auto} · ${D.engineExo}" }
    val decLabel = when (p.decoderMode) { "hw" -> D.hardware; "sw" -> D.software; else -> "${D.auto} · ${D.hardware}" }
    val bufLabel = when (p.bufferPreset) { "low_latency" -> D.bufLow; "balanced" -> D.bufBalanced; "stable" -> D.bufStable; "custom" -> D.bufCustom; else -> "${D.auto} · ${ad.auto.bufferPreset.name.lowercase().replace('_', ' ')}" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { PaneTitle(D.rubPlayback) }
        if (p.playerEngine != "auto" || p.decoderMode != "auto" || p.bufferPreset != "auto") PillButton(D.backToAuto, { panes.backToAuto() }, bg = Ux.Surface2)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        PrefRow(D.playerRow, engineLabel) { choice = "engine" }
        PrefRow(D.decodingRow, decLabel) { choice = "dec" }
        PrefRow(D.bufferRow, bufLabel) { choice = "buf" }
        PrefRow(D.maxQuality, "${D.auto} · ${ad.auto.maxVideoHeight}p") { }
        PrefRow(D.preferredQuality, qualityLabel(D, p.preferredQuality), hint = D.preferredQualityHint) { choice = "pq" }
        PrefRow(D.externalPlayer, if (p.defaultPlayer == DefaultPlayer.EXTERNAL) D.on else D.none) { choice = "ext" }
        SwitchPrefRow(D.autoNext, p.autoPlayNextEpisode) { app.setAutoPlayNext(it) }
        SwitchPrefRow(D.resumePlayback, p.resumePlayback) { app.setResumePlayback(it) }
        SwitchPrefRow(D.autoFps, p.autoFrameRate) { app.setAutoFrameRate(it) }
        SwitchPrefRow(D.compatVideo, com.ultratv.tv.nativeapp.ui.player.engine.useTextureView(p.videoSurface), hint = D.compatVideoHint) {
            app.setVideoSurface(if (it) "texture" else "surface")
        }
    }
    when (choice) {
        "engine" -> ChoiceDialog(D.playerRow, listOf("auto" to D.auto, "exo" to D.engineExo, "vlc" to D.engineVlc), p.playerEngine, { panes.setPlayerEngine(it); choice = "" }, { choice = "" })
        "dec" -> ChoiceDialog(D.decodingRow, listOf("auto" to D.auto, "hw" to D.hardware, "sw" to D.software), p.decoderMode, { panes.setDecoder(it); choice = "" }, { choice = "" })
        "buf" -> ChoiceDialog(D.bufferRow, listOf("auto" to D.auto, "low_latency" to D.bufLow, "balanced" to D.bufBalanced, "stable" to D.bufStable), p.bufferPreset, { panes.setBufferPreset(it); choice = "" }, { choice = "" })
        "pq" -> ChoiceDialog(D.preferredQuality, listOf("auto" to D.auto, "4k" to "4K", "fhd" to "FHD", "hd" to "HD", "sd" to "SD"), p.preferredQuality, { panes.setPreferredQuality(it); choice = "" }, { choice = "" })
        "ext" -> ChoiceDialog(D.externalPlayer, listOf(DefaultPlayer.INTERNAL to D.none, DefaultPlayer.EXTERNAL to D.on), p.defaultPlayer, { app.setDefaultPlayer(it); choice = "" }, { choice = "" })
    }
}

// ───────────────────────── Langues ─────────────────────────

@Composable
private fun LanguagesPane(panes: SettingsPanesViewModel, app: AppViewModel) {
    val D = LocalDs.current
    val p by panes.state.collectAsState()
    val counts by panes.langCounts.collectAsState()
    var picker by remember { mutableStateOf(false) }
    var uiLang by remember { mutableStateOf(false) }
    val selected = p.languages.split(',').filter { it.isNotBlank() }
    PaneTitle(D.rubLanguages)
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        PrefRow(D.appLanguage, AppLang.fromCode(p.language).displayName) { uiLang = true }
        PrefRow(D.contentLanguages, if (selected.isEmpty()) D.allLanguages else selected.joinToString(" · ") { it.uppercase() }) { picker = true }
        SwitchPrefRow(D.includeMulti, p.includeMulti) { panes.setIncludeMulti(it) }
        SwitchPrefRow(D.includeUnknown, p.includeUnknownLang) { panes.setIncludeUnknown(it) }
    }
    if (uiLang) ChoiceDialog(D.appLanguage, AppLang.entries.map { it.code to it.displayName }, p.language, { app.setLanguage(it); uiLang = false }, { uiLang = false })
    if (picker) LanguagePickerDialog(counts.filter { it.lang.isNotEmpty() && it.lang != "MULTI" }.sortedByDescending { it.n }.map { it.lang to it.n }, selected.toSet(), { panes.setLanguages(it.sorted().joinToString(",")) }, { picker = false })
}

/** Cases à cocher par langue détectée (avec le nombre de catégories) — « Toutes les langues » vide la sélection. */
@Composable
fun LanguagePickerDialog(languages: List<Pair<String, Int>>, selected: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val D = LocalDs.current
    val fallback = listOf("fr", "en", "ar", "es", "de", "pt", "it", "tr", "nl", "el").map { it to 0 }
    val items = languages.ifEmpty { fallback }
    ModalFocusScope(onBack = onDismiss, modifier = Modifier.background(Ux.Scrim)) {
        Column(Modifier.responsiveWidth(760).clip(RoundedCornerShape(28.design)).background(Ux.SurfaceDeep).padding(40.design), verticalArrangement = Arrangement.spacedBy(10.design)) {
            Text(D.contentLanguages, color = Ux.Text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 36.spx, maxLines = 1, modifier = Modifier.padding(bottom = 8.design))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(600.design), verticalArrangement = Arrangement.spacedBy(10.design)) {
                item { LangRow(D.allLanguages, "", selected.isEmpty()) { onChange(emptySet()) } }
                items(items.size, key = { items[it].first }) { i ->
                    val (code, n) = items[i]
                    LangRow(java.util.Locale(code).getDisplayLanguage(D.locale).replaceFirstChar { it.uppercase() }, if (n > 0) "$n" else "", code in selected) { onChange(if (code in selected) selected - code else selected + code) }
                }
            }
            PillButton(D.close, onDismiss, bg = Ux.Surface)
        }
    }
}

@Composable
private fun LangRow(label: String, count: String, on: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(16.design), bg = Ux.Surface, ringWidth = 5.design, focusedScale = 1.0f, modifier = Modifier.fillMaxWidth().height(68.design)) { f ->
        Row(Modifier.fillMaxSize().padding(horizontal = 28.design), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (f) Ux.TextOnLight else Ux.Text, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 24.spx, maxLines = 1, modifier = Modifier.weight(1f))
            if (count.isNotEmpty()) Text(count, color = if (f) Ux.OnFocus2 else Ux.Text3, fontFamily = Manrope, fontSize = 22.spx, maxLines = 1)
            Spacer(Modifier.width(16.design))
            Switch(on, inverted = f)
        }
    }
}

// ───────────────────────── À propos ─────────────────────────

@Composable
private fun AboutPane(vm: SettingsViewModel, panes: SettingsPanesViewModel, app: AppViewModel, onNavigate: (String) -> Unit) {
    val D = LocalDs.current
    val S = LocalStrings.current
    val p by panes.state.collectAsState()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    PaneTitle(D.rubAbout)
    Column(verticalArrangement = Arrangement.spacedBy(10.design)) {
        PrefRow(D.version, com.ultratv.tv.nativeapp.BuildConfig.VERSION_NAME) { }
        PrefRow(D.checkUpdates, msg.orEmpty()) {
            scope.launch {
                val info = com.ultratv.tv.nativeapp.update.UpdateChecker.checkForUpdate()
                msg = if (info != null) S.settingsUpdateAvailableTemplate.format(info.versionName) else S.settingsUpToDateTemplate.format(com.ultratv.tv.nativeapp.BuildConfig.VERSION_NAME)
            }
        }
        PrefRow(D.diagnostic, "", hint = D.diagnosticHint) { onNavigate("diagnostic") }
        SwitchPrefRow(D.telemetry, p.telemetryEnabled) { app.setTelemetry(it) }
        // Attribution exigée par les conditions d'utilisation de l'API TMDB.
        androidx.tv.material3.Text(D.tmdbAttribution, color = Ux.Text3, fontFamily = Manrope, fontSize = 24.spx, lineHeight = 32.spx, maxLines = 3, modifier = Modifier.padding(top = 14.design))
    }
    com.ultratv.tv.nativeapp.update.UpdateDialog()
}
