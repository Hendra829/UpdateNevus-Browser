package com.nevus.mediabridge.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Wraps `androidx.media3.transformer.Transformer` for the *optional* "Tingkatkan kualitas" step:
 * a real resize (`Presentation.createForHeight`) + mild contrast/vividness boost for video, and a
 * real bass/treble shelving EQ ([ShelvingEqAudioProcessor]) for audio. This is honest signal
 * processing — a resize does not invent detail that was not in the source, and the contrast
 * bump is a standard, modest RGB adjustment, not "AI enhancement".
 *
 * Must be invoked from a thread with a Looper (Transformer's requirement) — the caller
 * ([com.nevus.mediabridge.download.FloatingBubbleService]) runs on `Dispatchers.Main.immediate`.
 */
@UnstableApi
class MediaEnhancer(private val context: Context) {

    fun enhance(
        inputPath: String,
        outputPath: String,
        audioOnly: Boolean,
        targetHeightPx: Int? = null,
        bassBoostDb: Float = 6f,
        trebleBoostDb: Float = 4f,
    ): Flow<EnhanceEvent> = callbackFlow {
        val videoEffects: List<Effect> = if (audioOnly) {
            emptyList()
        } else {
            buildList {
                targetHeightPx?.let { add(Presentation.createForHeight(it)) }
                add(Contrast(CONTRAST_BOOST))
            }
        }
        val audioProcessors: List<AudioProcessor> = listOf(ShelvingEqAudioProcessor(bassBoostDb, trebleBoostDb))

        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(inputPath)))
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(audioOnly)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                NevusLog.i(TAG, "Enhance completed: $outputPath")
                trySend(EnhanceEvent.Completed(outputPath))
                close()
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                NevusLog.w(TAG, "Enhance failed for $inputPath", exportException)
                trySend(EnhanceEvent.Failed(exportException.message ?: "export failed", exportException))
                close()
            }
        }

        val transformer = Transformer.Builder(context)
            .addListener(listener)
            .build()

        trySend(EnhanceEvent.Started)
        runCatching { transformer.start(editedMediaItem, outputPath) }
            .onFailure { t ->
                NevusLog.w(TAG, "Failed to start Transformer for $inputPath", t)
                trySend(EnhanceEvent.Failed(t.message ?: "failed to start export", t))
                close()
            }

        awaitClose { runCatching { transformer.cancel() } }
    }

    companion object {
        private const val TAG = "MediaEnhancer"

        /** Modest, honest contrast bump — not a stand-in for real detail enhancement. */
        private const val CONTRAST_BOOST = 0.15f
    }
}

sealed interface EnhanceEvent {
    data object Started : EnhanceEvent
    data class Completed(val outputPath: String) : EnhanceEvent
    data class Failed(val message: String, val cause: Throwable? = null) : EnhanceEvent
}
