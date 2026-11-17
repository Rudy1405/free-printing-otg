package com.freeprintotg

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.freeprintotg.ui.AppRoot
import com.freeprintotg.ui.AppTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.freeprintotg.driver.EngineStore
import com.freeprintotg.transport.UsbPermission
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
    var statusTone by mutableStateOf(com.freeprintotg.ui.Tone.Info)
    var scanning by mutableStateOf(false)
    var busy by mutableStateOf(false)
    private var scanJob: Job? = null
    var options by mutableStateOf(PrintOptions())
    private var netJob: Job? = null

    fun refresh() {
        val ctx = getApplication<Application>()
        val usb = ctx.getSystemService(android.content.Context.USB_SERVICE) as UsbManager
        printers.removeAll { it.usb != null }
        UsbDiscovery.scan(usb).forEach(::add)
        // Without permission the IEEE-1284 id (which picks the driver) cannot be read: ask now, then rescan.
        val pending = usb.deviceList.values.filter { d -> !usb.hasPermission(d) && (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 7 } }
        if (pending.isNotEmpty()) viewModelScope.launch {
            pending.forEach { d ->
                AppLog.d("USB printer ${"%04x:%04x".format(d.vendorId, d.productId)}: requesting permission")
                UsbPermission.ensure(ctx, usb, d)
            }
            printers.removeAll { it.usb != null }
            UsbDiscovery.scan(usb).forEach { p -> AppLog.d("USB printer ${p.name} id=${p.deviceId?.raw}"); add(p) }
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch { scanning = true; kotlinx.coroutines.delay(8000); scanning = false }
        netJob?.cancel()
        netJob = viewModelScope.launch {
            NetworkDiscovery(getApplication()).discover().catch { status = "Network discovery failed: ${it.message}"; statusTone = com.freeprintotg.ui.Tone.Error }.collect(::add)
        }
    }

    fun addManual(host: String, raw: Boolean) {
        val h = host.trim().substringBefore(':')
        if (h.isEmpty()) return
        add(
            if (raw) Printer("raw:$h:9100", "Printer $h (raw 9100)", Transport.RAW_9100, host = h, port = 9100)
            else Printer("ipp:$h:631", "Printer $h (IPP)", Transport.IPP, host = h, port = 631),
        )
        selected = printers.last()
    }

    fun saveCatalogueUrl(url: String) {
        val ctx = getApplication<Application>()
        Settings.setCatalogueUrl(ctx, url)
        EngineStore(ctx).clearCache()
        AppLog.d("Driver server set to ${Settings.catalogueUrl(ctx).ifBlank { "(none)" }}")
    }

    private fun add(p: Printer) {
        if (printers.none { it.id == p.id }) printers += p
        if (selected == null) selected = p
        status = "${printers.size} printer(s) found"; statusTone = com.freeprintotg.ui.Tone.Info
    }

    fun print() {
        val p = selected ?: return
        if (documents.isEmpty()) { status = "Choose a file first"; statusTone = com.freeprintotg.ui.Tone.Warning; return }
        busy = true
        statusTone = com.freeprintotg.ui.Tone.Info
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PrintJob(getApplication(), DriverStore(getApplication())).print(p, documents.toList(), options) { status = it; statusTone = com.freeprintotg.ui.Tone.Info }
                }
            } catch (e: Exception) {
                status = "Print failed: ${e.message}"
                statusTone = com.freeprintotg.ui.Tone.Error
                AppLog.d("Print failed: ${e::class.java.simpleName}: ${e.message}\n" + e.stackTraceToString().lines().take(6).joinToString("\n"))
            } finally { busy = false }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm by lazy { androidx.lifecycle.ViewModelProvider(this)[PrintViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { AppTheme { AppRoot(viewModel()) } }
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
}
