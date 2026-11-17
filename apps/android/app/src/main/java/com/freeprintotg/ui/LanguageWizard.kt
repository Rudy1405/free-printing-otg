package com.freeprintotg.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freeprintotg.AppLog
import com.freeprintotg.LearnedLanguages
import com.freeprintotg.PrintJob
import com.freeprintotg.Printer
import com.freeprintotg.R
import com.freeprintotg.driver.DriverStore
import com.freeprintotg.driver.Language
import com.freeprintotg.driver.LanguageProbe
import com.freeprintotg.driver.LearnedEntry
import com.freeprintotg.driver.PrinterIdentity
import com.freeprintotg.render.MediaKind
import com.freeprintotg.render.PaperSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup assistant (docs/GENERIC-PRINTING-PLAN.md section 5, G4): prints one test page per candidate language and
 * remembers the one the user says came out readable. Self-contained: its own view model, one entry from the Print screen.
 */
class LanguageWizardViewModel(app: Application) : AndroidViewModel(app) {
    enum class Phase { Ready, Asking, Done, NoneWorked }

    var printer by mutableStateOf<Printer?>(null); private set
    var candidates by mutableStateOf(emptyList<Language>()); private set
    var index by mutableIntStateOf(0); private set
    var phase by mutableStateOf(Phase.Ready); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<Pair<String, Tone>?>(null); private set
    /** Language already saved for this printer, if any. */
    var saved by mutableStateOf<Language?>(null); private set
    private var sheet = PaperSize.A4

    private val key get() = printer?.let { PrinterIdentity.of(it).key }
    val current get() = candidates.getOrNull(index)

    fun start(p: Printer, sheetSize: PaperSize) {
        if (printer?.id == p.id) return
        printer = p
        sheet = if (sheetSize.kind == MediaKind.SHEET) sheetSize else PaperSize.A4
        candidates = LanguageProbe.candidates(PrinterIdentity.of(p))
        index = 0; phase = Phase.Ready; message = null
        saved = key?.let { LearnedLanguages.all(getApplication())[it] }
    }

    fun restart() { index = 0; phase = Phase.Ready; message = null }

    fun sendTest() {
        val p = printer ?: return
        val lang = current ?: return
        busy = true; message = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PrintJob(getApplication(), DriverStore(getApplication()))
                        .printTestPage(p, lang, index + 1, candidates.size, sheet) { message = it to Tone.Info }
                }
                message = null
                phase = Phase.Asking
            } catch (e: Exception) {
                AppLog.d("Test page failed: ${e.message}")
                message = "Could not send the test page: ${e.message}" to Tone.Error
            } finally { busy = false }
        }
    }

    fun readable() {
        val lang = current ?: return
        val k = key
        if (k == null) message = "This printer reports no name or address, so the choice cannot be remembered." to Tone.Warning
        else { LearnedLanguages.save(getApplication(), k, lang); saved = lang; AppLog.d("Assistant: saved $lang for $k") }
        phase = Phase.Done
    }

    fun notReadable() {
        if (index + 1 < candidates.size) { index++; phase = Phase.Ready } else phase = Phase.NoneWorked
    }

    fun skip() = notReadable()

    fun forget() {
        key?.let { LearnedLanguages.forget(getApplication(), it) }
        saved = null
    }

    fun shareText(): String? = printer?.let { p -> saved?.let { LearnedEntry.of(p, it)?.toJson() } }
}

@Composable
fun ColumnScope.LanguageWizardScreen(printer: Printer?, sheet: PaperSize, onBack: () -> Unit) {
    val vm: LanguageWizardViewModel = viewModel()
    val ctx = LocalContext.current
    LaunchedEffect(printer?.id) { printer?.let { vm.start(it, sheet) } }

    AppHeader("Printer setup", onBack = onBack)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = vm.printer
        when {
            printer == null -> EmptyState(R.drawable.ic_printer, "No printer chosen", "Pick a printer first, then come back here.")
            p != null && !LanguageProbe.applies(p) -> EmptyState(
                R.drawable.ic_printer, "No setup needed",
                "This printer tells the app which formats it accepts, so there is nothing to test.",
            )
            p != null -> {
                SectionTitle(p.name)
                vm.saved?.let { lang ->
                    SectionCard {
                        Text("This printer is set to ${lang.title}.", style = MaterialTheme.typography.bodyLarge)
                        if (vm.phase != LanguageWizardViewModel.Phase.Done) SecondaryButton("Forget this setting") { vm.forget() }
                    }
                }
                val total = vm.candidates.size
                when (vm.phase) {
                    LanguageWizardViewModel.Phase.Ready -> SectionCard {
                        if (vm.index == 0) {
                            StepRow(1, "The app prints one short test page per printer language, up to $total.")
                            StepRow(2, "Watch the printer after each one.")
                            StepRow(3, "Say whether a readable page came out. Garbage or nothing means no.")
                            Text("Each test may use one sheet, label or a bit of receipt paper.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                        }
                        Text("Next: test ${vm.index + 1} of $total, ${vm.current?.title}", style = MaterialTheme.typography.titleMedium)
                    }
                    LanguageWizardViewModel.Phase.Asking -> SectionCard {
                        Text("Did a readable page come out for ${vm.current?.title}?", style = MaterialTheme.typography.titleMedium)
                        Text("Wait a few seconds: some printers are slow to start.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                    }
                    LanguageWizardViewModel.Phase.Done -> SectionCard {
                        Text("Saved. Your next prints to this printer use ${vm.saved?.title ?: vm.current?.title}.", style = MaterialTheme.typography.bodyLarge)
                        Text("Sharing the setting helps add this printer to the app's list for everyone.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                    }
                    LanguageWizardViewModel.Phase.NoneWorked -> Banner(
                        "None of the $total languages worked. The printer may need a driver pack (set a driver server in Settings) or work only with its Windows driver. Copy the log in Settings to report it.",
                        Tone.Warning,
                    )
                }
                if (vm.busy) ProgressBanner(vm.message?.first ?: "Sending test page…")
                else vm.message?.let { (text, tone) -> Banner(text, tone) }
            }
        }
    }
    val p = vm.printer
    if (printer != null && p != null && LanguageProbe.applies(p)) {
        Surface(color = Palette.Paper, shadowElevation = 8.dp) {
            Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (vm.phase) {
                    LanguageWizardViewModel.Phase.Ready -> {
                        SecondaryButton("Skip ${vm.current?.title}") { vm.skip() }
                        PrimaryButton(if (vm.busy) "Sending…" else "Print test ${vm.index + 1} of ${vm.candidates.size}", enabled = !vm.busy) { vm.sendTest() }
                    }
                    LanguageWizardViewModel.Phase.Asking -> {
                        TextButton({ vm.sendTest() }, enabled = !vm.busy) { Text("Print it again", color = Palette.TurquoiseDeep) }
                        SecondaryButton("No: nothing or garbage") { vm.notReadable() }
                        PrimaryButton("Yes, it's readable") { vm.readable() }
                    }
                    LanguageWizardViewModel.Phase.Done -> {
                        vm.shareText()?.let { json ->
                            SecondaryButton("Share this setting") {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json), "Share printer setting"))
                            }
                        }
                        PrimaryButton("Done", onClick = onBack)
                    }
                    LanguageWizardViewModel.Phase.NoneWorked -> {
                        SecondaryButton("Start over") { vm.restart() }
                        PrimaryButton("Done", onClick = onBack)
                    }
                }
            }
        }
    }
}
