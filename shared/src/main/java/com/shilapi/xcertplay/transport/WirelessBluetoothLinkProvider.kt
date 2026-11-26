package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Supplies the RFCOMM byte link used to bootstrap wireless CarPlay. */
interface WirelessBluetoothLinkProvider {
    data class Phone(val address: String, val name: String?)

    interface Link : Closeable {
        val input: InputStream
        val output: OutputStream
    }

    /** Paired phones the provider can identify, including [preferredAddress] when still known. */
    fun pairedPhones(preferredAddress: String?): List<Phone>
    /** Resolves the saved phone or the provider's sole unambiguous phone. */
    fun selectPhone(preferredAddress: String?): Phone

    /** The head unit's Bluetooth address used by the CarPlay accessory identity. */
    fun localAddress(): String

    /** Opens an iAP2 RFCOMM link. Blocking; [Link.close] releases the vendor-side channel. */
    fun open(phone: Phone, onDiagnostic: (String) -> Unit): Link
}
