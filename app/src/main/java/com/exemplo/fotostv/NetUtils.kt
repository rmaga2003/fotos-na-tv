package com.exemplo.fotostv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** Um par (nome da interface, IPv4), ex.: ("wlan0", "192.168.0.44"). */
    data class Address(val iface: String, val ip: String)

    /**
     * Todos os IPv4 nao-loopback do aparelho. Mostrar a lista inteira na tela
     * de diagnostico ajuda quando o aparelho tem mais de uma interface e a
     * escolhida nao e a que o celular enxerga.
     */
    fun allIpv4(): List<Address> {
        val out = mutableListOf<Address>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    addr.hostAddress?.let { out += Address(iface.name, it) }
                }
            }
        } catch (e: Exception) {
            // devolve o que deu para juntar; a tela de diagnostico mostra vazio
        }
        return out
    }

    /**
     * IPv4 da rede local (Wi-Fi ou Ethernet). Enumeramos as interfaces em vez
     * de usar WifiManager para funcionar tambem com o Fire TV ligado por cabo.
     */
    fun localIpv4(): String? {
        val all = allIpv4()
        // Preferimos a interface fisica (wlan/eth) a tunel ou VPN
        return all.firstOrNull {
            val n = it.iface.lowercase()
            n.startsWith("wlan") || n.startsWith("eth")
        }?.ip ?: all.firstOrNull()?.ip
    }
}
