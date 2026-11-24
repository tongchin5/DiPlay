package com.shilapi.xcertplay.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EcarxE02BluetoothLinkProviderTest {
    @Test fun savedPhoneAndVendorMacDriveTheBootstrapLink() {
        var opened: WirelessBluetoothLinkProvider.Phone? = null
        var closed = false
        val provider = EcarxE02BluetoothLinkProvider(
            RuntimeEnvironment.getApplication(),
            metadata = FakeMetadata(
                local = "aa:bb:cc:dd:ee:ff",
                current = "00:11:22:33:44:55",
                names = mapOf("00:11:22:33:44:55" to "Car iPhone"),
            ),
            bridge = EcarxE02BluetoothBridgeLauncher { phone, _ ->
                opened = phone
                FakeLink { closed = true }
            },
        )

        val phones = provider.pairedPhones(preferredAddress = null)
        assertEquals(listOf(WirelessBluetoothLinkProvider.Phone("00:11:22:33:44:55", "Car iPhone")), phones)
        assertEquals("AA:BB:CC:DD:EE:FF", provider.localAddress())
        provider.open(phones.single(), {}).close()

        assertEquals("00:11:22:33:44:55", opened?.address)
        assertTrue(closed)
    }

    @Test fun savedPhoneIsPreferredWhenVendorBluetoothCurrentlyShowsAnotherPhone() {
        val provider = EcarxE02BluetoothLinkProvider(
            RuntimeEnvironment.getApplication(),
            metadata = FakeMetadata(
                local = "aa:bb:cc:dd:ee:ff",
                current = "00:11:22:33:44:55",
                names = mapOf(
                    "00:11:22:33:44:55" to "Current phone",
                    "00:11:22:33:44:66" to "Saved iPhone",
                ),
            ),
            bridge = EcarxE02BluetoothBridgeLauncher { _, _ -> FakeLink() },
        )

        assertEquals(
            "00:11:22:33:44:66",
            provider.selectPhone("00:11:22:33:44:66").address,
        )
    }

    @Test fun savedPhoneRemainsSelectableWithoutResolvedName() {
        val provider = EcarxE02BluetoothLinkProvider(
            RuntimeEnvironment.getApplication(),
            metadata = FakeMetadata(
                local = "aa:bb:cc:dd:ee:ff",
                current = "00:11:22:33:44:55",
                names = mapOf("00:11:22:33:44:55" to "Current phone"),
            ),
            bridge = EcarxE02BluetoothBridgeLauncher { _, _ -> FakeLink() },
        )

        assertEquals("00:11:22:33:44:66", provider.selectPhone("00:11:22:33:44:66").address)
    }

    private class FakeMetadata(
        private val local: String?,
        private val current: String?,
        private val names: Map<String, String>,
    ) : EcarxE02BluetoothMetadata {
        override fun localAddress(): String? = local
        override fun currentPhoneAddress(): String? = current
        override fun phoneName(address: String): String? = names[address]
    }

    private class FakeLink(private val onClose: () -> Unit = {}) : WirelessBluetoothLinkProvider.Link {
        override val input = ByteArrayInputStream(byteArrayOf())
        override val output = ByteArrayOutputStream()
        override fun close() = onClose()
    }
}
