package com.shilapi.xcertplay.transport

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Opens the E02 vendor RFCOMM channel without depending on Android's disabled Bluetooth adapter. */
internal object EcarxE02BluetoothBridge {
    private const val GOC_SERIAL_PATH = "/data/goc/goc_serial"

    private val CONTROL_ENDPOINTS = listOf(
        Endpoint(GOC_SERIAL_PATH, LocalSocketAddress.Namespace.FILESYSTEM, GOC_SERIAL_PATH),
        Endpoint("goc_serial", LocalSocketAddress.Namespace.RESERVED, "goc_serial"),
    )
    private val DATA_ENDPOINTS = listOf(
        Endpoint("goc_spp", LocalSocketAddress.Namespace.RESERVED, "goc_spp"),
        Endpoint("/data/goc/rfcomm", LocalSocketAddress.Namespace.FILESYSTEM, "/data/goc/rfcomm"),
    )

    fun open(
        address: String,
        onDiagnostic: (String) -> Unit,
    ): WirelessBluetoothLinkProvider.Link {
        val canonicalAddress = try {
            EcarxE02BluetoothProtocol.canonicalAddress(address)
        } catch (error: IllegalArgumentException) {
            throw IOException("ECarX Bluetooth received an invalid phone address", error)
        }
        logSocketInventory(onDiagnostic)
        val control = openControl(onDiagnostic)
            ?: throw IOException("ECarX gocsdk control channel is unavailable")
        var commandSent = false
        try {
            val data = connectFirst(DATA_ENDPOINTS, onDiagnostic)
                ?: throw IOException("ECarX gocsdk data socket is unavailable")
            val spp = data.second
            val input = spp.inputStream
            val output = spp.outputStream
            // goc_spp clients identify their device before the daemon carries iAP2 bytes.
            output.write(EcarxE02BluetoothProtocol.socketHandshake(canonicalAddress))
            output.flush()
            onDiagnostic("ECarX address handshake written to ${data.first.label}")
            control.output.write(
                EcarxE02BluetoothProtocol.connectCommand(canonicalAddress).toByteArray(Charsets.US_ASCII),
            )
            control.output.flush()
            commandSent = true
            // iAP2 is accessory-initiated; the caller writes the first identification frame.
            onDiagnostic("ECarX gocsdk RFCOMM bridge ready")
            return Link(canonicalAddress, control, spp, input, output)
        } catch (error: Exception) {
            if (commandSent) disconnect(control.output, canonicalAddress)
            control.close()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val detail = if (error is SecurityException) {
                "ECarX gocsdk socket access was denied"
            } else {
                "ECarX gocsdk Bluetooth link could not be opened"
            }
            throw IOException(detail, error)
        }
    }

    private class ControlChannel(
        val output: OutputStream,
        private val closeAction: () -> Unit,
    ) {
        fun close() = closeAction()
    }

    private fun openControl(onDiagnostic: (String) -> Unit): ControlChannel? {
        val serialFile = File(GOC_SERIAL_PATH)
        onDiagnostic(
            "ECarX $GOC_SERIAL_PATH exists=${serialFile.exists()} isFile=${serialFile.isFile} " +
                "canRead=${serialFile.canRead()} canWrite=${serialFile.canWrite()}",
        )
        runCatching {
            // Write-only: draining the pty would steal the vendor app's command replies.
            val output = java.io.FileOutputStream(serialFile)
            onDiagnostic("ECarX $GOC_SERIAL_PATH opened as a device")
            return ControlChannel(output) {
                runCatching { output.close() }
            }
        }.onFailure {
            onDiagnostic("ECarX $GOC_SERIAL_PATH open failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        val pair = connectFirst(CONTROL_ENDPOINTS, onDiagnostic) ?: return null
        val socket = pair.second
        onDiagnostic("ECarX control ${pair.first.label} opened")
        return ControlChannel(socket.outputStream) { closeQuietly(socket) }
    }

    private data class Endpoint(
        val path: String,
        val namespace: LocalSocketAddress.Namespace,
        val label: String,
    )

    private fun connect(endpoint: Endpoint, onDiagnostic: (String) -> Unit): LocalSocket? {
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(endpoint.path, endpoint.namespace))
            onDiagnostic("ECarX ${endpoint.label} connected")
            socket
        } catch (error: Exception) {
            onDiagnostic("ECarX ${endpoint.label} unavailable: ${error.javaClass.simpleName}: ${error.message}")
            closeQuietly(socket)
            null
        }
    }

    private fun connectFirst(
        endpoints: List<Endpoint>,
        onDiagnostic: (String) -> Unit,
    ): Pair<Endpoint, LocalSocket>? =
        endpoints.firstNotNullOfOrNull { endpoint -> connect(endpoint, onDiagnostic)?.let { endpoint to it } }

    private val inventoryLogged = AtomicBoolean(false)

    /** Once-only evidence for the next report: which vendor node this firmware exposes. */
    private fun logSocketInventory(onDiagnostic: (String) -> Unit) {
        if (!inventoryLogged.compareAndSet(false, true)) return
        runCatching {
            val all = File("/dev/socket").list().orEmpty()
            val entries = all.filter { it.contains("goc", ignoreCase = true) }
            onDiagnostic("ECarX /dev/socket goc entries=$entries all=${all.take(40)}")
        }.onFailure { onDiagnostic("ECarX /dev/socket unreadable: ${it.javaClass.simpleName}") }
        runCatching {
            val entries = File("/dev").list()
                ?.filter {
                    it.contains("goc", ignoreCase = true) ||
                        it.startsWith("stp", ignoreCase = true) ||
                        it.contains("spp", ignoreCase = true)
                }
                .orEmpty()
            onDiagnostic("ECarX /dev goc/stp/spp entries=$entries")
        }.onFailure { onDiagnostic("ECarX /dev unreadable: ${it.javaClass.simpleName}") }
        runCatching {
            val entries = File("/proc/net/unix").useLines { lines ->
                lines.filter { it.contains("goc", ignoreCase = true) }.take(10).toList()
            }
            onDiagnostic("ECarX /proc/net/unix goc entries=${entries.size} ${entries.joinToString(" | ")}")
        }.onFailure { onDiagnostic("ECarX /proc/net/unix unreadable: ${it.javaClass.simpleName}") }
        val serialDevice = File("/dev/goc_serial")
        onDiagnostic("ECarX /dev/goc_serial exists=${serialDevice.exists()} readable=${serialDevice.canRead()}")
        val gocDirectory = File("/data/goc")
        onDiagnostic(
            "ECarX /data/goc exists=${gocDirectory.exists()} readable=${gocDirectory.canRead()} " +
                "entries=${gocDirectory.list()?.take(20)}",
        )
    }

    private fun disconnect(output: OutputStream?, address: String) {
        if (output == null) return
        runCatching {
            output.write(EcarxE02BluetoothProtocol.disconnectCommand(address).toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    private fun closeQuietly(socket: LocalSocket) {
        runCatching { socket.close() }
    }

    private class Link(
        private val address: String,
        private val control: ControlChannel,
        private val spp: LocalSocket,
        override val input: InputStream,
        override val output: OutputStream,
    ) : WirelessBluetoothLinkProvider.Link {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            EcarxE02BluetoothBridge.disconnect(control.output, address)
            EcarxE02BluetoothBridge.closeQuietly(spp)
            control.close()
        }
    }
}
