package com.shilapi.xcertplay.transport;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Wire format used by the ECarX E02 gocsdk Bluetooth bridge. */
public final class EcarxE02BluetoothProtocol {
    public static final String IAP2_UUID = "00000000-deca-fade-deca-deafdecacafe";

    private static final Pattern BLUETOOTH_ADDRESS =
            Pattern.compile("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}");

    private EcarxE02BluetoothProtocol() {}

    /** gocsdk expects AT#SP followed by the compact address and the dashed iAP2 UUID. */
    public static String connectCommand(String address) {
        return "AT#SP" + compactAddress(address) + IAP2_UUID + "\r\n";
    }

    /** Release the RFCOMM channel when the bootstrap link closes. */
    public static String disconnectCommand(String address) {
        return "AT#SH" + compactAddress(address) + "\r\n";
    }

    /** goc_spp reads a 12-byte ASCII address before carrying raw RFCOMM bytes. */
    public static byte[] socketHandshake(String address) {
        return compactAddress(address).getBytes(StandardCharsets.US_ASCII);
    }

    /** Validate before a MAC enters a vendor command or socket handshake. */
    public static String compactAddress(String address) {
        if (address == null || !BLUETOOTH_ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("Invalid Bluetooth address");
        }
        return address.replace(":", "").toUpperCase(Locale.ROOT);
    }

    public static String canonicalAddress(String address) {
        String compact = compactAddress(address);
        return compact.substring(0, 2) + ":" + compact.substring(2, 4) + ":" + compact.substring(4, 6) + ":" +
                compact.substring(6, 8) + ":" + compact.substring(8, 10) + ":" + compact.substring(10, 12);
    }
}
