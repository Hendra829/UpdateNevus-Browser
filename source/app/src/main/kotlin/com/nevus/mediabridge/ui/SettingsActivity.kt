package com.nevus.mediabridge.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
    private var pendingVpnConnect = false

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val manager = (application as NevusApplication).wireGuardManager
        if (result.resultCode == RESULT_OK && pendingVpnConnect) {
            lifecycleScope.launch { manager.connect() }
        } else if (pendingVpnConnect) {
            Toast.makeText(this, R.string.vpn_dns_permission_denied, Toast.LENGTH_SHORT).show()
            findViewById<RadioGroup>(R.id.networkModeGroup).check(R.id.networkModeOff)
        }
        pendingVpnConnect = false
    }

    private val openConfigLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val manager = (application as NevusApplication).wireGuardManager
        val ok = runCatching { contentResolver.openInputStream(uri)?.use { manager.importConfig(it) } == true }.getOrDefault(false)
        if (ok) {
            updateConfigStatus(manager)
        } else {
            Toast.makeText(this, R.string.vpn_dns_config_import_failed, Toast.LENGTH_LONG).show()
        }
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
        val importBtn = findViewById<View>(R.id.importConfigBtn)

        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, DohProvider.entries.map { it.label })
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
                }
                R.id.networkModeDns -> {
                    lifecycleScope.launch { manager.disconnect() }
                    val provider = DohProvider.entries.getOrElse(providerSpinner.selectedItemPosition) { DohProvider.CLOUDFLARE }
                    NevusVpnService.start(this, provider)
                }
                R.id.networkModeVpn -> {
                    NevusVpnService.stop(this)
                    startVpnMode(manager)
                }
            }
        }

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (modeGroup.checkedRadioButtonId == R.id.networkModeDns) {
                    NevusVpnService.start(this@SettingsActivity, DohProvider.entries[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        importBtn.setOnClickListener { openConfigLauncher.launch(arrayOf("*/*")) }
    }

    private fun updateModeSections(checkedId: Int, dnsSection: View, vpnSection: View) {
        dnsSection.visibility = if (checkedId == R.id.networkModeDns) View.VISIBLE else View.GONE
        vpnSection.visibility = if (checkedId == R.id.networkModeVpn) View.VISIBLE else View.GONE
    }

    private fun startVpnMode(manager: WireGuardManager) {
        if (!manager.hasConfig()) {
            Toast.makeText(this, R.string.vpn_dns_config_none, Toast.LENGTH_SHORT).show()
            return
        }
        val consent = manager.prepareIntent()
        if (consent != null) {
            pendingVpnConnect = true
            vpnPermissionLauncher.launch(consent)
        } else {
            lifecycleScope.launch { manager.connect() }
        }
    }

    private fun updateConfigStatus(manager: WireGuardManager) {
        findViewById<TextView>(R.id.vpnConfigStatus).text =
            getString(if (manager.hasConfig()) R.string.vpn_dns_config_loaded else R.string.vpn_dns_config_none)
    }

    private fun observeNetworkStatus() {
        val manager = (application as NevusApplication).wireGuardManager
        val statusView = findViewById<TextView>(R.id.networkModeStatus)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(NevusVpnService.connected, manager.state) { dnsOn, vpnState -> dnsOn to vpnState }
                    .collect { (dnsOn, vpnState) ->
                        statusView.text = when {
                            dnsOn -> getString(R.string.vpn_dns_status_dns_on, currentDohProviderLabel())
                            vpnState == Tunnel.State.UP -> getString(R.string.vpn_dns_status_vpn_on)
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
