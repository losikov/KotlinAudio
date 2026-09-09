package com.doublesymmetry.kotlinaudio

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.doublesymmetry.kotlinaudio.players.BaseAudioPlayer
import com.doublesymmetry.kotlinaudio.test.R
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The check the Detox suite cannot make: does the player fetch the audio the caller asked for?
 *
 * `audio-sync-precision-{en,es,fr}` in the app repo prove that the marker, the cue file and the
 * clock agree with each other. They cannot prove that the *sound* agrees with any of them, and
 * that is exactly the shape the bug this file exists for had: ExoPlayer 2.19.1 seeks a CBR MP3
 * through the `Info` frame's 100-entry, one-byte-per-entry table of contents, lands up to several
 * seconds away, and then stamps the first sample with the time that was *asked* for. Every layer
 * above it was told the truth about a lie.
 *
 * So this measures the one thing that cannot lie: the byte offset the player requests from the
 * network. For a constant-bitrate file the answer is arithmetic —
 * `firstAudioFrame + t * bitrate / 8` — and [com.doublesymmetry.kotlinaudio.players.components.CbrMp3Extractor]
 * is what makes ExoPlayer use it.
 *
 * ### The fixture
 *
 * `res/raw/cbr_tone_300s.mp3`, generated once with
 *
 * ```
 * ffmpeg -f lavfi -i sine=frequency=440:duration=300 -c:a libmp3lame -b:a 128k -ac 2 cbr_tone_300s.mp3
 * ```
 *
 * Five minutes rather than ten only to keep 4.8 MB rather than 9.6 MB in the repository; the
 * table of contents is just as coarse either way. Its measured properties, which the constants
 * below state and [firstRequestedByteFor] relies on:
 *
 *  - ID3v2 header of 45 bytes, then the `Xing`/`Info` frame at byte 45 (417 bytes long), so the
 *    first audio frame begins at byte **462**.
 *  - 128 kbit/s → 16000 bytes of audio per second; one frame is 417 or 418 bytes.
 *  - The `Info` frame carries the table-of-contents flag and a full 100-byte table. Interpolating
 *    through it puts a seek up to **1.57 s — about 25000 bytes — away** from the right place, worst
 *    at t≈243 s. That is 60 times the tolerance asserted here, so this test fails loudly without
 *    the extractor and passes with it; it is not a test that happens to be satisfied either way.
 *
 * Regenerating the fixture at a different length or bitrate invalidates the constants, and the
 * first assertion in [givenCbrMp3_whenSeeking_thenRequestsTheArithmeticByteOffset] says so rather
 * than failing obscurely later.
 */
class CbrMp3SeekAccuracyTest {

    /** A [DataSource.Factory] that remembers every byte offset ExoPlayer opens the file at. */
    private class RecordingFileDataSourceFactory(private val file: File) : DataSource.Factory {
        val requestedPositions = CopyOnWriteArrayList<Long>()

        override fun createDataSource(): DataSource = object : DataSource {
            private val inner = FileDataSource()

            override fun open(dataSpec: DataSpec): Long {
                requestedPositions.add(dataSpec.position)
                return inner.open(dataSpec.withUri(Uri.fromFile(file)))
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                inner.read(buffer, offset, length)

            override fun getUri(): Uri? = inner.uri

            override fun close() = inner.close()

            override fun addTransferListener(transferListener: TransferListener) =
                inner.addTransferListener(transferListener)

            override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders
        }
    }

    /** ID3v2 header plus the `Info` frame — where the first sample of real audio lives. */
    private val firstAudioFrameOffset = 462L

    /** 128 kbit/s. */
    private val bytesPerSecond = 16_000L

    /**
     * One MPEG Layer III frame at 128 kbit/s / 44.1 kHz: 1152 samples, 417 or 418 bytes depending
     * on padding. A seeker cannot be more precise than the frame it has to start decoding at, so
     * this is the tightest tolerance the assertion can carry.
     */
    private val frameBytes = 418L

    /**
     * Times to seek to, in seconds. Spread across the file and including t≈243 s, where the table
     * of contents is at its worst — a test that only sampled the ends of the file would find the
     * table nearly honest, because its first and last entries are exact by construction.
     */
    private val seekTargets = listOf(30L, 90L, 150L, 243L, 280L)

    private val LOG_TAG = "CbrMp3SeekAccuracy"

    private lateinit var fixture: File
    private lateinit var recording: RecordingFileDataSourceFactory
    private lateinit var player: ExoPlayer

