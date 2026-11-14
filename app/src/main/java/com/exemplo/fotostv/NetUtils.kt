package com.exemplo.fotostv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /**
     * IPv4 da rede local (Wi-Fi ou Ethernet). Enumeramos as interfaces em vez
     * de usar WifiManager para funcionar tambem com o Fire TV ligado por cabo.
     */
    fun localIpv4(): String? {
        val fallback = mutableListOf<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    // Preferimos a interface fisica (wlan/eth) a tunel ou VPN
                    if (name.startsWith("wlan") || name.startsWith("eth")) return ip
                    fallback += ip
                }
            }
        } catch (e: Exception) {
            return null
        }
        return fallback.firstOrNull()
    }
}
