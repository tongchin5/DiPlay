package com.shilapi.xcertplay.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import com.nforetek.bt.aidl.INfCommandBluetooth
import com.shilapi.xcertplay.network.ConnectionFailureCause
import com.shilapi.xcertplay.network.ConnectionFailureException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** E02 v333 wireless path: vendor Bluetooth metadata plus the gocsdk RFCOMM bridge. */
class EcarxE02BluetoothLinkProvider internal constructor(
    context: Context,
    private val metadata: EcarxE02BluetoothMetadata = NforetekBluetoothApi,
    private val bridge: EcarxE02BluetoothBridgeLauncher = EcarxE02BluetoothBridgeLauncher { phone, log ->
        EcarxE02BluetoothBridge.open(phone.address, log)
    },
) : WirelessBluetoothLinkProvider {
    private val appContext = context.applicationContext

    init {
        NforetekBluetoothApi.initialize(appContext)
    }

    override fun pairedPhones(preferredAddress: String?): List<WirelessBluetoothLinkProvider.Phone> {
        val current = canonicalOrNull(metadata.currentPhoneAddress())
        val candidates = linkedMapOf<String, String?>()
        current?.let { candidates[it] = phoneName(it) }
        val preferred = canonicalOrNull(preferredAddress)
        if (preferred != null && preferred != current) {
            candidates[preferred] = phoneName(preferred)
        }
        return candidates.map { (address, name) -> WirelessBluetoothLinkProvider.Phone(address, name) }
    }

    private fun phoneName(address: String): String? =
        runCatching { metadata.phoneName(address) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(address, ignoreCase = true) }

    override fun selectPhone(preferredAddress: String?): WirelessBluetoothLinkProvider.Phone {
        val phones = pairedPhones(preferredAddress)
        canonicalOrNull(preferredAddress)?.let { preferred ->
            phones.firstOrNull { it.address.equals(preferred, ignoreCase = true) }?.let { return it }
            throw ConnectionFailureException(
                ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED,
                "The selected iPhone is no longer paired. Choose it again in DiPlay.",
            )
        }
        return when (phones.size) {
            1 -> phones.single()
            0 -> throw ConnectionFailureException(
                ConnectionFailureCause.NO_PAIRED_IPHONE,
                "Connect your iPhone in the car's Bluetooth screen, then choose it in DiPlay.",
            )
            else -> throw ConnectionFailureException(
                ConnectionFailureCause.IPHONE_NOT_CHOSEN,
                "Several ECarX phones are available. Choose an iPhone in DiPlay.",
            )
        }
    }

    override fun localAddress(): String {
        val address = canonicalOrNull(metadata.localAddress())
            ?: throw IOException("ECarX Bluetooth did not report a usable local address")
        return address
    }

    override fun open(
        phone: WirelessBluetoothLinkProvider.Phone,
        onDiagnostic: (String) -> Unit,
    ): WirelessBluetoothLinkProvider.Link {
        val address = canonicalOrNull(phone.address)
            ?: throw IOException("ECarX Bluetooth received an invalid phone address")
        onDiagnostic("ECarX gocsdk RFCOMM bridge starting")
        return bridge.open(WirelessBluetoothLinkProvider.Phone(address, phone.name), onDiagnostic)
    }

    companion object {
        private const val E02_MANUFACTURER = "ecarx"
        private const val E02_PRODUCT = "IHU524P"
        private const val NFORETEK_PACKAGE = "com.nforetek.bt"
        private const val NFORETEK_SERVICE = "com.nforetek.bt.service.NfServiceBluetooth"

        /** Enables this vendor path only on the dumped ECarX E02 product. */
        @JvmStatic
        fun createIfSupported(context: Context): EcarxE02BluetoothLinkProvider? {
            if (!android.os.Build.MANUFACTURER.equals(E02_MANUFACTURER, ignoreCase = true) ||
                !android.os.Build.PRODUCT.equals(E02_PRODUCT, ignoreCase = true)
            ) return null
            val component = ComponentName(NFORETEK_PACKAGE, NFORETEK_SERVICE)
            val service = runCatching { context.packageManager.getServiceInfo(component, 0) }.getOrNull()
                ?: return null
            if (!service.exported) return null
            return EcarxE02BluetoothLinkProvider(context)
        }
    }
}

