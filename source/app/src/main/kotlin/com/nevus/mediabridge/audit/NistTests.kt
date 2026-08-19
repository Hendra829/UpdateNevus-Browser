package com.nevus.mediabridge.audit

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Statistical primitives used by [LiveStatisticalValidator].
 *
 * Every test returns a *p-value*: the probability of observing a deviation at least as extreme
 * as the one measured, under the null hypothesis "the stream is uniformly random". Reject the
 * null (i.e. raise an alarm) when the p-value is below a chosen significance level — typically
 * `0.01` for continuous monitoring, per NIST SP 800-22 Appendix A guidance.
 *
 * The p-value approximations here are self-contained (no Apache Commons Math dependency) and
 * accurate enough for continuous liveness monitoring in the [0.001, 0.999] range that matters.
 */
internal object NistTests {

    /**
     * NIST SP 800-22 §2.1 — Frequency (monobit) test on a byte stream.
     *
     * Under H0 the count of `1` bits equals half the total bit count. Large deviations imply
     * biased output.
     */
    fun monobit(bytes: ByteArray, offset: Int, length: Int): Double {
        require(length > 0)
        var onesMinusZeros = 0L
        val end = offset + length
        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            // Java's Integer.bitCount uses HD 5-2 popcount; 8-bit popcount inlined here to avoid Integer boxing on some ARTs.
            var v = b
            v -= (v shr 1) and 0x55
            v = (v and 0x33) + ((v shr 2) and 0x33)
            val popcount = (v + (v shr 4)) and 0x0F
            onesMinusZeros += (2 * popcount - 8).toLong()
        }
        val n = length * 8.0
        val sObs = abs(onesMinusZeros) / sqrt(n)
        return erfc(sObs / SQRT_2)
    }

    /**
     * NIST SP 800-22 §2.3 — Runs test.
     *
     * A "run" is a maximal sub-sequence of identical bits. Too few or too many runs relative to
     * the expected value for the observed 1-frequency imply the generator has forgotten how to
     * alternate.
     */
    fun runs(bytes: ByteArray, offset: Int, length: Int): Double {
        require(length > 0)
        val n = length * 8
        // Count of ones
        var ones = 0
        for (i in offset until offset + length) {
            var b = bytes[i].toInt() and 0xFF
            b -= (b shr 1) and 0x55
            b = (b and 0x33) + ((b shr 2) and 0x33)
            ones += (b + (b shr 4)) and 0x0F
        }
        val pi = ones.toDouble() / n
        // Runs test only meaningful when frequency test would already pass: |pi - 0.5| <= 2/sqrt(n)
        val freqOk = abs(pi - 0.5) <= 2.0 / sqrt(n.toDouble())
        if (!freqOk) return 0.0

        // V_n = 1 + Σ r(k), where r(k) = 0 if x_k == x_{k+1} else 1.
        // Iterate bit-by-bit using bytes; we track transitions between consecutive bits.
        var transitions = 0L
        var prevBit = -1
        for (i in offset until offset + length) {
            val v = bytes[i].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val cur = (v ushr bit) and 1
                if (prevBit >= 0 && cur != prevBit) transitions++
                prevBit = cur
            }
        }
        val vObs = (transitions + 1).toDouble()
        val expected = 2.0 * n * pi * (1.0 - pi)
        val denom = 2.0 * sqrt(2.0 * n) * pi * (1.0 - pi)
        val z = abs(vObs - expected) / denom
        return erfc(z / SQRT_2)
    }

    /**
     * Byte-histogram chi-square goodness-of-fit test (df = 255).
     *
     * Under H0 each of the 256 byte values appears with probability 1/256. The p-value is
     * approximated via the Wilson–Hilferty transformation (a normal approximation to
     * chi-square that is accurate to three decimals for df ≥ 30).
     */
    fun byteHistogramChiSquare(bytes: ByteArray, offset: Int, length: Int): Double {
        require(length >= 512) { "chi-square needs ≥512 samples for a stable histogram" }
        val counts = IntArray(256)
        val end = offset + length
        for (i in offset until end) counts[bytes[i].toInt() and 0xFF]++
        val expected = length.toDouble() / 256.0
        var chi = 0.0
        for (c in counts) {
            val d = c - expected
            chi += (d * d) / expected
        }
        return chiSquareUpperTailP(chi, df = 255)
    }

    /**
     * NIST SP 800-22 §2.2 — Block Frequency test.
     *
     * Splits the stream into fixed-size blocks and checks that each block's own 1-fraction is
     * close to 0.5, not just the overall stream (the monobit test can pass on a stream that is
     * locally biased in a way that cancels out globally — e.g. alternating long runs of mostly-1
     * and mostly-0 blocks). [blockBits] defaults to 1024, satisfying NIST's recommended
     * `M > 0.01n` and `N < 100` bounds for the engine's default 8192-byte (65536-bit) window.
     */
    fun blockFrequency(bytes: ByteArray, offset: Int, length: Int, blockBits: Int = 1024): Double {
        val totalBits = length * 8
        val numBlocks = totalBits / blockBits
        require(numBlocks >= 1) { "stream too short for a $blockBits-bit block" }

        var chiSum = 0.0
        for (blockIndex in 0 until numBlocks) {
            var ones = 0
            val blockStartBit = blockIndex * blockBits
            for (bitOffset in 0 until blockBits) {
                val globalBit = blockStartBit + bitOffset
                val byteIndex = offset + globalBit / 8
                val bitInByte = 7 - (globalBit % 8)
                ones += (bytes[byteIndex].toInt() ushr bitInByte) and 1
            }
            val pi = ones.toDouble() / blockBits
            val d = pi - 0.5
            chiSum += d * d
        }
        val chiSquare = 4.0 * blockBits * chiSum
        return chiSquareUpperTailP(chiSquare, df = numBlocks)
    }

    // ─────────── numeric helpers ───────────

    private const val SQRT_2 = 1.41421356237309504880

    /**
     * Complementary error function `erfc(x) = 1 - erf(x)`.
     *
     * Abramowitz & Stegun 7.1.26 approximation with |error| < 1.5e-7 for x >= 0. For negative
     * x we use the identity `erfc(-x) = 2 - erfc(x)`.
     */
    fun erfc(x: Double): Double {
        if (x < 0) return 2.0 - erfc(-x)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val poly = t * (
            0.254829592 + t * (
                -0.284496736 + t * (
                    1.421413741 + t * (
                        -1.453152027 + t * 1.061405429
                    )
                )
            )
        )
        return poly * exp(-x * x)
    }

    /**
     * P(X > chi) for X ~ chi-square(df), via Wilson–Hilferty:
     *   Z ≈ ((chi/df)^(1/3) − (1 − 2/(9·df))) / sqrt(2/(9·df)) ~ N(0,1)
     */
    private fun chiSquareUpperTailP(chi: Double, df: Int): Double {
        val k = df.toDouble()
        val z = ((chi / k).pow(1.0 / 3.0) - (1.0 - 2.0 / (9.0 * k))) / sqrt(2.0 / (9.0 * k))
        // Upper tail of standard normal = 0.5 * erfc(z / sqrt(2))
        return 0.5 * erfc(z / SQRT_2)
    }
}
