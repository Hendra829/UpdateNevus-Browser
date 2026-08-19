package com.nevus.mediabridge.media

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Minimal from-scratch animated GIF89a encoder. Android has no built-in encoder for animated
 * GIF/WebP, so this is hand-written rather than adding a whole new library for one feature.
 *
 * Scope: fixed 256-color web-safe-ish palette (6x6x6 color cube + grayscale ramp) — no per-frame
 * adaptive palette/median-cut. Good enough for a short Ken Burns pan/zoom clip; not a general
 * photo-quality GIF encoder. LZW compression, per-frame Graphic Control Extension (delay), and a
 * NETSCAPE2.0 application extension for infinite looping.
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {

    private val palette = buildPalette()
    private var started = false

    fun start() {
        val stream = BufferedOutputStream(out)
        writeHeader(stream)
        writeLogicalScreenDescriptor(stream)
        writeGlobalColorTable(stream)
        writeNetscapeLoopExtension(stream)
        stream.flush()
        started = true
    }

    /** [bitmap] must already be [width]x[height]. [delayMs] is this frame's display time. */
    fun addFrame(bitmap: Bitmap, delayMs: Int) {
        check(started) { "call start() first" }
        val stream = BufferedOutputStream(out)
        val indices = quantize(bitmap)
        writeGraphicControlExtension(stream, delayMs)
        writeImageDescriptor(stream)
        writeImageData(stream, indices)
        stream.flush()
    }

    fun finish() {
        out.write(TRAILER)
        out.flush()
    }

    // ─────────── palette ───────────

    private fun buildPalette(): IntArray {
        // 6x6x6 color cube (216 colors) + a 40-step grayscale ramp = 256.
        val colors = IntArray(256)
        var i = 0
        val steps = intArrayOf(0, 51, 102, 153, 204, 255)
        for (r in steps) for (g in steps) for (b in steps) {
            colors[i++] = (r shl 16) or (g shl 8) or b
        }
        while (i < 256) {
            val v = ((i - 216) * 255 / 39).coerceIn(0, 255)
            colors[i++] = (v shl 16) or (v shl 8) or v
        }
        return colors
    }

    private fun nearestPaletteIndex(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dr = r - pr; val dg = g - pg; val db = b - pb
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) { bestDist = dist; best = i }
        }
        return best
    }

    private fun quantize(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArray(pixels.size)
        for (i in pixels.indices) out[i] = nearestPaletteIndex(pixels[i]).toByte()
        return out
    }

    // ─────────── GIF structure ───────────

    private fun writeHeader(s: OutputStream) = s.write("GIF89a".toByteArray(Charsets.US_ASCII))

    private fun writeLogicalScreenDescriptor(s: OutputStream) {
        s.writeShortLE(width)
        s.writeShortLE(height)
        // Global color table present, color resolution 8 bits, table size 2^8 = 256.
        s.write(0xF7)
        s.write(0) // background color index
        s.write(0) // pixel aspect ratio
    }

    private fun writeGlobalColorTable(s: OutputStream) {
        for (c in palette) {
            s.write((c shr 16) and 0xFF)
            s.write((c shr 8) and 0xFF)
            s.write(c and 0xFF)
        }
    }

    private fun writeNetscapeLoopExtension(s: OutputStream) {
        s.write(0x21); s.write(0xFF); s.write(11)
        s.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        s.write(3); s.write(1)
        s.writeShortLE(0) // loop forever
        s.write(0)
    }

    private fun writeGraphicControlExtension(s: OutputStream, delayMs: Int) {
        s.write(0x21); s.write(0xF9); s.write(4)
        s.write(0x00) // no transparency, no disposal preference
        s.writeShortLE((delayMs / 10).coerceIn(1, 65535)) // GIF delay unit = 1/100s
        s.write(0) // transparent color index (unused)
        s.write(0)
    }

    private fun writeImageDescriptor(s: OutputStream) {
        s.write(0x2C)
        s.writeShortLE(0); s.writeShortLE(0)
        s.writeShortLE(width); s.writeShortLE(height)
        s.write(0x00) // no local color table, no interlace
    }

    private fun writeImageData(s: OutputStream, indices: ByteArray) {
        val minCodeSize = 8
        s.write(minCodeSize)
        val compressed = LzwEncoder(minCodeSize).encode(indices)
        var offset = 0
        while (offset < compressed.size) {
            val chunk = minOf(255, compressed.size - offset)
            s.write(chunk)
            s.write(compressed, offset, chunk)
            offset += chunk
        }
        s.write(0) // block terminator
    }

    private fun OutputStream.writeShortLE(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private companion object {
        val TRAILER = byteArrayOf(0x3B)
    }
}

/** Standard GIF LZW variable-width encoder (code size grows 9→12 bits, with clear/end codes). */
private class LzwEncoder(private val minCodeSize: Int) {

    fun encode(data: ByteArray): ByteArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var nextCode = endCode + 1
        var codeSize = minCodeSize + 1

        var dict = HashMap<String, Int>()
        fun resetDict() {
            dict = HashMap()
            for (i in 0 until clearCode) dict[i.toChar().toString()] = i
            nextCode = endCode + 1
            codeSize = minCodeSize + 1
        }
        resetDict()

        val bits = BitWriter()
        bits.write(clearCode, codeSize)

        var w = ""
        for (byte in data) {
            val c = (byte.toInt() and 0xFF).toChar().toString()
            val wc = w + c
            if (dict.containsKey(wc)) {
                w = wc
            } else {
                bits.write(dict.getValue(w), codeSize)
                if (nextCode < 4096) {
                    dict[wc] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    bits.write(clearCode, codeSize)
                    resetDict()
                }
                w = c
            }
        }
        if (w.isNotEmpty()) bits.write(dict.getValue(w), codeSize)
        bits.write(endCode, codeSize)
        return bits.toByteArray()
    }

    private class BitWriter {
        private val bytes = ArrayList<Byte>()
        private var bitBuffer = 0
        private var bitCount = 0

        fun write(code: Int, size: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                bytes.add((bitBuffer and 0xFF).toByte())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        fun toByteArray(): ByteArray {
            if (bitCount > 0) bytes.add((bitBuffer and 0xFF).toByte())
            return bytes.toByteArray()
        }
    }
}