internal interface EcarxE02BluetoothMetadata {
    fun localAddress(): String?
    fun currentPhoneAddress(): String?
    fun phoneName(address: String): String?
}

internal fun interface EcarxE02BluetoothBridgeLauncher {
    fun open(
        phone: WirelessBluetoothLinkProvider.Phone,
        onDiagnostic: (String) -> Unit,
    ): WirelessBluetoothLinkProvider.Link
}

private object NforetekBluetoothApi : EcarxE02BluetoothMetadata {
    private const val PACKAGE = "com.nforetek.bt"
    private const val SERVICE = "com.nforetek.bt.service.NfServiceBluetooth"
    private const val BIND_TIMEOUT_MILLIS = 5_000L
    private const val SERVICE_READY_TIMEOUT_MILLIS = 3_000L

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var service: INfCommandBluetooth? = null
    private var bindingStarted = false
    private var bindingLatch = CountDownLatch(1)
    private var serviceConnection: ServiceConnection? = null
    private var bindingFailure: IOException? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun localAddress(): String? = invokeVendor { it.getBtLocalAddress() }

    override fun currentPhoneAddress(): String? = invokeVendor { it.getBtAutoConnectingAddress() }

    override fun phoneName(address: String): String? = invokeVendor { it.getBtRemoteDeviceName(address) }

    private fun <T> invokeVendor(call: (INfCommandBluetooth) -> T): T {
        val api = connectedService()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERVICE_READY_TIMEOUT_MILLIS)
        while (!runCatching { api.isBluetoothServiceReady() }.getOrDefault(false)) {
            if (System.nanoTime() >= deadline) throw IOException("ECarX Bluetooth service is not ready")
            SystemClock.sleep(100)
        }
        return try {
            call(api)
        } catch (error: RemoteException) {
            throw IOException("ECarX Bluetooth service call failed", error)
        } catch (error: SecurityException) {
            throw IOException("ECarX Bluetooth service denied DiPlay", error)
        }
    }

    private fun connectedService(): INfCommandBluetooth {
        val context = appContext ?: throw IOException("ECarX Bluetooth provider was not initialized")
        val latch: CountDownLatch
        synchronized(lock) {
            service?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let { return it }
            // Keep this binding for the process lifetime; vendor onDestroy tears down gocsdk.
            if (!bindingStarted) {
                bindingStarted = true
                bindingFailure = null
                bindingLatch = CountDownLatch(1)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        synchronized(lock) {
                            service = INfCommandBluetooth.Stub.asInterface(binder)
                            bindingFailure = if (service == null) IOException("ECarX Bluetooth binder is unavailable") else null
                            bindingLatch.countDown()
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        synchronized(lock) {
                            service = null
                            // Keep the single binding; Android reconnects this ServiceConnection.
                            bindingLatch = CountDownLatch(1)
                        }
                    }
                }
                serviceConnection = connection
                val bound = try {
                    context.bindService(
                        Intent().setComponent(ComponentName(PACKAGE, SERVICE)),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                } catch (error: SecurityException) {
                    bindingFailure = IOException("ECarX Bluetooth service binding was denied", error)
                    false
                }
                if (!bound) {
                    bindingStarted = false
                    serviceConnection = null
                    bindingFailure = bindingFailure ?: IOException("ECarX Bluetooth service is unavailable")
                    bindingLatch.countDown()
                }
            }
            latch = bindingLatch
        }
        if (!latch.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw IOException("Timed out binding ECarX Bluetooth service")
        }
        return synchronized(lock) {
            service ?: throw (bindingFailure ?: IOException("ECarX Bluetooth service disconnected"))
        }
    }
}

private fun canonicalOrNull(address: String?): String? = try {
    address?.let(EcarxE02BluetoothProtocol::canonicalAddress)
        ?.takeUnless { it.equals("00:00:00:00:00:00", ignoreCase = true) }
} catch (_: IllegalArgumentException) {
    null
}
