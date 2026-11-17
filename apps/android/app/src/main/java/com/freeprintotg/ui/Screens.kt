package com.freeprintotg.ui

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
        vm.selected == null && vm.scanning -> ProgressBanner("Searching for printers…")
        vm.selected == null -> Banner("No printer found yet. Plug one in by USB or pick a Wi-Fi printer.", Tone.Warning)
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
        "Free Print OTG",
        actions = { HeaderIcon(R.drawable.ic_settings, "Settings and log", onSettings) },
        below = { PrinterBar(vm.selected, onPrinters) },
    )
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (vm.documents.isNotEmpty()) {
            ActionCard(R.drawable.ic_printer, "Continue to print", "${vm.documents.size} file(s) chosen", onContinue)
        }
        ActionCard(R.drawable.ic_photo, "Photos & images", "JPEG, PNG, WebP", onPhotos)
        ActionCard(R.drawable.ic_document, "Documents", "PDF and text files", onDocuments)
        StatusLine(vm, showInfo = false)
        if (vm.selected == null) {
            SectionTitle("Print over USB")
            SectionCard {
                StepRow(1, "Plug the printer into your phone with a USB OTG cable.")
                StepRow(2, "Allow USB access when Android asks.")
                StepRow(3, "Pick the printer in the bar above.")
                Text("Wi-Fi printers on your network show up on their own.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            }
        }
    }
}

@Composable
private fun ColumnScope.PrintersScreen(vm: PrintViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    AppHeader(
        "Printers",
        onBack = onBack,
        actions = {
            if (tab == 0) HeaderIcon(R.drawable.ic_add, "Add by IP address") { showAdd = true }
            HeaderIcon(R.drawable.ic_refresh, "Scan again") { vm.refresh() }
        },
        below = { HeaderTabs(listOf(R.drawable.ic_wifi to "Wi-Fi", R.drawable.ic_usb to "USB"), tab) { tab = it } },
    )
    val list = vm.printers.filter { (it.usb != null) == (tab == 1) }
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (vm.scanning) ProgressBanner("Searching…")
        list.forEach { p -> PrinterRow(p, vm.selected?.id == p.id) { vm.selected = p; onBack() } }
        if (list.isEmpty() && !vm.scanning) {
            if (tab == 0) EmptyState(
                R.drawable.ic_wifi,
                "No Wi-Fi printers found",
                "Check that the printer is on and on the same Wi-Fi network as this phone. You can also add it by its IP address.",
            ) else EmptyState(
                R.drawable.ic_usb,
                "No USB printer found",
                "Plug the printer in with a USB OTG cable and allow access when Android asks. If nothing appears, check the cable and that the printer is on. Some phones don't support USB host mode.",
            )
        }
    }
    // Thumb-zone actions
    Surface(color = Palette.Paper, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) SecondaryButton("Add printer by IP address") { showAdd = true }
            PrimaryButton("Scan again") { vm.refresh() }
        }
    }
    if (showAdd) AddPrinterDialog(
        onDismiss = { showAdd = false },
        onAdd = { host, raw -> vm.addManual(host, raw); showAdd = false; onBack() },
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
        title = { Text("Add printer by IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    host, { host = it }, label = { Text("Printer IP address") }, placeholder = { Text("192.168.1.50") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Turquoise, focusedLabelColor = Palette.TurquoiseDeep, cursorColor = Palette.Turquoise),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(raw, { raw = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.TurquoiseDeep))
                    Column {
                        Text("Older printer (raw port 9100)", style = MaterialTheme.typography.bodyLarge)
                        Text("Leave off for most printers made after 2015.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onAdd(host, raw) }, enabled = host.isNotBlank()) { Text("Add", color = Palette.TurquoiseDeep) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.InkSoft) } },
    )
}

@Composable
private fun ColumnScope.PrintScreen(vm: PrintViewModel, onBack: () -> Unit, onPrinters: () -> Unit, onAddMore: () -> Unit, onWizard: () -> Unit) {
    AppHeader("Print", onBack = onBack, below = { PrinterBar(vm.selected, onPrinters) })
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Files (${vm.documents.size})")
        vm.documents.toList().forEach { uri -> FileRow(uri) { vm.documents.remove(uri) } }
        TextButton(onAddMore) { Text("+ Add more files", color = Palette.TurquoiseDeep, style = MaterialTheme.typography.labelLarge) }

        SectionTitle("Options")
        SectionCard {
            OptionRow("Color") { SoftChip(if (vm.options.color) "Color" else "Black & white", vm.options.color) { vm.options = vm.options.copy(color = !vm.options.color) } }
            OptionRow("Sides") { SoftChip("Two-sided", vm.options.duplex) { vm.options = vm.options.copy(duplex = !vm.options.duplex) } }
            OptionRow("Paper") {
                PaperSize.values().forEach { size ->
                    SoftChip(size.label, vm.options.paper == size) { vm.options = vm.options.copy(paper = size) }
                }
            }
        }
        // Setup assistant (G4): for printers on a raw link that may not say which language they speak.
        if (vm.selected?.let(LanguageProbe::applies) == true) {
            TextButton(onWizard) { Text("Prints garbage or nothing? Find the right setting", color = Palette.TurquoiseDeep, style = MaterialTheme.typography.labelLarge) }
        }
        if (vm.busy) BusyBar("Printing")
        StatusLine(vm, showInfo = true)
    }
    Surface(color = Palette.Paper, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            when {
                vm.busy -> PrimaryButton("Printing…", enabled = false) {}
                vm.selected == null -> PrimaryButton("Choose a printer", onClick = onPrinters)
                else -> PrimaryButton("Print") { vm.print() }
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
            IconButton(onRemove) { Icon(painterResource(R.drawable.ic_close), "Remove $name", tint = Palette.InkSoft) }
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
    AppHeader("Settings & log", onBack = onBack)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Log")
        SectionCard {
            Text("If a print fails, copy the log and send it to whoever is helping you.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            SecondaryButton(if (copied) "Copied" else "Copy log") { clipboard.setText(AnnotatedString(AppLog.text())); copied = true }
            if (AppLog.lines.isEmpty()) Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            AppLog.lines.takeLast(40).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Palette.Ink)
            }
        }
        SectionTitle("Driver server")
        SectionCard {
            Text("Only needed for printers that connect by USB and have no built-in driver.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            OutlinedTextField(
                serverUrl, { serverUrl = it; saved = false }, label = { Text("Server address") }, placeholder = { Text("http://192.168.1.20:8000") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Turquoise, focusedLabelColor = Palette.TurquoiseDeep, cursorColor = Palette.Turquoise),
            )
            PrimaryButton("Save driver server") { vm.saveCatalogueUrl(serverUrl); saved = true }
            if (saved) Banner("Saved. USB drivers will be downloaded from this server.", Tone.Info)
        }
    }
}
