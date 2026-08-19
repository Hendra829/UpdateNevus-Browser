package com.nevus.mediabridge.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioGroup
import android.widget.Toast
import com.nevus.mediabridge.R
import com.nevus.mediabridge.download.StickerMode
import com.nevus.mediabridge.media.StickerMaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reusable "make a sticker from this already-downloaded image" picker — used from a history row
 * (retroactive), separate from the sticker choice offered up front in [DownloadOptionsDialog].
 */
object StickerChoiceDialog {

    fun show(context: Context, scope: CoroutineScope, inputPath: String) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_sticker_choice, null, false)
        val group = view.findViewById<RadioGroup>(R.id.stickerChoiceGroup)

        AlertDialog.Builder(context)
            .setTitle(R.string.sticker_action)
            .setView(view)
            .setPositiveButton(R.string.sticker_action) { _, _ ->
                val mode = if (group.checkedRadioButtonId == R.id.stickerChoiceBackgroundRemoval) {
                    StickerMode.BACKGROUND_REMOVAL
                } else {
                    StickerMode.BORDER
                }
                run(context, scope, inputPath, mode)
            }
            .setNegativeButton(R.string.download_options_cancel, null)
            .show()
    }

    private fun run(context: Context, scope: CoroutineScope, inputPath: String, mode: StickerMode) {
        scope.launch {
            val input = File(inputPath)
            val outputPath = File(input.parentFile, "${input.nameWithoutExtension}-sticker.webp").absolutePath
            val ok = withContext(Dispatchers.Default) {
                when (mode) {
                    StickerMode.BORDER -> StickerMaker.simpleBorder(inputPath, outputPath)
                    StickerMode.BACKGROUND_REMOVAL -> StickerMaker.removeBackground(inputPath, outputPath)
                    StickerMode.NONE -> false
                }
            }
            val message = if (ok) {
                context.getString(R.string.download_done, File(outputPath).name)
            } else {
                context.getString(R.string.download_failed, File(inputPath).name)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
