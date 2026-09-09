package com.doublesymmetry.kotlinaudio.players.components

import android.net.Uri
import com.google.android.exoplayer2.extractor.Extractor
import com.google.android.exoplayer2.extractor.ExtractorInput
import com.google.android.exoplayer2.extractor.ExtractorOutput
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.extractor.ForwardingExtractorInput
import com.google.android.exoplayer2.extractor.PositionHolder
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import timber.log.Timber

/**
 * Makes ExoPlayer 2.19.1 seek constant-bitrate MP3s by arithmetic instead of through the
 * `Info` frame's table of contents.
 *
 * LAME and ffmpeg put a 100-entry, one-byte-per-entry table of contents in the `Info` frame at the
 * start of every MP3 they write, CBR files included. Each entry resolves to 1/256th of the file,
 * which on a 27-minute chapter is 6.4 s of audio. ExoPlayer 2.19.1 seeks through that table
 * whenever it is present, lands up to several seconds away from the requested time, and then
 * stamps the first sample with the time that was *asked for*. The reported position and the sound
 * therefore disagree by a constant that depends on where in the file you seeked, in either
 * direction. Word-level narration sync is drawn from that position and shows it as a highlight a
 * few words away from the voice.
 *
 * Media3 1.4 fixed this upstream (androidx/media#1376): an `Info` header means CBR, so it ignores
 * the table and seeks by `byte = time * bitrate / 8`, which is exact to one frame. The legacy
 * `com.google.android.exoplayer` line ended at 2.19.1 and never received it. This wrapper produces
 * the same outcome without touching the library: it clears the table-of-contents flag bit in the
 * `Info` frame as the extractor reads it, so [Mp3Extractor] takes its own "Info header without a
 * table" path and builds a `ConstantBitrateSeeker` anchored on the first audio frame. Duration,
 * gapless metadata and ID3 handling are unchanged. A genuine VBR file carries `Xing`, not `Info`,
 * and is left alone.
 */
class CbrMp3Extractor(private val delegate: Mp3Extractor) : Extractor {

    /** Absolute byte offset of the `Info` frame's flags int, or -1 if there is none. */
    private var infoFlagsPosition = -1L
    private var probed = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(wrap(input))

    override fun init(output: ExtractorOutput) = delegate.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(wrap(input), seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    private fun wrap(input: ExtractorInput): ExtractorInput = TocHidingInput(input)

    /**
     * Locates the `Info` frame by peeking from the start of the stream. Runs on the first read or
     * peek at position 0 and, once it has an answer, never again. Restores the peek position it
     * found.
     *
     * An I/O failure is not an answer. ExoPlayer cancels an in-flight load by interrupting the
     * loader thread, which surfaces here as an IOException; it is rethrown so the extractor sees
     * the same failure it would have seen without this wrapper, and the next attempt probes again.
     */
    private fun probe(input: ExtractorInput) {
        val savedPeekOffset = (input.peekPosition - input.position).toInt()
        try {
            input.resetPeekPosition()
            infoFlagsPosition = findInfoFlags(input)
            probed = true
        } finally {
            input.resetPeekPosition()
            if (savedPeekOffset > 0) input.advancePeekPosition(savedPeekOffset)
        }
        if (infoFlagsPosition >= 0) {
            Timber.d("CbrMp3Extractor: hiding Info table of contents (flags at byte %d); using constant-bitrate seeking", infoFlagsPosition)
        } else {
            Timber.d("CbrMp3Extractor: no Info frame found; leaving the stream untouched")
        }
    }

    private fun findInfoFlags(input: ExtractorInput): Long {
        val head = ByteArray(10)
        if (!input.peekFully(head, 0, 10, true)) return -1
        var frameStart = 0L
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val size = (head[6].toInt() and 0x7F shl 21) or
                (head[7].toInt() and 0x7F shl 14) or
                (head[8].toInt() and 0x7F shl 7) or
                (head[9].toInt() and 0x7F)
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            frameStart = 10L + size + footer
            if (!input.advancePeekPosition(size + footer, true)) return -1
        } else {
            input.resetPeekPosition()
        }

        // Scan a window for the first MPEG audio frame whose Xing slot reads "Info".
        val window = ByteArray(SCAN_BYTES)
        var filled = 0
        while (filled < window.size) {
            val n = input.peek(window, filled, window.size - filled)
            if (n <= 0) break
            filled += n
        }
        var i = 0
        while (i + 4 <= filled) {
            val xingBase = xingBaseForHeader(window, i)
            if (xingBase >= 0 && i + xingBase + 8 <= filled) {
                if (matches(window, i + xingBase, INFO)) return frameStart + i + xingBase + 4
                if (matches(window, i + xingBase, XING)) return -1
            }
            i++
        }
        return -1
    }

