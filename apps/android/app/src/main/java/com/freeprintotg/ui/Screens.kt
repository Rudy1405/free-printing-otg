package com.freeprintotg.ui

import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freeprintotg.AppLog
import com.freeprintotg.PrintViewModel
import com.freeprintotg.R
import com.freeprintotg.Settings
import com.freeprintotg.ThemeMode
import com.freeprintotg.ThemePrefs
import com.freeprintotg.driver.LanguageProbe
import com.freeprintotg.render.DocKind
import com.freeprintotg.render.DocumentRenderer
import com.freeprintotg.render.PaperSize

private enum class Screen { Home, Printers, Print, Settings, Wizard }

/** Root: tiny state-based navigation (Home → Printers / Print / Settings). */
@Composable
fun AppRoot(vm: PrintViewModel) {
    var screen by remember { mutableStateOf(if (vm.documents.isNotEmpty()) Screen.Print else Screen.Home) }
    // Files arriving from the share sheet or the pickers open the Print screen.
    LaunchedEffect(vm.documents.size) { if (vm.documents.isNotEmpty() && screen == Screen.Home) screen = Screen.Print }
    LaunchedEffect(Unit) { vm.refresh() }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.documents.addAll(it) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.documents.addAll(it) }
    val more = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.documents.addAll(it) }

    // Back never throws the user's files away: Home offers "Continue" while files are chosen.
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        when (screen) {
            Screen.Home -> HomeScreen(
                vm,
                onPhotos = { photos.launch(arrayOf("image/*")) },
                onDocuments = { docs.launch(arrayOf("application/pdf", "text/*")) },
                onContinue = { screen = Screen.Print },
                onPrinters = { screen = Screen.Printers },
                onSettings = { screen = Screen.Settings },
            )
            Screen.Printers -> PrintersScreen(vm, onBack = { screen = if (vm.documents.isNotEmpty()) Screen.Print else Screen.Home })
            Screen.Print -> PrintScreen(
                vm,
                onBack = { screen = Screen.Home },
                onPrinters = { screen = Screen.Printers },
                onAddMore = { more.launch(arrayOf("image/*", "application/pdf", "text/*")) },
                onWizard = { screen = Screen.Wizard },
            )
            Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Home })
            Screen.Wizard -> LanguageWizardScreen(vm.selected, vm.options.paper, onBack = { screen = Screen.Print })
        }
    }
}

/** The one status line for a screen: real spinner while scanning, amber when a printer is missing, red on failure. */
@Composable
private fun StatusLine(vm: PrintViewModel, showInfo: Boolean) {
    when {
        vm.busy -> ProgressBanner(vm.status)
        vm.statusTone != Tone.Info -> Banner(vm.status, vm.statusTone)
        vm.selected == null && vm.scanning -> ProgressBanner(stringResource(R.string.status_searching))
        vm.selected == null -> Banner(stringResource(R.string.status_no_printer), Tone.Warning)
        showInfo -> Banner(vm.status, Tone.Info)
    }
}

@Composable
private fun ColumnScope.HomeScreen(
    vm: PrintViewModel,
    onPhotos: () -> Unit,
    onDocuments: () -> Unit,
    onContinue: () -> Unit,
    onPrinters: () -> Unit,
    onSettings: () -> Unit,
) {
    AppHeader(
        stringResource(R.string.app_name),
        actions = { HeaderIcon(R.drawable.ic_settings, stringResource(R.string.settings_and_log_desc), onSettings) },
        below = { PrinterBar(vm.selected, onPrinters) },
    )
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (vm.documents.isNotEmpty()) {
            ActionCard(R.drawable.ic_printer, stringResource(R.string.continue_print), stringResource(R.string.files_chosen, vm.documents.size), onContinue)
        }
        ActionCard(R.drawable.ic_photo, stringResource(R.string.photos_title), stringResource(R.string.photos_sub), onPhotos)
        ActionCard(R.drawable.ic_document, stringResource(R.string.docs_title), stringResource(R.string.docs_sub), onDocuments)
        StatusLine(vm, showInfo = false)
        if (vm.selected == null) {
            SectionTitle(stringResource(R.string.usb_guide_title))
            SectionCard {
                StepRow(1, stringResource(R.string.usb_step1))
                StepRow(2, stringResource(R.string.usb_step2))
                StepRow(3, stringResource(R.string.usb_step3))
                Text(stringResource(R.string.wifi_auto), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            }
        }
    }
}

