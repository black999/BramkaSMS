package pl.bramkasms.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

data class CidrBlock(private val network: ByteArray, private val prefixBits: Int) {
    fun contains(address: InetAddress): Boolean {
        val candidate = address.address
        if (candidate.size != network.size) return false
        val wholeBytes = prefixBits / 8
        val remainingBits = prefixBits % 8
        for (i in 0 until wholeBytes) if (candidate[i] != network[i]) return false
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (candidate[wholeBytes].toInt() and mask) == (network[wholeBytes].toInt() and mask)
    }

    companion object {
        fun parse(value: String): CidrBlock? = runCatching {
            val parts = value.trim().split('/')
            if (parts.size != 2) return null
            val address = InetAddress.getByName(parts[0])
            val prefix = parts[1].toInt()
            require(prefix in 0..address.address.size * 8)
            CidrBlock(address.address, prefix)
        }.getOrNull()
    }
}

object NetworkAccess {
    fun isAllowed(remoteAddress: String, configuredCidrs: String): Boolean {
        if (configuredCidrs.isBlank()) return true
        val address = runCatching { InetAddress.getByName(remoteAddress.substringBefore('%')) }.getOrNull() ?: return false
        return configuredCidrs.split(',', ';').mapNotNull(CidrBlock::parse).any { it.contains(address) }
    }

    @Suppress("DEPRECATION")
    fun wifiIpv4Address(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.asSequence()
            .filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
