package com.nevus.mediabridge.vpn

import android.content.Context
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
 * only; it does not provide or operate a server. Every profile is a standard WireGuard `.conf`
 * the user supplies for a server *they* control — up to [MAX_PROFILES] saved slots so someone
 * with several of their own servers (or a provider account that hands out per-location configs)
 * can switch between them, not 10 servers this app hands out itself.
 *
 * Configs (private keys included) live only in app-private internal storage — never
 * external/shared storage, never logged.
 */
class WireGuardManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private val profilesDir = File(appContext.filesDir, "vpn/profiles").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("nevus_vpn", Context.MODE_PRIVATE)

    private var config: Config? = null

    var activeProfileName: String? = prefs.getString(KEY_ACTIVE_PROFILE, null)
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
        activeProfileName?.let { loadIntoMemory(it) }
    }

    /** Saved profile names, alphabetical. */
    fun listProfiles(): List<String> =
        profilesDir.listFiles { f -> f.extension == "conf" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun hasActiveConfig(): Boolean = config != null

    /**
     * Validates and saves a `.conf` under [name]. Fails if [name] is blank, or the profile cap
     * is reached and [name] isn't already an existing profile (overwriting one is always fine).
     */
    fun importProfile(name: String, input: InputStream): Boolean {
        val safeName = sanitizeName(name)
        if (safeName.isBlank()) return false
        val existing = listProfiles()
        if (existing.size >= MAX_PROFILES && safeName !in existing) return false

        val bytes = input.use { it.readBytes() }
        return runCatching {
            Config.parse(bytes.inputStream()) // validate before writing
            File(profilesDir, "$safeName.conf").writeBytes(bytes)
        }.onFailure { NevusLog.w(TAG, "Failed to import WireGuard profile '$safeName'", it) }.isSuccess
    }

    fun deleteProfile(name: String) {
        File(profilesDir, "$name.conf").delete()
        if (activeProfileName == name) {
            activeProfileName = null
            config = null
            prefs.edit().remove(KEY_ACTIVE_PROFILE).apply()
        }
    }

    /** Marks [name] the profile [connect] will use. Does not connect by itself. */
    fun selectProfile(name: String): Boolean {
        if (!loadIntoMemory(name)) return false
        activeProfileName = name
        prefs.edit().putString(KEY_ACTIVE_PROFILE, name).apply()
        return true
    }

    private fun loadIntoMemory(name: String): Boolean {
        val file = File(profilesDir, "$name.conf")
        if (!file.exists()) return false
        return runCatching { config = file.inputStream().use { Config.parse(it) } }
            .onFailure { NevusLog.w(TAG, "Stored profile '$name' unreadable", it) }
            .isSuccess
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

    private fun sanitizeName(name: String): String =
        name.trim().take(40).replace(Regex("[^A-Za-z0-9 _-]"), "_")

    private companion object {
        const val TAG = "WireGuardManager"
        const val TUNNEL_NAME = "nevus0"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val MAX_PROFILES = 10
    }
}
