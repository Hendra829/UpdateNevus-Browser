package com.nevus.mediabridge.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.nevus.mediabridge.R
import com.nevus.mediabridge.download.DownloadPlan
import com.nevus.mediabridge.download.FloatingBubbleService
import com.nevus.mediabridge.download.ManifestKind
import com.nevus.mediabridge.download.MediaKind
import com.nevus.mediabridge.download.PlaylistParser
import com.nevus.mediabridge.download.QualityVariant
import com.nevus.mediabridge.download.StickerMode
import com.nevus.mediabridge.util.NevusSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Picker shown when the user taps a pending detection in [DownloadManagerActivity] — format,
 * real source quality (if [PlaylistParser] found any), target resize resolution, and the
 * optional enhancement pass.
 */
object DownloadOptionsDialog {

    fun show(context: Context, scope: CoroutineScope, detection: FloatingBubbleService.Detection) {
        val settings = NevusSettings(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_download_options, null, false)

        val formatGroup = view.findViewById<RadioGroup>(R.id.formatGroup)
        val sourceQualityLabel = view.findViewById<TextView>(R.id.sourceQualityLabel)
        val sourceQualitySpinner = view.findViewById<Spinner>(R.id.sourceQualitySpinner)
        val heightGroup = view.findViewById<RadioGroup>(R.id.dialogTargetHeightGroup)
        val enhanceCheckbox = view.findViewById<CheckBox>(R.id.enhanceCheckbox)
        val segmentedCheckbox = view.findViewById<CheckBox>(R.id.segmentedCheckbox)
        val stickerLabel = view.findViewById<TextView>(R.id.stickerLabel)
        val stickerGroup = view.findViewById<RadioGroup>(R.id.stickerGroup)

        if (detection.kind == MediaKind.IMAGE) {
            stickerLabel.visibility = android.view.View.VISIBLE
            stickerGroup.visibility = android.view.View.VISIBLE
        }

        heightGroup.check(
            when (settings.defaultTargetHeight) {
                NevusSettings.HEIGHT_720P -> R.id.dialogHeight720
                NevusSettings.HEIGHT_1080P -> R.id.dialogHeight1080
                NevusSettings.HEIGHT_1440P -> R.id.dialogHeight1440
                else -> R.id.dialogHeightAuto
            }
        )
        enhanceCheckbox.isChecked = settings.enhanceByDefault

        val manifestKind = PlaylistParser.probe(detection.url)
        var variants: List<QualityVariant> = emptyList()
        if (manifestKind != ManifestKind.NONE) {
            scope.launch {
                variants = PlaylistParser.listVariants(detection.url, manifestKind)
                if (variants.isNotEmpty()) {
                    sourceQualityLabel.visibility = android.view.View.VISIBLE
                    sourceQualitySpinner.visibility = android.view.View.VISIBLE
                    sourceQualitySpinner.adapter = ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(context.getString(R.string.download_options_source_quality_default)) + variants.map { it.label },
                    )
                }
            }
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.download_options_title)
            .setView(view)
            .setPositiveButton(R.string.download_options_start) { _, _ ->
                val audioOnly = formatGroup.checkedRadioButtonId == R.id.formatAudioOnly
                val targetHeight = when (heightGroup.checkedRadioButtonId) {
                    R.id.dialogHeight720 -> NevusSettings.HEIGHT_720P
                    R.id.dialogHeight1080 -> NevusSettings.HEIGHT_1080P
                    R.id.dialogHeight1440 -> NevusSettings.HEIGHT_1440P
                    else -> null
                }
                // Position 0 is always "Kualitas sumber (default)" — no variant chosen.
                val spinnerPos = sourceQualitySpinner.selectedItemPosition
                val chosenVariant = if (spinnerPos > 0) variants.getOrNull(spinnerPos - 1) else null

                val stickerMode = when (stickerGroup.checkedRadioButtonId) {
                    R.id.stickerBorder -> StickerMode.BORDER
                    R.id.stickerBackgroundRemoval -> StickerMode.BACKGROUND_REMOVAL
                    else -> StickerMode.NONE
                }

                val plan = DownloadPlan(
                    detection = detection,
                    audioOnly = audioOnly,
                    manifestKind = manifestKind,
                    chosenVariant = chosenVariant,
                    targetHeightPx = targetHeight,
                    enhance = enhanceCheckbox.isChecked,
                    stickerMode = stickerMode,
                    segmented = segmentedCheckbox.isChecked,
                )
                FloatingBubbleService.submitPlan(context.applicationContext, plan)
            }
            .setNegativeButton(R.string.download_options_cancel, null)
            .show()
    }
}
