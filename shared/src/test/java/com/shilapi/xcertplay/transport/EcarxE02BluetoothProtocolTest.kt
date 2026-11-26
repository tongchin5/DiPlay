package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EcarxE02BluetoothProtocolTest {
    @Test fun connectCommandMatchesTheE02GocsdkFormat() {
        assertEquals(
            "AT#SP00112233445500000000-deca-fade-deca-deafdecacafe\r\n",
            EcarxE02BluetoothProtocol.connectCommand("00:11:22:33:44:55"),
        )
    }

    @Test fun disconnectCommandReleasesTheSelectedPhone() {
        assertEquals(
            "AT#SH001122334455\r\n",
            EcarxE02BluetoothProtocol.disconnectCommand("00:11:22:33:44:55"),
        )
    }

    @Test fun socketHandshakeIsTwelveAsciiHexAddressBytes() {
        assertArrayEquals(
            "001122334455".toByteArray(Charsets.US_ASCII),
            EcarxE02BluetoothProtocol.socketHandshake("00:11:22:33:44:55"),
        )
    }

    @Test fun malformedAddressCannotReachTheVendorBridge() {
        listOf("00:11:22:33:44", "00:11:22:33:44:55;id", "gocsdk").forEach { address ->
            try {
                EcarxE02BluetoothProtocol.socketHandshake(address)
                throw AssertionError("accepted malformed address: $address")
            } catch (_: IllegalArgumentException) {
                // The bridge accepts only one Bluetooth address.
            }
        }
    }
}
