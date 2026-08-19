package com.nevus.mediabridge.vpn

/**
 * Hand-rolled IPv4/UDP header parsing and reply construction for [NevusVpnService]'s DNS-only
 * tunnel. IPv4 + UDP only — that's all the tunnel routes (see the `addRoute` call in
 * [NevusVpnService]), so anything else arriving on the TUN fd is out of scope and dropped.
 */
data class IpV4UdpPacket(
    val sourceAddr: ByteArray,
    val destAddr: ByteArray,
    val sourcePort: Int,
    val destPort: Int,
    val payload: ByteArray,
) {
    /**
     * Builds the reply packet for this query: source/dest swapped, [dnsResponse] as the UDP
     * body. UDP checksum is left at 0 — legal for IPv4 per RFC 768, and skips a second manual
     * checksum (pseudo-header) computation for a tunnel-local, already-TLS-verified payload.
     */
    fun buildReply(dnsResponse: ByteArray): ByteArray {
        val udpLength = 8 + dnsResponse.size
        val totalLength = 20 + udpLength
        val out = ByteArray(totalLength)

        out[0] = 0x45 // version 4, IHL 5 (20-byte header, no options)
        out[1] = 0
        out[2] = ((totalLength shr 8) and 0xFF).toByte()
        out[3] = (totalLength and 0xFF).toByte()
        out[4] = 0; out[5] = 0
        out[6] = 0x40; out[7] = 0 // flags: don't fragment
        out[8] = 64 // TTL
        out[9] = 17 // protocol: UDP
        System.arraycopy(destAddr, 0, out, 12, 4)
        System.arraycopy(sourceAddr, 0, out, 16, 4)

        val ipChecksum = checksum16(out, 0, 20)
        out[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        val udpStart = 20
        out[udpStart] = ((destPort shr 8) and 0xFF).toByte()
        out[udpStart + 1] = (destPort and 0xFF).toByte()
        out[udpStart + 2] = ((sourcePort shr 8) and 0xFF).toByte()
        out[udpStart + 3] = (sourcePort and 0xFF).toByte()
        out[udpStart + 4] = ((udpLength shr 8) and 0xFF).toByte()
        out[udpStart + 5] = (udpLength and 0xFF).toByte()
        out[udpStart + 6] = 0
        out[udpStart + 7] = 0

        System.arraycopy(dnsResponse, 0, out, udpStart + 8, dnsResponse.size)
        return out
    }

    companion object {
        fun parse(packet: ByteArray, length: Int): IpV4UdpPacket? {
            if (length < 20) return null
            val versionAndIhl = packet[0].toInt() and 0xFF
            if (versionAndIhl shr 4 != 4) return null // IPv6 not handled — see class kdoc
            val ihl = (versionAndIhl and 0x0F) * 4
            if (ihl < 20 || length < ihl + 8) return null
            if (packet[9].toInt() and 0xFF != 17) return null // UDP only

            val srcAddr = packet.copyOfRange(12, 16)
            val dstAddr = packet.copyOfRange(16, 20)
            val udpStart = ihl
            val srcPort = readUShort(packet, udpStart)
            val dstPort = readUShort(packet, udpStart + 2)
            val udpLength = readUShort(packet, udpStart + 4)
            val payloadStart = udpStart + 8
            val payloadLen = (udpLength - 8).coerceAtLeast(0)
            if (payloadStart + payloadLen > length) return null

            return IpV4UdpPacket(srcAddr, dstAddr, srcPort, dstPort, packet.copyOfRange(payloadStart, payloadStart + payloadLen))
        }

        private fun readUShort(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun checksum16(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var i = offset
            while (i < offset + length - 1) {
                sum += readUShort(data, i)
                i += 2
            }
            if (length % 2 != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }
    }
}
