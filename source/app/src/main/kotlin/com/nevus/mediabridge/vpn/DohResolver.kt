package com.nevus.mediabridge.vpn

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal DNS-over-HTTPS (RFC 8484) client: POSTs a raw DNS query message and returns the raw
 * DNS response message. Deliberately hand-rolled over a plain [Socket] + manual HTTP/1.1 framing
 * rather than [javax.net.HttpsURLConnection] — the caller runs inside a [android.net.VpnService]
 * and needs [protect] called on the underlying socket *before* the TLS handshake, which
 * `HttpsURLConnection` gives no way to do (it never exposes its raw socket).
 */
class DohResolver(private val protect: (Socket) -> Boolean) {

    fun resolve(provider: DohProvider, query: ByteArray): ByteArray? {
        val url = URL(provider.endpoint)
        val host = url.host
        val port = if (url.port != -1) url.port else 443
        val path = url.path.ifBlank { "/" } + (url.query?.let { "?$it" } ?: "")

        val plain = Socket()
        protect(plain)
        return try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = READ_TIMEOUT_MS

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
            ssl.use {
                it.startHandshake()
                sendRequest(it, host, path, query)
                readResponseBody(it)
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { if (!plain.isClosed) plain.close() }
        }
    }

    private fun sendRequest(ssl: SSLSocket, host: String, path: String, query: ByteArray) {
        val header = buildString {
            append("POST ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Content-Type: application/dns-message\r\n")
            append("Accept: application/dns-message\r\n")
            append("Content-Length: ").append(query.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        ssl.outputStream.apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(query)
            flush()
        }
    }

    private fun readResponseBody(ssl: SSLSocket): ByteArray? {
        val input = BufferedInputStream(ssl.inputStream)
        val statusLine = readLine(input) ?: return null
        if (!statusLine.contains(" 200 ")) return null

        var contentLength = -1
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            if (line.substring(0, sep).trim().equals("content-length", ignoreCase = true)) {
                contentLength = line.substring(sep + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength <= 0) return null

        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return if (read == contentLength) body else null
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 5000
    }
}
