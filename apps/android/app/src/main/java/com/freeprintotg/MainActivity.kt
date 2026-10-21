package com.freeprintotg

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freeprintotg.discovery.NetworkDiscovery
import com.freeprintotg.discovery.UsbDiscovery
import com.freeprintotg.driver.DriverStore
import com.freeprintotg.render.PaperSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrintViewModel(app: Application) : AndroidViewModel(app) {
    val printers = mutableStateListOf<Printer>()
    val documents = mutableStateListOf<Uri>()
    var selected by mutableStateOf<Printer?>(null)
    var status by mutableStateOf("Searching for printers…")
    var busy by mutableStateOf(false)
    var options by mutableStateOf(PrintOptions())
    private var netJob: Job? = null

    fun refresh() {
        val usb = getApplication<Application>().getSystemService(android.content.Context.USB_SERVICE) as UsbManager
        printers.removeAll { it.usb != null }
        UsbDiscovery.scan(usb).forEach(::add)
        netJob?.cancel()
        netJob = viewModelScope.launch {
            NetworkDiscovery(getApplication()).discover().catch { status = "Network discovery failed: ${it.message}" }.collect(::add)
        }
    }

    private fun add(p: Printer) {
        if (printers.none { it.id == p.id }) printers += p
        if (selected == null) selected = p
        status = "${printers.size} printer(s) found"
    }

    fun print() {
        val p = selected ?: return
        if (documents.isEmpty()) { status = "Choose a file first"; return }
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PrintJob(getApplication(), DriverStore(getApplication())).print(p, documents.toList(), options) { status = it }
                }
            } catch (e: Exception) {
                status = "Print failed: ${e.message}"
            } finally { busy = false }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm by lazy { androidx.lifecycle.ViewModelProvider(this)[PrintViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeIntent(intent)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
        vm.refresh() // USB_DEVICE_ATTACHED lands here too
    }

    private fun takeIntent(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(vm.documents::add)
            Intent.ACTION_SEND_MULTIPLE -> @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(vm.documents::addAll)
            Intent.ACTION_VIEW -> i.data?.let(vm.documents::add)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val vm: PrintViewModel = viewModel()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.documents.addAll(it) }
        LaunchedEffect(Unit) { vm.refresh() }

        Scaffold(topBar = { TopAppBar(title = { Text("Free Print OTG") }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(vm.status, style = MaterialTheme.typography.bodyMedium)

                Text("Printers", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.printers, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text(if (p.usb != null) "USB (OTG)" else "${p.transport} · ${p.host}") },
                            leadingContent = { RadioButton(selected = vm.selected?.id == p.id, onClick = { vm.selected = p }) },
                            modifier = Modifier.clickable { vm.selected = p },
                        )
                    }
                }
                OutlinedButton(onClick = { vm.refresh() }) { Text("Rescan") }

                Text("Documents (${vm.documents.size})", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf", "text/*")) }) { Text("Choose files") }
                vm.documents.forEach { Text(it.lastPathSegment ?: it.toString(), style = MaterialTheme.typography.bodySmall) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vm.options.color, { vm.options = vm.options.copy(color = !vm.options.color) }, { Text("Color") })
                    FilterChip(vm.options.duplex, { vm.options = vm.options.copy(duplex = !vm.options.duplex) }, { Text("Duplex") })
                    FilterChip(
                        vm.options.paper == PaperSize.LETTER,
                        { vm.options = vm.options.copy(paper = if (vm.options.paper == PaperSize.A4) PaperSize.LETTER else PaperSize.A4) },
                        { Text(vm.options.paper.label) },
                    )
                }
                Button(onClick = { vm.print() }, enabled = !vm.busy && vm.selected != null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.busy) "Printing…" else "Print")
                }
            }
        }
    }
}
