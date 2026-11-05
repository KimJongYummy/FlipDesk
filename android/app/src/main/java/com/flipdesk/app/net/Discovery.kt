package com.flipdesk.app.net

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Finds FlipDesk servers on the LAN with a UDP broadcast probe. */
object Discovery {
    suspend fun find(timeoutMs: Int = 1500, network: Network? = null): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredServer>()
        runCatching {
            DatagramSocket().use { s ->
                network?.bindSocket(s)
                s.broadcast = true
                s.soTimeout = 300
                val probe = Protocol.DISCOVERY_PROBE.toByteArray()
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextProbe = 0L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextProbe) { // re-probe: UDP can drop packets
                        for (t in targets) runCatching { s.send(DatagramPacket(probe, probe.size, t, Protocol.DISCOVERY_PORT)) }
                        nextProbe = System.currentTimeMillis() + 500
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val host = p.address.hostAddress ?: continue
                    DiscoveredServer.parse(String(p.data, 0, p.length), host)?.let { found[it.serverId] = it }
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.broadcast }
            .distinct()
    }.getOrDefault(emptyList())
}
