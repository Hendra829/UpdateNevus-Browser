package com.nevus.mediabridge.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.nevus.mediabridge.NevusApplication
import com.nevus.mediabridge.R
import com.nevus.mediabridge.crypto.CSPRNGHealthMonitor
import com.nevus.mediabridge.crypto.CSPRNGProvider
import com.nevus.mediabridge.util.NevusSettings

/**
 * Settings screen — four sections switched by a [TabLayout] (not swipeable; no ViewPager2
 * dependency needed for four static sections): Umum, Koneksi Cepat, Audit CSPRNG, Riwayat &
 * Recovery. The CSPRNG section polls [CSPRNGHealthMonitor.snapshot] on a short interval while
 * visible — it's a live dashboard, not a one-shot read.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: NevusSettings
    private lateinit var sections: List<View>

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
        )

        findViewById<View>(R.id.settingsBackBtn).setOnClickListener { finish() }
        wireTabs()
        wireGeneralSection()
        wireFastConnectionSection()
        wireCsprngSection()
        renderRecoverySection()
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

    private companion object {
        const val CSPRNG_REFRESH_MS = 2000L
    }
}
