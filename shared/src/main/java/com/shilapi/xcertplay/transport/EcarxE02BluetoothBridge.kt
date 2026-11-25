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
    private const val CONTROL_BUFFER_BYTES = 1024

    fun open(
        address: String,
        onDiagnostic: (String) -> Unit,
    ): WirelessBluetoothLinkProvider.Link {
        val canonicalAddress = try {
            EcarxE02BluetoothProtocol.canonicalAddress(address)
        } catch (error: IllegalArgumentException) {
            throw IOException("ECarX Bluetooth received an invalid phone address", error)
        }
        val serial = LocalSocket()
        val spp = LocalSocket()
        var serialOutput: OutputStream? = null
        var connectAttempted = false
        try {
            logSocketInventory(onDiagnostic)
            // Android 9's LocalSocket.connect(address, timeout) is a stub that throws
            // UnsupportedOperationException; only the no-timeout overload reaches the socket.
            if (connectOptional(serial, "goc_serial", onDiagnostic)) {
                val controlOutput = serial.outputStream
                serialOutput = controlOutput
                drainControlSocket(serial.inputStream)
                connectAttempted = true
                controlOutput.write(EcarxE02BluetoothProtocol.connectCommand(canonicalAddress).toByteArray(Charsets.US_ASCII))
                controlOutput.flush()
            } else {
                onDiagnostic("ECarX goc_serial absent; using goc_spp alone")
            }

            connectReserved(spp, "goc_spp", onDiagnostic)
            val input = spp.inputStream
            val output = spp.outputStream
            output.write(EcarxE02BluetoothProtocol.socketHandshake(canonicalAddress))
            output.flush()
            runCatching { onDiagnostic("ECarX gocsdk RFCOMM bridge ready") }
            return Link(canonicalAddress, serial, serialOutput, spp, input, output)
        } catch (error: Exception) {
            if (connectAttempted) disconnect(serialOutput, canonicalAddress)
            closeQuietly(spp)
            closeQuietly(serial)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val detail = if (error is SecurityException) {
                "ECarX gocsdk socket access was denied"
            } else {
                "ECarX gocsdk Bluetooth link could not be opened"
            }
            throw IOException(detail, error)
        }
    }

    private fun connectReserved(socket: LocalSocket, name: String, onDiagnostic: (String) -> Unit) {
        try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.RESERVED))
            onDiagnostic("ECarX $name socket connected")
        } catch (error: Exception) {
            onDiagnostic("ECarX $name socket failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    /** Some E02 firmware exposes only goc_spp; a missing control socket is not fatal. */
    private fun connectOptional(socket: LocalSocket, name: String, onDiagnostic: (String) -> Unit): Boolean =
        try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.RESERVED))
            onDiagnostic("ECarX $name socket connected")
            true
        } catch (error: Exception) {
            onDiagnostic("ECarX $name socket unavailable: ${error.javaClass.simpleName}: ${error.message}")
            closeQuietly(socket)
            false
        }

    /** Once-only evidence for the next report: which vendor node this firmware exposes. */
    private fun logSocketInventory(onDiagnostic: (String) -> Unit) {
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
    }

    private fun drainControlSocket(input: InputStream) {
        Thread({
            val buffer = ByteArray(CONTROL_BUFFER_BYTES)
            try {
                while (input.read(buffer) >= 0) {
                    // Keep control responses off the RFCOMM stream and out of logs.
                }
            } catch (_: IOException) {
                // Closing the control socket ends this reader.
            }
        }, "diplay-e02-gocsdk-control").apply {
            isDaemon = true
            start()
        }
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
        private val serial: LocalSocket,
        private val serialOutput: OutputStream?,
        private val spp: LocalSocket,
        override val input: InputStream,
        override val output: OutputStream,
    ) : WirelessBluetoothLinkProvider.Link {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            EcarxE02BluetoothBridge.disconnect(serialOutput, address)
            EcarxE02BluetoothBridge.closeQuietly(spp)
            EcarxE02BluetoothBridge.closeQuietly(serial)
        }
    }
}