@Composable
private fun ColumnScope.PrintersScreen(vm: PrintViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    AppHeader(
        stringResource(R.string.printers_title),
        onBack = onBack,
        actions = {
            if (tab == 0) HeaderIcon(R.drawable.ic_add, stringResource(R.string.add_by_ip_desc)) { showAdd = true }
            HeaderIcon(R.drawable.ic_refresh, stringResource(R.string.scan_again)) { vm.refresh() }
        },
        below = { HeaderTabs(listOf(R.drawable.ic_wifi to stringResource(R.string.tab_wifi), R.drawable.ic_usb to stringResource(R.string.tab_usb), R.drawable.ic_bluetooth to stringResource(R.string.tab_bluetooth)), tab) { tab = it } },
    )
    val list = vm.printers.filter {
        when (tab) {
            1 -> it.usb != null
            2 -> it.ble != null
            else -> it.usb == null && it.ble == null
        }
    }
    val blePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    var showBridgeWifi by remember { mutableStateOf(false) }
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (vm.scanning) ProgressBanner(stringResource(R.string.searching))
        list.forEach { p -> PrinterRow(p, vm.selected?.id == p.id) { vm.selected = p; onBack() } }
        if (tab == 2 && vm.busy) ProgressBanner(vm.status)
        else if (tab == 2 && vm.status.isNotBlank()) Banner(vm.status, vm.statusTone)
        if (tab == 2 && vm.bleMissing.isNotEmpty()) {
            EmptyState(
                R.drawable.ic_bluetooth,
                stringResource(R.string.ble_perm_title),
                stringResource(R.string.ble_perm_body),
            )
        } else if (tab == 2 && vm.bleOff) {
            EmptyState(R.drawable.ic_bluetooth, stringResource(R.string.ble_off_title), stringResource(R.string.ble_off_body))
        } else if (list.isEmpty() && !vm.scanning) {
            if (tab == 2) EmptyState(
                R.drawable.ic_bluetooth,
                stringResource(R.string.ble_none_title),
                stringResource(R.string.ble_none_body),
            ) else if (tab == 0) EmptyState(
                R.drawable.ic_wifi,
                stringResource(R.string.wifi_none_title),
                stringResource(R.string.wifi_none_body),
            ) else EmptyState(
                R.drawable.ic_usb,
                stringResource(R.string.usb_none_title),
                stringResource(R.string.usb_none_body),
            )
        }
    }
    // Thumb-zone actions
    Surface(color = Palette.Paper, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) SecondaryButton(stringResource(R.string.add_printer_by_ip)) { showAdd = true }
            if (tab == 2 && vm.selected?.ble != null && !vm.busy) SecondaryButton(stringResource(R.string.bridge_connect_wifi)) { showBridgeWifi = true }
            if (tab == 2 && vm.bleMissing.isNotEmpty()) PrimaryButton(stringResource(R.string.ble_perm_title)) { blePermissions.launch(vm.bleMissing.toTypedArray()) }
            else PrimaryButton(stringResource(R.string.scan_again)) { vm.refresh() }
        }
    }
    if (showAdd) AddPrinterDialog(
        onDismiss = { showAdd = false },
        onAdd = { host, raw -> vm.addManual(host, raw); showAdd = false; onBack() },
    )
    val bridge = vm.selected
    if (showBridgeWifi && bridge?.ble != null) BridgeWifiDialog(
        onDismiss = { showBridgeWifi = false },
        onSave = { ssid, pass -> vm.bridgeWifi(bridge, ssid, pass); showBridgeWifi = false },
    )
}

/**
 * Sends a Wi-Fi network to a PrintBridge over Bluetooth. The bridge accepts it only while its setup network is up or
 * for 5 minutes after a short press on its BOOT button, and only over a paired (encrypted) link.
 */
@Composable
private fun BridgeWifiDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val passOk = pass.isEmpty() || pass.length in 8..64
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bridge_wifi_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bridge_wifi_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.network_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = !passOk, supportingText = { if (!passOk) Text(stringResource(R.string.password_error)) },
                )
            }
        },
        confirmButton = { TextButton({ onSave(ssid, pass) }, enabled = ssid.isNotBlank() && passOk) { Text(stringResource(R.string.save), color = Palette.TurquoiseDeep) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Palette.InkSoft) } },
    )
}

@Composable
private fun AddPrinterDialog(onDismiss: () -> Unit, onAdd: (String, Boolean) -> Unit) {
    var host by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Paper,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.add_ip_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    host, { host = it }, label = { Text(stringResource(R.string.ip_label)) }, placeholder = { Text("192.168.1.50") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Turquoise, focusedLabelColor = Palette.TurquoiseDeep, cursorColor = Palette.Turquoise),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(raw, { raw = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.TurquoiseDeep))
                    Column {
                        Text(stringResource(R.string.older_printer), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.older_printer_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onAdd(host, raw) }, enabled = host.isNotBlank()) { Text(stringResource(R.string.add), color = Palette.TurquoiseDeep) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Palette.InkSoft) } },
    )
}

