package com.freeprintotg.transport

import android.content.Context
import com.freeprintotg.AppLog
import com.freeprintotg.ble.BridgeClient
import com.freeprintotg.ble.BridgeProtocol
import com.freeprintotg.ble.CreditWindow
import com.freeprintotg.ble.Notice

/**
 * Print stream over Bluetooth LE to a PrintBridge (docs/BLE-APP-PLAN.md, phase B). Opens a job, sends Data packets
 * only while the bridge has granted credit, then ends the job and waits until the bridge has passed every byte to
 * the printer over USB. [onProgress] gets (sent, total) after each packet.
 */
class BleSink(
    ctx: Context,
    address: String,
    private val total: Long,
    private val onProgress: (Long, Long) -> Unit = { _, _ -> },
) : PrintSink {
    private val client = BridgeClient.connect(ctx, address)
    private val jobId = (System.nanoTime() and 0xFFFF).toInt()
    private val credit = CreditWindow()
    private var sent = 0L
    private var finished = false

    init {
        try {
            client.sendControl(BridgeProtocol.openJob(jobId, total))
            awaitCredit()
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    /** Handles one notification; true when it granted credit. Errors from the bridge become exceptions. */
    private fun handle(n: Notice?): Boolean = when (n) {
        is Notice.Credit -> if (n.jobId == jobId) { credit.grant(n.bytes); true } else false
        is Notice.Error -> if (n.jobId == jobId || n.jobId == 0) error(n.error.message) else false
        else -> false
    }

    private fun awaitCredit() {
        val deadline = System.currentTimeMillis() + CREDIT_TIMEOUT_MS
        while (credit.available == 0L) {
            if (System.currentTimeMillis() > deadline) error("The bridge stopped accepting data")
            handle(client.nextNotice(500))
        }
    }

    override fun write(data: ByteArray, off: Int, len: Int) {
        var p = off
        val end = off + len
        val packet = client.mtu - BridgeProtocol.ATT_OVERHEAD
        while (p < end) {
            // Use any credit that arrived meanwhile without blocking.
            while (true) { val n = client.notices.poll() ?: break; handle(n) }
            if (credit.available == 0L) awaitCredit()
            val n = credit.take(minOf(packet, end - p))
            client.sendData(data.copyOfRange(p, p + n))
            p += n
            sent += n
            onProgress(sent, total)
        }
    }

    override fun close() {
        if (finished) return
        finished = true
        try {
            client.sendControl(BridgeProtocol.endJob(jobId))
            val deadline = System.currentTimeMillis() + DONE_TIMEOUT_MS
            while (true) {
                if (System.currentTimeMillis() > deadline) error("The bridge did not confirm the end of the job")
                when (val n = client.nextNotice(1000)) {
                    is Notice.Done -> if (n.jobId == jobId) break
                    else -> handle(n)
                }
            }
            AppLog.d("Bluetooth job $jobId done: $sent bytes")
        } finally {
            client.close()
        }
    }

    companion object {
        private const val CREDIT_TIMEOUT_MS = 30_000L
        private const val DONE_TIMEOUT_MS = 120_000L
    }
}
