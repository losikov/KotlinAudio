package com.doublesymmetry.kotlinaudio.utils

import android.os.Bundle
import com.doublesymmetry.kotlinaudio.models.AAMediaSessionCallBack

/**
 * The Android Auto media-session callbacks, stubbed out.
 *
 * [com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer] takes one as a required constructor
 * argument; nothing in these tests drives playback from a media session, so every entry point is a
 * no-op. Without it the instrumented source set does not compile at all, which is the state this
 * fork's `AudioPlayerTest` and `QueuedAudioPlayerTest` were left in when the argument was added.
 */
object NoopMediaSessionCallback : AAMediaSessionCallBack {
    override fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?) = Unit

    override fun handlePlayFromSearch(query: String?, extras: Bundle?) = Unit

    override fun handlePrepareFromMediaId(mediaId: String?, extras: Bundle?) = Unit

    override fun handlePrepareFromSearch(query: String?, extras: Bundle?) = Unit

    override fun handleSkipToQueueItem(id: Long) = Unit
}
