package com.mamy.dataguard

import java.net.InetAddress

/**
 * Extraction très légère des informations utiles d'un paquet IPv4 (TCP/UDP) :
 * protocole, adresses et ports source/destination. Suffisant pour retrouver
 * l'UID propriétaire de la connexion via ConnectivityManager.getConnectionOwnerUid.
 * Ne gère pas IPv6 ni les options IP (hors périmètre de ce squelette de projet).
 */
object IpPacketParser {

    data class PacketInfo(
        val protocol: Int,
        val sourceAddress: InetAddress,
        val sourcePort: Int,
        val destAddress: InetAddress,
        val destPort: Int
    )

    private const val PROTOCOL_TCP = 6
    private const val PROTOCOL_UDP = 17

    fun parse(buffer: ByteArray, length: Int): PacketInfo? {
        if (length < 20) return null

        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // IPv6 non géré dans ce squelette

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < 20 || length < ihl + 4) return null

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != PROTOCOL_TCP && protocol != PROTOCOL_UDP) return null

        val srcAddrBytes = buffer.copyOfRange(12, 16)
        val dstAddrBytes = buffer.copyOfRange(16, 20)

        if (length < ihl + 4) return null
        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

        return try {
            PacketInfo(
                protocol = protocol,
                sourceAddress = InetAddress.getByAddress(srcAddrBytes),
                sourcePort = srcPort,
                destAddress = InetAddress.getByAddress(dstAddrBytes),
                destPort = dstPort
            )
        } catch (e: Exception) {
            null
        }
    }
}
