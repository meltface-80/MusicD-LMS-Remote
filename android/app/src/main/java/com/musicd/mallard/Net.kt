package com.musicd.mallard

import java.net.Inet4Address
import java.net.NetworkInterface

/** This phone's own IPv4 addresses on the local network (Wi-Fi first). */
object Net {
    fun localIpv4(): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in ifs.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                val rank = when {
                    ni.name.startsWith("wlan") -> 0
                    ni.name.startsWith("eth") -> 1
                    ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") -> 9   // mobile data
                    else -> 5
                }
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        out += rank to (a.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** Whether the phone has any network at all (Wi-Fi, Ethernet, mobile data). */
    fun hasNetwork(context: android.content.Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
