package com.nevus.mediabridge.ui

import android.app.AlertDialog
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.nevus.mediabridge.NevusApplication
import com.nevus.mediabridge.R
import com.nevus.mediabridge.crypto.CSPRNGHealthMonitor
import com.nevus.mediabridge.crypto.CSPRNGProvider
import com.nevus.mediabridge.util.NetworkSpeedTest
import com.nevus.mediabridge.util.NevusSettings
import com.nevus.mediabridge.vpn.DohProvider
import com.nevus.mediabridge.vpn.NevusVpnService
import com.nevus.mediabridge.vpn.WireGuardManager
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Settings screen — four sections switched by a [TabLayout] (not swipeable; no ViewPager2
 * dependency needed for four static sections): Umum, Koneksi Cepat, Audit CSPRNG, Riwayat &
 * Recovery. The CSPRNG section polls [CSPRNGHealthMonitor.snapshot] on a short interval while
 * visible — it's a live dashboard, not a one-shot read.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: NevusSettings
    private lateinit var sections: List<View>

    /** Set right before launching [vpnPermissionLauncher]; runs once the system grants consent. Shared by both DNS Aman and VPN modes — both are backed by a VpnService and need the same one-time system permission. */
    private var pendingAfterVpnConsent: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingAfterVpnConsent
        pendingAfterVpnConsent = null
        if (result.resultCode == RESULT_OK) {
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.vpn_dns_permission_denied, Toast.LENGTH_SHORT).show()
            findViewById<RadioGroup>(R.id.networkModeGroup).check(R.id.networkModeOff)
        }
    }

    /** [VpnService.prepare] is shared system state across every VpnService this app declares — one consent covers both DNS Aman and VPN modes. */
    private fun requestVpnPermissionThen(action: () -> Unit) {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            pendingAfterVpnConsent = action
            vpnPermissionLauncher.launch(consent)
        } else {
            action()
        }
    }

    private val openConfigLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) promptImportName(uri)
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshCsprng = object : Runnable {
        override fun run() {
            renderCsprngSnapshot()
            refreshHandler.postDelayed(this, CSPRNG_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = NevusSettings(this)

        sections = listOf(
            findViewById(R.id.sectionGeneral),
            findViewById(R.id.sectionFastConnection),
            findViewById(R.id.sectionCsprng),
            findViewById(R.id.sectionRecovery),
            findViewById(R.id.sectionVpnDns),
        )

        findViewById<View>(R.id.settingsBackBtn).setOnClickListener { finish() }
        wireTabs()
        wireGeneralSection()
        wireFastConnectionSection()
        wireCsprngSection()
        renderRecoverySection()
        wireVpnDnsSection()
        observeNetworkStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshCsprng)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshCsprng)
        super.onPause()
    }

    private fun wireTabs() {
        val tabs = findViewById<TabLayout>(R.id.settingsTabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSection(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showSection(0)
    }

    private fun showSection(index: Int) {
        sections.forEachIndexed { i, view -> view.visibility = if (i == index) View.VISIBLE else View.GONE }
    }

    private fun wireGeneralSection() {
        findViewById<MaterialSwitch>(R.id.switchEnhanceDefault).apply {
            isChecked = settings.enhanceByDefault
            setOnCheckedChangeListener { _, checked -> settings.enhanceByDefault = checked }
        }

        val group = findViewById<RadioGroup>(R.id.targetHeightGroup)
        val checkedId = when (settings.defaultTargetHeight) {
            NevusSettings.HEIGHT_720P -> R.id.height720
            NevusSettings.HEIGHT_1080P -> R.id.height1080
            NevusSettings.HEIGHT_1440P -> R.id.height1440
            else -> R.id.heightAuto
        }
        group.check(checkedId)
        group.setOnCheckedChangeListener { _, id ->
            settings.defaultTargetHeight = when (id) {
                R.id.height720 -> NevusSettings.HEIGHT_720P
                R.id.height1080 -> NevusSettings.HEIGHT_1080P
                R.id.height1440 -> NevusSettings.HEIGHT_1440P
                else -> null
            }
        }
    }

    private fun wireFastConnectionSection() {
        findViewById<MaterialSwitch>(R.id.switchFastConnection).apply {
            isChecked = settings.fastConnectionMode
            setOnCheckedChangeListener { _, checked -> settings.fastConnectionMode = checked }
        }

        val resultView = findViewById<TextView>(R.id.speedTestResult)
        val progress = findViewById<View>(R.id.speedTestProgress)
        val runBtn = findViewById<View>(R.id.runSpeedTestBtn)

        settings.lastMeasuredDownloadMbps?.let { resultView.text = getString(R.string.settings_speedtest_result, "—", "%.1f".format(it), "—") }

        runBtn.setOnClickListener {
            runBtn.isEnabled = false
            progress.visibility = View.VISIBLE
            resultView.text = ""
            lifecycleScope.launch {
                val result = NetworkSpeedTest.run()
                progress.visibility = View.GONE
                runBtn.isEnabled = true
                if (result.latencyMs == null && result.downloadMbps == null && result.uploadMbps == null) {
                    resultView.text = getString(R.string.settings_speedtest_failed)
                    return@launch
                }
                result.downloadMbps?.let { settings.lastMeasuredDownloadMbps = it.toFloat() }
                val unavailable = getString(R.string.settings_speedtest_unavailable)
                resultView.text = getString(
                    R.string.settings_speedtest_result,
                    result.latencyMs?.toString() ?: unavailable,
                    result.downloadMbps?.let { "%.1f".format(it) } ?: unavailable,
                    result.uploadMbps?.let { "%.1f".format(it) } ?: unavailable,
                )
            }
        }
    }

    private fun wireCsprngSection() {
        findViewById<View>(R.id.csprngReseedNowBtn).setOnClickListener {
            CSPRNGProvider.reseedSelf()
            renderCsprngSnapshot()
            Toast.makeText(this, R.string.settings_csprng_reseed_done, Toast.LENGTH_SHORT).show()
        }
        val bitmapView = findViewById<RandomBitmapView>(R.id.randomBitmapView)
        bitmapView.refresh()
        findViewById<View>(R.id.refreshBitmapBtn).setOnClickListener { bitmapView.refresh() }
    }

    private fun renderCsprngSnapshot() {
        val snapshot = (application as NevusApplication).csprngHealthMonitor.snapshot()
        findViewById<TextView>(R.id.csprngBytesServed).text =
            getString(R.string.settings_csprng_bytes_served, snapshot.provider.bytesServed)
        findViewById<TextView>(R.id.csprngReseedGeneration).text =
            getString(R.string.settings_csprng_reseed_generation, snapshot.provider.reseedGeneration)
        findViewById<TextView>(R.id.csprngAlgorithm).text =
            getString(R.string.settings_csprng_algorithm, snapshot.provider.algorithm)
        findViewById<TextView>(R.id.csprngEvaluations).text =
            getString(R.string.settings_csprng_evaluations, snapshot.validator.evaluations)
        findViewById<TextView>(R.id.csprngAlarms).text =
            getString(R.string.settings_csprng_alarms, snapshot.validator.alarms)

        val lastReport = snapshot.validator.lastReport
        findViewById<TextView>(R.id.csprngLastReport).text = if (lastReport == null) {
            getString(R.string.settings_csprng_no_report)
        } else {
            getString(
                R.string.settings_csprng_last_report,
                lastReport.monobitP,
                lastReport.runsP,
                lastReport.chiSquareP,
                lastReport.blockFrequencyP,
            )
        }

        // NIST default significance level (see LiveStatisticalValidator's own kdoc) — the
        // validator doesn't expose its configured threshold, and this app never changes it from
        // the default, so it's safe to mirror here for the trend line's reference marker.
        findViewById<PValueTrendView>(R.id.pValueTrendView).setData(snapshot.validator.recentReports, alarmThreshold = 0.01)
    }

    private fun renderRecoverySection() {
        val report = (application as NevusApplication).lastRecoveryReport
        findViewById<TextView>(R.id.recoverySummary).text =
            getString(R.string.settings_recovery_summary, report.summary())
        findViewById<TextView>(R.id.recoveryInFlight).text =
            getString(R.string.settings_recovery_in_flight, report.inFlight.size)
    }

    private fun wireVpnDnsSection() {
        val manager = (application as NevusApplication).wireGuardManager
        val modeGroup = findViewById<RadioGroup>(R.id.networkModeGroup)
        val dnsSection = findViewById<View>(R.id.dnsProviderSection)
        val vpnSection = findViewById<View>(R.id.vpnConfigSection)
        val providerSpinner = findViewById<Spinner>(R.id.dohProviderSpinner)
        val profileSpinner = findViewById<Spinner>(R.id.vpnProfileSpinner)
        val importBtn = findViewById<View>(R.id.importConfigBtn)
        val deleteBtn = findViewById<View>(R.id.deleteProfileBtn)
        val connectBtn = findViewById<View>(R.id.connectVpnBtn)

        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, DohProvider.entries.map { it.label })
        refreshProfileSpinner(manager, profileSpinner)
        updateConfigStatus(manager)

        // Reflect real current state, not a stale UI default — this Activity may be recreated
        // while a mode from a previous session is still running.
        modeGroup.check(
            when {
                NevusVpnService.connected.value -> R.id.networkModeDns
                manager.state.value != Tunnel.State.DOWN -> R.id.networkModeVpn
                else -> R.id.networkModeOff
            }
        )
        updateModeSections(modeGroup.checkedRadioButtonId, dnsSection, vpnSection)

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateModeSections(checkedId, dnsSection, vpnSection)
            when (checkedId) {
                R.id.networkModeOff -> {
                    NevusVpnService.stop(this)
                    lifecycleScope.launch { manager.disconnect() }
                    Toast.makeText(this, R.string.vpn_dns_turned_off, Toast.LENGTH_SHORT).show()
                }
                R.id.networkModeDns -> {
                    lifecycleScope.launch { manager.disconnect() }
                    val provider = DohProvider.entries.getOrElse(providerSpinner.selectedItemPosition) { DohProvider.CLOUDFLARE }
                    requestVpnPermissionThen { NevusVpnService.start(this, provider) }
                }
                R.id.networkModeVpn -> {
                    // Just reveal the section — connecting needs a profile picked first,
                    // via the explicit "Aktifkan VPN" button below.
                    NevusVpnService.stop(this)
                }
            }
        }

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (modeGroup.checkedRadioButtonId == R.id.networkModeDns) {
                    requestVpnPermissionThen { NevusVpnService.start(this@SettingsActivity, DohProvider.entries[position]) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = manager.listProfiles().getOrNull(position) ?: return
                if (name != manager.activeProfileName) manager.selectProfile(name)
                updateConfigStatus(manager)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        importBtn.setOnClickListener { openConfigLauncher.launch(arrayOf("*/*")) }

        deleteBtn.setOnClickListener {
            val name = manager.activeProfileName
            if (name == null) {
                Toast.makeText(this, R.string.vpn_dns_no_profiles, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch { manager.disconnect() }
            manager.deleteProfile(name)
            refreshProfileSpinner(manager, profileSpinner)
            updateConfigStatus(manager)
            Toast.makeText(this, R.string.vpn_dns_profile_deleted, Toast.LENGTH_SHORT).show()
        }

        connectBtn.setOnClickListener { startVpnMode(manager) }

        findViewById<View>(R.id.turnOffNetworkModeBtn).setOnClickListener {
            // Stop directly rather than relying solely on the RadioGroup listener firing — if the
            // radio's checked state and the real connection state ever drift apart (e.g. after
            // Activity recreation), re-checking an already-checked button is a no-op in Android
            // and would silently do nothing. This button always works regardless.
            NevusVpnService.stop(this)
            lifecycleScope.launch { manager.disconnect() }
            modeGroup.check(R.id.networkModeOff)
            updateModeSections(R.id.networkModeOff, dnsSection, vpnSection)
            Toast.makeText(this, R.string.vpn_dns_turned_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshProfileSpinner(manager: WireGuardManager, spinner: Spinner) {
        val profiles = manager.listProfiles()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.ifEmpty { listOf(getString(R.string.vpn_dns_no_profiles)) })
        val activeIndex = profiles.indexOf(manager.activeProfileName)
        if (activeIndex >= 0) spinner.setSelection(activeIndex)
    }

    /** Prompts for a profile name (pre-filled from the picked file's display name), then imports. */
    private fun promptImportName(uri: Uri) {
        val manager = (application as NevusApplication).wireGuardManager
        val input = EditText(this).apply {
            hint = getString(R.string.vpn_dns_profile_name_hint)
            setText(guessProfileName(uri))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vpn_dns_profile_name_title)
            .setView(input)
            .setPositiveButton(R.string.vpn_dns_import_config) { _, _ ->
                val name = input.text.toString()
                val ok = runCatching { contentResolver.openInputStream(uri)?.use { manager.importProfile(name, it) } == true }.getOrDefault(false)
                if (ok) {
                    manager.selectProfile(manager.listProfiles().firstOrNull { it.equals(name.trim(), ignoreCase = true) } ?: return@setPositiveButton)
                    refreshProfileSpinner(manager, findViewById(R.id.vpnProfileSpinner))
                    updateConfigStatus(manager)
                } else {
                    Toast.makeText(this, R.string.vpn_dns_config_import_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.download_options_cancel, null)
            .show()
    }

    private fun guessProfileName(uri: Uri): String {
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
        return (displayName ?: "Profil").substringBeforeLast('.')
    }

    private fun updateModeSections(checkedId: Int, dnsSection: View, vpnSection: View) {
        dnsSection.visibility = if (checkedId == R.id.networkModeDns) View.VISIBLE else View.GONE
        vpnSection.visibility = if (checkedId == R.id.networkModeVpn) View.VISIBLE else View.GONE
    }

    private fun startVpnMode(manager: WireGuardManager) {
        if (!manager.hasActiveConfig()) {
            Toast.makeText(this, R.string.vpn_dns_config_none, Toast.LENGTH_SHORT).show()
            return
        }
        requestVpnPermissionThen { lifecycleScope.launch { manager.connect() } }
    }

    private fun updateConfigStatus(manager: WireGuardManager) {
        findViewById<TextView>(R.id.vpnConfigStatus).text = manager.activeProfileName?.let {
            getString(R.string.vpn_dns_config_loaded, it)
        } ?: getString(R.string.vpn_dns_config_none)
    }

    private fun observeNetworkStatus() {
        val manager = (application as NevusApplication).wireGuardManager
        val statusView = findViewById<TextView>(R.id.networkModeStatus)
        val turnOffBtn = findViewById<View>(R.id.turnOffNetworkModeBtn)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(NevusVpnService.connected, manager.state) { dnsOn, vpnState -> dnsOn to vpnState }
                    .collect { (dnsOn, vpnState) ->
                        val active = dnsOn || vpnState != Tunnel.State.DOWN
                        turnOffBtn.visibility = if (active) View.VISIBLE else View.GONE
                        statusView.text = when {
                            dnsOn -> getString(R.string.vpn_dns_status_dns_on, currentDohProviderLabel())
                            vpnState == Tunnel.State.UP -> getString(R.string.vpn_dns_status_vpn_on, manager.activeProfileName ?: "")
                            vpnState == Tunnel.State.TOGGLE -> getString(R.string.vpn_dns_status_vpn_connecting)
                            else -> getString(R.string.vpn_dns_status_off)
                        }
                    }
            }
        }
    }

    private fun currentDohProviderLabel(): String {
        val spinner = findViewById<Spinner>(R.id.dohProviderSpinner)
        return DohProvider.entries.getOrNull(spinner.selectedItemPosition)?.label ?: DohProvider.CLOUDFLARE.label
    }

    private companion object {
        const val CSPRNG_REFRESH_MS = 2000L
    }
}
