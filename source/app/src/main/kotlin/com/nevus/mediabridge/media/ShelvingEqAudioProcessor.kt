package com.nevus.mediabridge.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real bass/treble shelving EQ — a second-order (biquad) low-shelf + high-shelf filter chain per
 * channel, coefficients from the RBJ Audio EQ Cookbook. This is genuine signal processing baked
 * into the exported audio, not a cosmetic label: a 0 dB setting is a no-op, positive dB values
 * measurably boost the target band.
 *
 * PCM16 only (mirrors the common case for media3's decoder output); [onConfigure] rejects
 * anything else rather than silently mishandling other encodings.
 */
@UnstableApi
class ShelvingEqAudioProcessor(
    private val bassBoostDb: Float = 6f,
    private val trebleBoostDb: Float = 4f,
) : BaseAudioProcessor() {

    private var bassFilters: Array<Biquad> = emptyArray()
    private var trebleFilters: Array<Biquad> = emptyArray()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        bassFilters = Array(inputAudioFormat.channelCount) {
            Biquad.lowShelf(sampleRate, BASS_FREQUENCY_HZ, bassBoostDb.toDouble())
        }
        trebleFilters = Array(inputAudioFormat.channelCount) {
            Biquad.highShelf(sampleRate, TREBLE_FREQUENCY_HZ, trebleBoostDb.toDouble())
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val output = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        while (inputBuffer.remaining() >= channels * 2) {
            for (ch in 0 until channels) {
                var x = inputBuffer.short.toDouble() / 32768.0
                x = bassFilters[ch].process(x)
                x = trebleFilters[ch].process(x)
                val sample = (x * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                output.putShort(sample)
            }
        }
        output.flip()
    }

    override fun onFlush() {
        bassFilters.forEach { it.reset() }
        trebleFilters.forEach { it.reset() }
    }

    /** Direct Form I biquad — [b0,b1,b2,a1,a2] pre-normalized by a0. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x0: Double): Double {
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            return y0
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }

        companion object {
            /** RBJ Audio EQ Cookbook low-shelf, shelf slope S=1 (steepest monotonic response). */
            fun lowShelf(sampleRate: Double, f0: Double, dbGain: Double): Biquad {
                val a = 10.0.pow(dbGain / 40.0)
                val w0 = 2.0 * Math.PI * f0 / sampleRate
                val cosW0 = cos(w0)
                val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 1.0 - 1.0) + 2.0)
                val sqrtA2Alpha = 2.0 * sqrt(a) * alpha

                val b0 = a * ((a + 1) - (a - 1) * cosW0 + sqrtA2Alpha)
                val b1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
                val b2 = a * ((a + 1) - (a - 1) * cosW0 - sqrtA2Alpha)
                val a0 = (a + 1) + (a - 1) * cosW0 + sqrtA2Alpha
                val a1 = -2 * ((a - 1) + (a + 1) * cosW0)
                val a2 = (a + 1) + (a - 1) * cosW0 - sqrtA2Alpha
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            /** RBJ Audio EQ Cookbook high-shelf, shelf slope S=1. */
            fun highShelf(sampleRate: Double, f0: Double, dbGain: Double): Biquad {
                val a = 10.0.pow(dbGain / 40.0)
                val w0 = 2.0 * Math.PI * f0 / sampleRate
                val cosW0 = cos(w0)
                val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 1.0 - 1.0) + 2.0)
                val sqrtA2Alpha = 2.0 * sqrt(a) * alpha

                val b0 = a * ((a + 1) + (a - 1) * cosW0 + sqrtA2Alpha)
                val b1 = -2 * a * ((a - 1) + (a + 1) * cosW0)
                val b2 = a * ((a + 1) + (a - 1) * cosW0 - sqrtA2Alpha)
                val a0 = (a + 1) - (a - 1) * cosW0 + sqrtA2Alpha
                val a1 = 2 * ((a - 1) - (a + 1) * cosW0)
                val a2 = (a + 1) - (a - 1) * cosW0 - sqrtA2Alpha
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    companion object {
        private const val BASS_FREQUENCY_HZ = 150.0
        private const val TREBLE_FREQUENCY_HZ = 4000.0
    }
}
