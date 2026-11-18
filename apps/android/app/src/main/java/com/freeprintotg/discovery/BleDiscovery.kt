package com.freeprintotg.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.freeprintotg.AppLog
import com.freeprintotg.BleRef
import com.freeprintotg.Printer
import com.freeprintotg.Transport
import com.freeprintotg.ble.BridgeProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Finds PrintBridges over Bluetooth LE by their service UUID. Only the advertised name is known here; who the
 * printer behind the bridge is gets read from the Info characteristic when printing (PrintJob.prepare).
 */
class BleDiscovery(private val ctx: Context) {

    /** Runtime permissions BLE scanning and connecting need on this Android version. */
    fun missingPermissions(): List<String> = requiredPermissions().filter {
        ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun available(): Boolean =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun discover(): Flow<Printer> = callbackFlow {
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        if (scanner == null || missingPermissions().isNotEmpty()) { close(); return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: "PrintBridge"
                trySend(Printer("ble:${r.device.address}", name, Transport.BLE_BRIDGE, ble = BleRef(r.device.address)))
            }
            override fun onScanFailed(code: Int) { AppLog.d("Bluetooth scan failed: $code"); close() }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BridgeProtocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    companion object {
        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