@Composable
private fun ColumnScope.PrintScreen(vm: PrintViewModel, onBack: () -> Unit, onPrinters: () -> Unit, onAddMore: () -> Unit, onWizard: () -> Unit) {
    AppHeader(stringResource(R.string.print_title), onBack = onBack, below = { PrinterBar(vm.selected, onPrinters) })
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.files_title, vm.documents.size))
        vm.documents.toList().forEach { uri -> FileRow(uri) { vm.documents.remove(uri) } }
        TextButton(onAddMore) { Text(stringResource(R.string.add_more_files), color = Palette.TurquoiseDeep, style = MaterialTheme.typography.labelLarge) }

        SectionTitle(stringResource(R.string.options_title))
        SectionCard {
            OptionRow(stringResource(R.string.opt_color)) { SoftChip(if (vm.options.color) stringResource(R.string.color_on) else stringResource(R.string.color_off), vm.options.color) { vm.options = vm.options.copy(color = !vm.options.color) } }
            OptionRow(stringResource(R.string.opt_sides)) { SoftChip(stringResource(R.string.two_sided), vm.options.duplex) { vm.options = vm.options.copy(duplex = !vm.options.duplex) } }
            OptionRow(stringResource(R.string.opt_paper)) {
                PaperSize.values().forEach { size ->
                    SoftChip(size.label, vm.options.paper == size) { vm.options = vm.options.copy(paper = size) }
                }
            }
        }
        // Setup assistant (G4): for printers on a raw link that may not say which language they speak.
        if (vm.selected?.let(LanguageProbe::applies) == true) {
            TextButton(onWizard) { Text(stringResource(R.string.wizard_link), color = Palette.TurquoiseDeep, style = MaterialTheme.typography.labelLarge) }
        }
        if (vm.busy) BusyBar(stringResource(R.string.printing_desc))
        StatusLine(vm, showInfo = true)
    }
    Surface(color = Palette.Paper, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            when {
                vm.busy -> PrimaryButton(stringResource(R.string.printing_btn), enabled = false) {}
                vm.selected == null -> PrimaryButton(stringResource(R.string.choose_printer_btn), onClick = onPrinters)
                else -> PrimaryButton(stringResource(R.string.print_btn)) { vm.print() }
            }
        }
    }
}

@Composable
private fun FileRow(uri: Uri, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember(uri) { DocumentRenderer.displayName(ctx, uri) }
    val isImage = remember(uri) { DocumentRenderer.kindOf(ctx.contentResolver.getType(uri), name) == DocKind.IMAGE }
    Surface(shape = MaterialTheme.shapes.medium, color = Palette.Mist, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(if (isImage) R.drawable.ic_photo else R.drawable.ic_document, size = 44.dp)
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onRemove) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove_file, name), tint = Palette.InkSoft) }
        }
    }
}

@Composable
private fun ColumnScope.SettingsScreen(vm: PrintViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var serverUrl by remember { mutableStateOf(Settings.catalogueUrl(ctx)) }
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    AppHeader(stringResource(R.string.settings_title), onBack = onBack)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.appearance_title))
        SectionCard {
            Text(stringResource(R.string.appearance_desc), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to stringResource(R.string.theme_system), ThemeMode.LIGHT to stringResource(R.string.theme_light), ThemeMode.DARK to stringResource(R.string.theme_dark)).forEach { (m, label) ->
                    FilterChip(
                        selected = ThemePrefs.mode == m, onClick = { ThemePrefs.set(ctx, m) }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.TurquoiseTint, selectedLabelColor = Palette.Ink),
                    )
                }
            }
        }
        SectionTitle(stringResource(R.string.log_title))
        SectionCard {
            Text(stringResource(R.string.log_desc), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            SecondaryButton(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy_log)) { clipboard.setText(AnnotatedString(AppLog.text())); copied = true }
            if (AppLog.lines.isEmpty()) Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            AppLog.lines.takeLast(40).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Palette.Ink)
            }
        }
        SectionTitle(stringResource(R.string.driver_server_title))
        SectionCard {
            Text(stringResource(R.string.driver_server_desc), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            OutlinedTextField(
                serverUrl, { serverUrl = it; saved = false }, label = { Text(stringResource(R.string.server_address)) }, placeholder = { Text("http://192.168.1.20:8000") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Turquoise, focusedLabelColor = Palette.TurquoiseDeep, cursorColor = Palette.Turquoise),
            )
            PrimaryButton(stringResource(R.string.save_driver_server)) { vm.saveCatalogueUrl(serverUrl); saved = true }
            if (saved) Banner(stringResource(R.string.driver_server_saved), Tone.Info)
        }
    }
}
