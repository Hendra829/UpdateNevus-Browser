package com.nevus.mediabridge.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.nevus.mediabridge.util.NevusLog
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Wraps the official `com.wireguard.android:tunnel` client for "VPN" mode — this app is a client
 * only; it does not provide or operate a server. [importConfig] takes a standard WireGuard
 * `.conf` the user supplies for their own server. The parsed config (private key included) is
 * kept only in app-private internal storage — never external/shared storage, never logged.
 */
class WireGuardManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private val configFile = File(appContext.filesDir, "vpn/wireguard.conf")

    private var config: Config? = null
        private set

    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = newState
        }
    }

    init {
        if (configFile.exists()) {
            runCatching { configFile.inputStream().use { Config.parse(it) } }
                .onSuccess { config = it }
                .onFailure { NevusLog.w(TAG, "Stored WireGuard config unreadable, ignoring", it) }
        }
    }

    fun hasConfig(): Boolean = config != null

    /** The system VPN-permission consent intent, or null if already granted — must be launched from an Activity. */
    fun prepareIntent(): Intent? = VpnService.prepare(appContext)

    /** Parses and persists a user-supplied `.conf`. Returns true on success. */
    fun importConfig(input: InputStream): Boolean {
        val bytes = input.use { it.readBytes() }
        return runCatching {
            val parsed = Config.parse(bytes.inputStream())
            configFile.parentFile?.mkdirs()
            configFile.writeBytes(bytes)
            config = parsed
        }.onFailure { NevusLog.w(TAG, "Failed to import WireGuard config", it) }.isSuccess
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext false
        runCatching { backend.setState(tunnel, Tunnel.State.UP, cfg) }
            .onFailure { NevusLog.w(TAG, "Failed to bring WireGuard tunnel up", it) }
            .isSuccess
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, cfg) }
            .onFailure { NevusLog.w(TAG, "Failed to bring WireGuard tunnel down", it) }
    }

    private companion object {
        const val TAG = "WireGuardManager"
        const val TUNNEL_NAME = "nevus0"
    }
}