    @BeforeEach
    fun setUp() = runBlocking(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A raw resource has no file path, and the point of this test is byte offsets in a file,
        // so it is copied out once per test.
        fixture = File(context.cacheDir, "cbr_tone_300s.mp3")
        if (!fixture.exists() || fixture.length() == 0L) {
            context.resources.openRawResource(R.raw.cbr_tone_300s).use { input ->
                fixture.outputStream().use { output -> input.copyTo(output) }
            }
        }
        recording = RecordingFileDataSourceFactory(fixture)
        // A deliberately tiny buffer, and the whole measurement depends on it. A 4.8 MB local file
        // is loaded end to end within a moment of `prepare()`, and ExoPlayer serves a seek from
        // sample queues it already holds without asking the data source for anything — so a player
        // on default settings records exactly one byte offset for the whole test, and the question
        // being asked here ("which byte does a seek to t fetch?") never reaches the file. Held to a
        // couple of seconds of audio, every seek in this test lands outside what is buffered, and
        // ProgressiveMediaPeriod restarts loading at the offset the seek map resolved — which is
        // the number under measurement.
        player = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 5_000, 1_000, 2_000)
                    // 256 KB is 16 seconds of this file, and the seek targets are never closer
                    // than 30 seconds apart, so every one of them lands outside what is held. Not
                    // smaller: below a few of the allocator's 64 KB chunks the load control stops
                    // loading while the renderer still wants data, and ExoPlayer's own watchdog
                    // fails the playback with "stuck buffering and not loading".
                    .setTargetBufferBytes(256 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .build()
        player.volume = 0f
        // Nothing here listens; playing would only move the position out from under the assertions.
        player.playWhenReady = false
        // The same extractor stack every progressive track in the app is played through, taken
        // from the player rather than rebuilt here — a copy could agree with itself while the real
        // wiring drifted.
        player.setMediaSource(
            ProgressiveMediaSource.Factory(recording, BaseAudioPlayer.progressiveExtractorsFactory())
                // The load control is only consulted between chunks, and the default chunk is 1 MB
                // — a minute of this file, read in one go from local disk before anything is asked
                // about buffer limits, which is enough to serve the first seek out of memory and
                // record no request at all. 64 KB makes the buffer limits above mean what they say.
                .setContinueLoadingCheckIntervalBytes(64 * 1024)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(fixture))),
        )
        player.prepare()
        awaitState(Player.STATE_READY)
    }

    @AfterEach
    fun tearDown() = runBlocking(Dispatchers.Main) {
        player.release()
    }

    private suspend fun awaitState(state: Int, timeout: Duration = Duration.ofSeconds(30)) {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            val current = withContext(Dispatchers.Main) { player.playbackState }
            if (current == state) return
            withContext(Dispatchers.Default) { Thread.sleep(50) }
        }
        val (actual, error) = withContext(Dispatchers.Main) { player.playbackState to player.playerError }
        throw AssertionError("Player never reached state $state (stuck in $actual, error: $error)")
    }

    /**
     * Seek to [seconds] and return the first byte offset the player opened the file at afterwards,
     * waiting for the seek to actually produce a read.
     */
    private suspend fun firstRequestedByteFor(seconds: Long): Long {
        recording.requestedPositions.clear()
        withContext(Dispatchers.Main) { player.seekTo(seconds * 1000) }

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            // The extractor re-probes the `Info` frame from the top of the stream after a seek;
            // that read is not the seek's own, and the first offset past the header is.
            val positions = recording.requestedPositions.filter { it >= firstAudioFrameOffset }
            if (positions.isNotEmpty()) {
                // Let the player finish settling before its reported position is read.
                awaitState(Player.STATE_READY)
                return positions.first()
            }
            withContext(Dispatchers.Default) { Thread.sleep(50) }
        }
        throw AssertionError(
            "Seek to ${seconds}s never requested any byte past the header " +
                "(requested: ${recording.requestedPositions.toList()})",
        )
    }

    @Test
    fun givenCbrMp3_whenSeeking_thenRequestsTheArithmeticByteOffset() = runBlocking {
        val length = fixture.length()
        // The constants above are properties of the committed fixture; a regenerated file would
        // make every number below meaningless, so say that here rather than fail obscurely.
        assertTrue(
            length in 4_700_000..4_900_000,
            "cbr_tone_300s.mp3 is $length bytes; the constants in this test describe the 4.80 MB " +
                "fixture generated by the ffmpeg line in the class comment",
        )

        val failures = mutableListOf<String>()
        seekTargets.forEach { seconds ->
            val requested = firstRequestedByteFor(seconds)
            val exact = firstAudioFrameOffset + seconds * bytesPerSecond
            val offBy = requested - exact

            // Logged on success as well as failure: the numbers are the result, and a run that
            // only says "passed" cannot be compared against the run before the fix.
            Log.i(
                LOG_TAG,
                "seek ${seconds}s -> byte $requested (arithmetic $exact, off by $offBy = " +
                    "${"%.3f".format(offBy.toDouble() / bytesPerSecond)}s)",
            )

            if (Math.abs(offBy) > frameBytes) {
                failures += "seek to ${seconds}s opened the file at byte $requested, " +
                    "arithmetic says $exact — off by $offBy bytes " +
                    "(${"%.2f".format(offBy.toDouble() / bytesPerSecond)}s), tolerance is one frame ($frameBytes)"
            }

            // And the position it reports is the position it was asked for — which was true before
            // the fix as well, and is the reason the byte assertion above has to exist. Kept so a
            // regression that broke the *reported* time could not hide behind a correct fetch.
            val reported = withContext(Dispatchers.Main) { player.currentPosition }
            if (Math.abs(reported - seconds * 1000) > 50) {
                failures += "seek to ${seconds}s settled at ${reported}ms, more than 50ms away"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
