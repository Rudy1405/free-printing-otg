package com.freeprintotg.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.freeprintotg.AppLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Blocking GATT client for one PrintBridge. Android allows one outstanding GATT operation at a time, so every call
 * waits for its callback before the next one. Call from a background thread (PrintJob runs on Dispatchers.IO).
 * Needs BLUETOOTH_CONNECT on Android 12+ (checked by the caller before printing).
 */
@SuppressLint("MissingPermission")
class BridgeClient private constructor(private val ctx: Context, val address: String) : AutoCloseable {
    private sealed class Ev {
        data class State(val connected: Boolean, val status: Int) : Ev()
        data class Mtu(val mtu: Int) : Ev()
        data class Discovered(val status: Int) : Ev()
        data class Read(val value: ByteArray, val status: Int) : Ev()
        data class Wrote(val status: Int) : Ev()
    }

    private val ops = LinkedBlockingQueue<Ev>()
    /** Control notifications, in order. */
    val notices = LinkedBlockingQueue<Notice>()
    @Volatile var connected = false
        private set
    var mtu = 23
        private set

    private lateinit var gatt: BluetoothGatt
    private lateinit var info: BluetoothGattCharacteristic
    private lateinit var control: BluetoothGattCharacteristic
    private lateinit var data: BluetoothGattCharacteristic

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            connected = newState == BluetoothProfile.STATE_CONNECTED
            ops.offer(Ev.State(connected, status))
            if (!connected) notices.offer(Notice.Unknown(byteArrayOf()))  // wakes a waiter: the link is gone
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { ops.offer(Ev.Mtu(m)) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { ops.offer(Ev.Discovered(status)) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ops.offer(Ev.Wrote(status)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { ops.offer(Ev.Wrote(status)) }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            ops.offer(Ev.Read(value, status))
        }
        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") ops.offer(Ev.Read(c.value ?: byteArrayOf(), status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == BridgeProtocol.CONTROL) notices.offer(BridgeProtocol.parse(value))
        }
        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") if (c.uuid == BridgeProtocol.CONTROL) notices.offer(BridgeProtocol.parse(c.value ?: byteArrayOf()))
        }
    }

    private inline fun <reified T : Ev> await(what: String, timeoutMs: Long = OP_TIMEOUT_MS): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val e = if (left > 0) ops.poll(left, TimeUnit.MILLISECONDS) else null
            when {
                e == null -> error("Bluetooth bridge did not answer ($what)")
                e is T -> return e
                e is Ev.State && !e.connected -> error("Bluetooth bridge disconnected ($what)")
            }
        }
    }

    private fun open() {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("This phone has no Bluetooth")
        if (!adapter.isEnabled) error("Bluetooth is off. Turn it on and try again.")
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        val st = await<Ev.State>("connect", CONNECT_TIMEOUT_MS)
        if (!st.connected) error("Cannot connect to the Bluetooth bridge (status ${st.status})")
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        mtu = if (gatt.requestMtu(517)) runCatching { await<Ev.Mtu>("MTU").mtu }.getOrDefault(23) else 23
        if (!gatt.discoverServices()) error("Bluetooth service discovery failed")
        await<Ev.Discovered>("services")
        val svc = gatt.getService(BridgeProtocol.SERVICE) ?: error("This Bluetooth device is not a PrintBridge")
        info = svc.getCharacteristic(BridgeProtocol.INFO) ?: error("PrintBridge: no Info characteristic")
        control = svc.getCharacteristic(BridgeProtocol.CONTROL) ?: error("PrintBridge: no Control characteristic")
        data = svc.getCharacteristic(BridgeProtocol.DATA) ?: error("PrintBridge: no Data characteristic")
        gatt.setCharacteristicNotification(control, true)
        val cccd = control.getDescriptor(BridgeProtocol.CCCD) ?: error("PrintBridge: notifications unavailable")
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, enable)
        else @Suppress("DEPRECATION") run { cccd.value = enable; gatt.writeDescriptor(cccd) }
        await<Ev.Wrote>("enable notifications")
        AppLog.d("Bluetooth bridge $address connected, MTU $mtu")
    }

    fun readInfo(): BridgeInfo {
        if (!gatt.readCharacteristic(info)) error("Cannot read the bridge's Info")
        val r = await<Ev.Read>("Info")
        if (r.status != BluetoothGatt.GATT_SUCCESS) error("Reading the bridge's Info failed (status ${r.status})")
        return BridgeInfo.parse(String(r.value, Charsets.UTF_8))
    }

    fun sendControl(msg: ByteArray) = write(control, msg, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    /** One Data packet (at most [mtu] - 3 bytes). */
    fun sendData(chunk: ByteArray) = write(data, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    private fun write(c: BluetoothGattCharacteristic, value: ByteArray, type: Int) {
        if (!connected) error("Bluetooth bridge disconnected")
        val started = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
        else @Suppress("DEPRECATION") run { c.writeType = type; c.value = value; gatt.writeCharacteristic(c) }
        if (!started) error("Bluetooth write refused by the phone")
        val w = await<Ev.Wrote>("write")
        if (w.status != BluetoothGatt.GATT_SUCCESS) error("Bluetooth write failed (status ${w.status})")
    }

    /** Next Control notification, or null after [timeoutMs]. A dropped link raises an error. */
    fun nextNotice(timeoutMs: Long): Notice? {
        val n = notices.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (!connected) error("Bluetooth bridge disconnected")
        return n
    }

    /** Bonds (Just Works pairing) so the link is encrypted; the bridge only takes a Wi-Fi password that way. */
    fun ensureBonded(timeoutMs: Long = 30_000) {
        val dev = gatt.device
        if (dev.bondState == BluetoothDevice.BOND_BONDED) return
        if (dev.bondState != BluetoothDevice.BOND_BONDING && !dev.createBond()) error("Bluetooth pairing could not start")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (dev.bondState != BluetoothDevice.BOND_BONDED) {
            if (System.currentTimeMillis() > deadline) error("Bluetooth pairing was not completed")
            Thread.sleep(250)
        }
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        private const val OP_TIMEOUT_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        fun connect(ctx: Context, address: String): BridgeClient =
            BridgeClient(ctx.applicationContext, address).also { c ->
                try { c.open() } catch (e: Exception) { c.close(); throw e }
            }
    }
}
