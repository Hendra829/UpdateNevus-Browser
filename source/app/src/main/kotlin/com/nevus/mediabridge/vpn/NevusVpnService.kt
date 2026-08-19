package com.nevus.mediabridge.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * "DNS Aman" mode: a DNS-*only* VPN. [android.net.VpnService.Builder] is configured to route
 * just one fake resolver IP ([FAKE_DNS_ADDRESS]) through the TUN interface — every other route
 * stays off it, so ordinary traffic is completely unaffected. Only UDP/port-53 packets arrive on
 * the TUN fd; each is forwarded via [DohResolver] and the response is written back as a reply
 * packet built by [IpV4UdpPacket.buildReply]. Any failure in the loop stops the service cleanly
 * rather than leaving the device stuck without working DNS.
 */
class NevusVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var provider: DohProvider = DohProvider.CLOUDFLARE
    private val resolver by lazy { DohResolver(::protect) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        provider = intent?.getStringExtra(EXTRA_PROVIDER)
            ?.let { runCatching { DohProvider.valueOf(it) }.getOrNull() }
            ?: DohProvider.CLOUDFLARE
        startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        if (vpnInterface != null) return
        val iface = try {
            Builder()
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS_ADDRESS)
                .addRoute(FAKE_DNS_ADDRESS, 32)
                .setSession("Nevus DNS Aman")
                .setBlocking(true)
                .establish()
        } catch (t: Throwable) {
            NevusLog.e(TAG, "Failed to establish DNS tunnel", t)
            null
        }
        if (iface == null) {
            stopSelf()
            return
        }
        vpnInterface = iface
        loopJob = scope.launch { runLoop(iface) }
        _state.value = true
    }

    private suspend fun runLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val writeLock = Any()
        val buf = ByteArray(MAX_PACKET_BYTES)
        try {
            while (currentCoroutineContext().isActive) {
                val length = input.read(buf)
                if (length <= 0) continue
                val packet = IpV4UdpPacket.parse(buf, length) ?: continue
                if (packet.destPort != 53) continue
                val query = packet.payload
                scope.launch {
                    val response = runCatching { resolver.resolve(provider, query) }.getOrNull() ?: return@launch
                    val reply = packet.buildReply(response)
                    synchronized(writeLock) { runCatching { output.write(reply) } }
                }
            }
        } catch (t: Throwable) {
            NevusLog.w(TAG, "DNS tunnel loop stopped", t)
        } finally {
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        vpnInterface?.let { runCatching { it.close() } }
        vpnInterface = null
        _state.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NevusVpnService"
        private const val EXTRA_PROVIDER = "provider"
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val FAKE_DNS_ADDRESS = "10.0.0.1"
        private const val MAX_PACKET_BYTES = 32767

        private val _state = MutableStateFlow(false)

        /** True while the DNS-only tunnel is up. */
        val connected: StateFlow<Boolean> = _state

        fun start(context: Context, provider: DohProvider) {
            val intent = Intent(context, NevusVpnService::class.java).putExtra(EXTRA_PROVIDER, provider.name)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NevusVpnService::class.java))
        }
    }
}
