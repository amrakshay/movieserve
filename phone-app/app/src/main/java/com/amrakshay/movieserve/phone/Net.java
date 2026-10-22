package com.amrakshay.movieserve.phone;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Finding the address to read out and type into the TV. */
final class Net {

    private Net() {
    }

    /**
     * This phone's address on the local network, or null when it is not on one.
     *
     * <p>Walks the interfaces rather than asking WifiManager, so a phone tethering or on Ethernet
     * over USB-C still reports something usable, and so no location permission is needed -- reading
     * Wi-Fi details requires one on newer Android, and this does not.
     */
    static String lanAddress() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** "http://192.168.0.5:18080", or null when there is no network to serve on. */
    static String serverUrl() {
        String address = lanAddress();
        return address == null ? null : "http://" + address + ":" + Prefs.PORT;
    }
}
