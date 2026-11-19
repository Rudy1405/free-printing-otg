package com.freeprintotg.ui

import androidx.compose.ui.res.stringResource
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
                message = getApplication<Application>().getString(R.string.wiz_test_failed, e.message) to Tone.Error
            } finally { busy = false }
        }
    }

    fun readable() {
        val lang = current ?: return
        val k = key
        if (k == null) message = getApplication<Application>().getString(R.string.wiz_no_identity) to Tone.Warning
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

    AppHeader(stringResource(R.string.wizard_title), onBack = onBack)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = vm.printer
        when {
            printer == null -> EmptyState(R.drawable.ic_printer, stringResource(R.string.wiz_no_printer_title), stringResource(R.string.wiz_no_printer_body))
            p != null && !LanguageProbe.applies(p) -> EmptyState(
                R.drawable.ic_printer, stringResource(R.string.wiz_no_setup_title),
                stringResource(R.string.wiz_no_setup_body),
            )
            p != null -> {
                SectionTitle(p.name)
                vm.saved?.let { lang ->
                    SectionCard {
                        Text(stringResource(R.string.wiz_set_to, lang.title), style = MaterialTheme.typography.bodyLarge)
                        if (vm.phase != LanguageWizardViewModel.Phase.Done) SecondaryButton(stringResource(R.string.wiz_forget)) { vm.forget() }
                    }
                }
                val total = vm.candidates.size
                when (vm.phase) {
                    LanguageWizardViewModel.Phase.Ready -> SectionCard {
                        if (vm.index == 0) {
                            StepRow(1, stringResource(R.string.wiz_step1, total))
                            StepRow(2, stringResource(R.string.wiz_step2))
                            StepRow(3, stringResource(R.string.wiz_step3))
                            Text(stringResource(R.string.wiz_each_test), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                        }
                        Text(stringResource(R.string.wiz_next, vm.index + 1, total, vm.current?.title.orEmpty()), style = MaterialTheme.typography.titleMedium)
                    }
                    LanguageWizardViewModel.Phase.Asking -> SectionCard {
                        Text(stringResource(R.string.wiz_did_read, vm.current?.title.orEmpty()), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.wiz_wait), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                    }
                    LanguageWizardViewModel.Phase.Done -> SectionCard {
                        Text(stringResource(R.string.wiz_saved, (vm.saved?.title ?: vm.current?.title).orEmpty()), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.wiz_share_help), style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                    }
                    LanguageWizardViewModel.Phase.NoneWorked -> Banner(
                        stringResource(R.string.wiz_none_worked, total),
                        Tone.Warning,
                    )
                }
                if (vm.busy) ProgressBanner(vm.message?.first ?: stringResource(R.string.wiz_sending_test))
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
                        SecondaryButton(stringResource(R.string.wiz_skip, vm.current?.title.orEmpty())) { vm.skip() }
                        PrimaryButton(if (vm.busy) stringResource(R.string.wiz_sending) else stringResource(R.string.wiz_print_test, vm.index + 1, vm.candidates.size), enabled = !vm.busy) { vm.sendTest() }
                    }
                    LanguageWizardViewModel.Phase.Asking -> {
                        TextButton({ vm.sendTest() }, enabled = !vm.busy) { Text(stringResource(R.string.wiz_print_again), color = Palette.TurquoiseDeep) }
                        SecondaryButton(stringResource(R.string.wiz_no)) { vm.notReadable() }
                        PrimaryButton(stringResource(R.string.wiz_yes)) { vm.readable() }
                    }
                    LanguageWizardViewModel.Phase.Done -> {
                        vm.shareText()?.let { json ->
                            val shareTitle = stringResource(R.string.wiz_share_title)
                            SecondaryButton(stringResource(R.string.wiz_share)) {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json), shareTitle))
                            }
                        }
                        PrimaryButton(stringResource(R.string.done), onClick = onBack)
                    }
                    LanguageWizardViewModel.Phase.NoneWorked -> {
                        SecondaryButton(stringResource(R.string.start_over)) { vm.restart() }
                        PrimaryButton(stringResource(R.string.done), onClick = onBack)
                    }
                }
            }
        }
    }
}
