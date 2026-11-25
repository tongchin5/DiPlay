package com.shilapi.xcertplay.transport

import android.net.LocalSocket
import android.net.LocalSocketAddress
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
            // Android 9's LocalSocket.connect(address, timeout) is a stub that throws
            // UnsupportedOperationException; only the no-timeout overload reaches the socket.
            serial.connect(LocalSocketAddress("goc_serial", LocalSocketAddress.Namespace.RESERVED))
            val controlOutput = serial.outputStream
            serialOutput = controlOutput
            drainControlSocket(serial.inputStream)
            connectAttempted = true
            controlOutput.write(EcarxE02BluetoothProtocol.connectCommand(canonicalAddress).toByteArray(Charsets.US_ASCII))
            controlOutput.flush()

            spp.connect(LocalSocketAddress("goc_spp", LocalSocketAddress.Namespace.RESERVED))
            val input = spp.inputStream
            val output = spp.outputStream
            output.write(EcarxE02BluetoothProtocol.socketHandshake(canonicalAddress))
            output.flush()
            runCatching { onDiagnostic("ECarX gocsdk RFCOMM bridge ready") }
            return Link(canonicalAddress, serial, controlOutput, spp, input, output)
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
        private val serialOutput: OutputStream,
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