    /** Returns the Xing/Info offset within a frame whose header starts at [i], or -1 if [i] is not a valid Layer III header. */
    private fun xingBaseForHeader(b: ByteArray, i: Int): Int {
        val b0 = b[i].toInt() and 0xFF
        val b1 = b[i + 1].toInt() and 0xFF
        val b2 = b[i + 2].toInt() and 0xFF
        val b3 = b[i + 3].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return -1
        val version = b1 shr 3 and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5, 1 = reserved
        val layer = b1 shr 1 and 3 // 1 = Layer III
        val bitrateIndex = b2 shr 4
        val sampleRateIndex = b2 shr 2 and 3
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return -1
        val mono = b3 shr 6 == 3
        return if (version == 3) (if (mono) 21 else 36) else (if (mono) 13 else 21)
    }

    private fun matches(b: ByteArray, at: Int, tag: ByteArray): Boolean {
        for (k in tag.indices) if (b[at + k] != tag[k]) return false
        return true
    }

    /**
     * Clears the table-of-contents bit (0x04) of the `Info` flags in any bytes handed to the
     * extractor. The flags are a big-endian int, so the bit lives in its last byte.
     */
    private fun patch(target: ByteArray, offset: Int, absoluteStart: Long, count: Int) {
        if (infoFlagsPosition < 0 || count <= 0) return
        val flagByte = infoFlagsPosition + 3
        if (flagByte < absoluteStart || flagByte >= absoluteStart + count) return
        val idx = offset + (flagByte - absoluteStart).toInt()
        target[idx] = (target[idx].toInt() and 0x04.inv()).toByte()
    }

    private inner class TocHidingInput(private val inner: ExtractorInput) : ForwardingExtractorInput(inner) {

        private fun ensureProbed() {
            if (!probed && inner.position == 0L) probe(inner)
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            ensureProbed()
            val start = inner.position
            val n = super.read(target, offset, length)
            if (n > 0) patch(target, offset, start, n)
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            ensureProbed()
            val start = inner.position
            val ok = super.readFully(target, offset, length, allowEndOfInput)
            if (ok) patch(target, offset, start, length)
            return ok
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            ensureProbed()
            val start = inner.position
            super.readFully(target, offset, length)
            patch(target, offset, start, length)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            ensureProbed()
            val start = inner.peekPosition
            val n = super.peek(target, offset, length)
            if (n > 0) patch(target, offset, start, n)
            return n
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            ensureProbed()
            val start = inner.peekPosition
            val ok = super.peekFully(target, offset, length, allowEndOfInput)
            if (ok) patch(target, offset, start, length)
            return ok
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            ensureProbed()
            val start = inner.peekPosition
            super.peekFully(target, offset, length)
            patch(target, offset, start, length)
        }
    }

    companion object {
        private const val SCAN_BYTES = 16 * 1024
        private val INFO = byteArrayOf(0x49, 0x6E, 0x66, 0x6F) // "Info"
        private val XING = byteArrayOf(0x58, 0x69, 0x6E, 0x67) // "Xing"

        /** Wraps every [Mp3Extractor] produced by [base]; other extractors pass through untouched. */
        fun wrapping(base: ExtractorsFactory): ExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = wrapAll(base.createExtractors())

            override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
                wrapAll(base.createExtractors(uri, responseHeaders))

            private fun wrapAll(extractors: Array<Extractor>): Array<Extractor> =
                extractors.map { if (it is Mp3Extractor) CbrMp3Extractor(it) else it }.toTypedArray()
        }
    }
}
