package com.doublesymmetry.kotlinaudio.players

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioAttributesCompat.CONTENT_TYPE_MUSIC
import androidx.media.AudioAttributesCompat.CONTENT_TYPE_SPEECH
import androidx.media.AudioAttributesCompat.CONTENT_TYPE_SONIFICATION
import androidx.media.AudioAttributesCompat.CONTENT_TYPE_MOVIE
import androidx.media.AudioAttributesCompat.USAGE_MEDIA
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.AudioManagerCompat.AUDIOFOCUS_GAIN
import com.doublesymmetry.kotlinaudio.event.EventHolder
import com.doublesymmetry.kotlinaudio.event.NotificationEventHolder
import com.doublesymmetry.kotlinaudio.event.PlayerEventHolder
import com.doublesymmetry.kotlinaudio.models.AudioContentType
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioItemHolder
import com.doublesymmetry.kotlinaudio.models.AudioItemTransitionReason
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.BufferConfig
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.models.DefaultPlayerOptions
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.AAMediaSessionCallBack
import com.doublesymmetry.kotlinaudio.models.MediaType
import com.doublesymmetry.kotlinaudio.models.PlayWhenReadyChangeData
import com.doublesymmetry.kotlinaudio.models.PlaybackError
import com.doublesymmetry.kotlinaudio.models.PlayerConfig
import com.doublesymmetry.kotlinaudio.models.PlayerOptions
import com.doublesymmetry.kotlinaudio.models.PositionChangedReason
import com.doublesymmetry.kotlinaudio.models.WakeMode
import com.doublesymmetry.kotlinaudio.notification.NotificationManager
import com.doublesymmetry.kotlinaudio.players.components.PlayerCache
import com.doublesymmetry.kotlinaudio.players.components.getAudioItemHolder
import com.doublesymmetry.kotlinaudio.utils.isUriLocalFile
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultLoadControl.Builder
import com.google.android.exoplayer2.DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
import com.google.android.exoplayer2.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
import com.google.android.exoplayer2.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
import com.google.android.exoplayer2.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
import com.google.android.exoplayer2.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ForwardingPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Player.Listener
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit


abstract class BaseAudioPlayer internal constructor(
    internal val context: Context,
    playerConfig: PlayerConfig,
    private val bufferConfig: BufferConfig?,
    private val cacheConfig: CacheConfig?,
    mediaSessionCallback: AAMediaSessionCallBack
) : AudioManager.OnAudioFocusChangeListener {
    protected val exoPlayer: ExoPlayer

    private var cache: SimpleCache? = null
    private val scope = MainScope()
    private var playerConfig: PlayerConfig = playerConfig
    var mediaSessionCallBack: AAMediaSessionCallBack = mediaSessionCallback

    val notificationManager: NotificationManager

    open val playerOptions: PlayerOptions = DefaultPlayerOptions()

    open val currentItem: AudioItem?
        get() = exoPlayer.currentMediaItem?.getAudioItemHolder()?.audioItem

    var playbackError: PlaybackError? = null
    var playerState: AudioPlayerState = AudioPlayerState.IDLE
        private set(value) {
            if (value != field) {
                field = value
                playerEventHolder.updateAudioPlayerState(value)
                if (!playerConfig.handleAudioFocus) {
                    when (value) {
                        AudioPlayerState.IDLE,
                        AudioPlayerState.ERROR -> abandonAudioFocusIfHeld()
                        AudioPlayerState.READY -> requestAudioFocus()
                        else -> {}
                    }
                }
            }
        }

    var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    val duration: Long
        get() {
            return if (exoPlayer.duration == C.TIME_UNSET) 0
            else exoPlayer.duration
        }

    val isCurrentMediaItemLive: Boolean
        get() = exoPlayer.isCurrentMediaItemLive

    private var oldPosition = 0L

    val position: Long
        get() {
            return if (exoPlayer.currentPosition == C.POSITION_UNSET.toLong()) 0
            else exoPlayer.currentPosition
        }

    val bufferedPosition: Long
        get() {
            return if (exoPlayer.bufferedPosition == C.POSITION_UNSET.toLong()) 0
            else exoPlayer.bufferedPosition
        }

    var volume: Float
        get() = exoPlayer.volume
        set(value) {
            exoPlayer.volume = value * volumeMultiplier
        }

    var playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed
        set(value) {
            exoPlayer.setPlaybackSpeed(value)
        }

    var automaticallyUpdateNotificationMetadata: Boolean = true

    private var volumeMultiplier = 1f
        private set(value) {
            field = value
            volume = volume
        }

    val isPlaying
        get() = exoPlayer.isPlaying

    private val notificationEventHolder = NotificationEventHolder()
    private val playerEventHolder = PlayerEventHolder()

    var ratingType: Int = RatingCompat.RATING_NONE
        set(value) {
            field = value
            mediaSession.setRatingType(ratingType)
            mediaSessionConnector.setRatingCallback(object : MediaSessionConnector.RatingCallback {
                override fun onCommand(
                    player: Player,
                    command: String,
                    extras: Bundle?,
                    cb: ResultReceiver?
                ): Boolean {
                    return true
                }

                override fun onSetRating(player: Player, rating: RatingCompat) {
                    playerEventHolder.updateOnPlayerActionTriggeredExternally(
                        MediaSessionCallback.RATING(
                            rating,
                            null
                        )
                    )
                }

                override fun onSetRating(player: Player, rating: RatingCompat, extras: Bundle?) {
                    playerEventHolder.updateOnPlayerActionTriggeredExternally(
                        MediaSessionCallback.RATING(
                            rating,
                            extras
                        )
                    )
                }
            })
        }

    val event = EventHolder(notificationEventHolder, playerEventHolder)

    private var focus: AudioFocusRequestCompat? = null
    private var hasAudioFocus = false
    private var wasDucking = false

    private val mediaSession = MediaSessionCompat(context, "KotlinAudioPlayer")
    private val mediaSessionConnector = MediaSessionConnector(mediaSession)

    init {
        if (cacheConfig != null) {
            cache = PlayerCache.getInstance(context, cacheConfig)
        }

        exoPlayer = ExoPlayer.Builder(context)
            .setHandleAudioBecomingNoisy(playerConfig.handleAudioBecomingNoisy)
            .setWakeMode(
                when (playerConfig.wakeMode) {
                    WakeMode.NONE -> C.WAKE_MODE_NONE
                    WakeMode.LOCAL -> C.WAKE_MODE_LOCAL
                    WakeMode.NETWORK -> C.WAKE_MODE_NETWORK
                }
            )
            .apply {
                if (bufferConfig != null) setLoadControl(setupBuffer(bufferConfig))
            }
            .build()

        mediaSession.isActive = true
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        val playerToUse =
            if (playerConfig.interceptPlayerActionsTriggeredExternally) createForwardingPlayer() else exoPlayer

        mediaSession.setCallback(object: MediaSessionCompat.Callback() {
            // ===== PREPARE Actions (for reduced latency) =====
            override fun onPrepare() {
                Timber.tag("GVATest").d("MediaSessionCompat.Callback.onPrepare called")
                // Prepare current media if available (without playing)
                if (exoPlayer.mediaItemCount > 0) {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = false
                }
            }
            
            override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                Timber.tag("GVATest").d("MediaSessionCompat.Callback.onPrepareFromMediaId called: %s", mediaId)
                // PREPARE: Load media and prepare, but don't play
                mediaSessionCallback.handlePrepareFromMediaId(mediaId, extras)
            }
            
            override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
                Timber.tag("GVATest").d("MediaSessionCompat.Callback.onPrepareFromSearch called: %s", query)
                // PREPARE: Load media and prepare, but don't play
                mediaSessionCallback.handlePrepareFromSearch(query, extras)
            }
            
            // ===== PLAY Actions =====
            override fun onPlay() {
                playerToUse.play()
            }
            
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                Timber.tag("GVATest").d("playing from mediaID: %s", mediaId)
                mediaSessionCallback.handlePlayFromMediaId(mediaId, extras)
            }

            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                super.onPlayFromSearch(query, extras)
                Timber.tag("GVATest").d("playing from query: %s", query)
                mediaSessionCallback.handlePlayFromSearch(query, extras)
            }
            
            // ===== Playback Control Actions =====
            override fun onPause() {
                playerToUse.pause()
            }

            override fun onStop() {
                playerToUse.stop()
            }
            
            // ===== Queue Navigation Actions =====
            override fun onSkipToNext() {
                try {
                    if (exoPlayer.hasNextMediaItem()) {
                        playerToUse.seekToNext()
                    } else {
                        // No next item - report error for Assistant recognition
                        setPlaybackStateError(
                            PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE,
                            "No next item available"
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in onSkipToNext")
                    setPlaybackStateError(
                        PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        "Failed to skip to next: ${e.message}"
                    )
                }
            }

            override fun onSkipToPrevious() {
                try {
                    if (exoPlayer.hasPreviousMediaItem()) {
                        playerToUse.seekToPrevious()
                    } else {
                        // No previous item - report error for Assistant recognition
                        setPlaybackStateError(
                            PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE,
                            "No previous item available"
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in onSkipToPrevious")
                    setPlaybackStateError(
                        PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        "Failed to skip to previous: ${e.message}"
                    )
                }
            }
            
            // https://stackoverflow.com/questions/53837783/selecting-media-item-in-android-auto-queue-does-nothing
            override fun onSkipToQueueItem(id: Long) {
                try {
                    val index = id.toInt()
                    if (index >= 0 && index < exoPlayer.mediaItemCount) {
                        mediaSessionCallback.handleSkipToQueueItem(id)
                    } else {
                        // Invalid queue index - report error
                        setPlaybackStateError(
                            PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                            "Invalid queue item index: $index"
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in onSkipToQueueItem")
                    setPlaybackStateError(
                        PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        "Failed to skip to queue item: ${e.message}"
                    )
                }
            }
            
            // ===== Seek Actions =====
            override fun onSeekTo(pos: Long) {
                try {
                    val duration = exoPlayer.duration
                    // Check if position is valid (duration might be C.TIME_UNSET if not loaded)
                    if (pos >= 0 && (duration == C.TIME_UNSET || pos <= duration)) {
                        playerToUse.seekTo(pos)
                    } else if (duration != C.TIME_UNSET) {
                        // Invalid seek position (only if duration is known) - report error
                        setPlaybackStateError(
                            PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                            "Invalid seek position: $pos (duration: $duration)"
                        )
                    } else {
                        // Duration unknown, but position is non-negative - allow seek
                        playerToUse.seekTo(pos)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in onSeekTo")
                    setPlaybackStateError(
                        PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        "Failed to seek: ${e.message}"
                    )
                }
            }

            override fun onFastForward() {
                playerToUse.seekForward()
            }

            override fun onRewind() {
                playerToUse.seekBack()
            }
            
            // ===== Rating Actions =====
            override fun onSetRating(rating: RatingCompat?) {
                if (rating == null) return
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.RATING(
                        rating, null
                    )
                )
            }

            override fun onSetRating(rating: RatingCompat?, extras: Bundle?) {
                if (rating == null) return
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.RATING(
                        rating,
                        extras
                    )
                )
            }
            
            // ===== Custom Actions =====
            // see NotificationManager.kt. onRewind, onFastForward and onStop do not trigger.
            override fun onCustomAction(action: String?, extras: Bundle?) {
                when (action) {
                    NotificationManager.REWIND -> playerToUse.seekBack()
                    NotificationManager.FORWARD -> playerToUse.seekForward()
                    NotificationManager.STOP-> playerToUse.stop()
                }
            }
        })


        notificationManager = NotificationManager(
            context,
            playerToUse,
            mediaSession,
            mediaSessionConnector,
            notificationEventHolder,
            playerEventHolder
        )

        exoPlayer.addListener(PlayerListener())

        scope.launch {
            // Whether ExoPlayer should manage audio focus for us automatically
            // see https://medium.com/google-exoplayer/easy-audio-focus-with-exoplayer-a2dcbbe4640e
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(
                    when (playerConfig.audioContentType) {
                        AudioContentType.MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
                        AudioContentType.SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
                        AudioContentType.SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
                        AudioContentType.MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
                        AudioContentType.UNKNOWN -> C.AUDIO_CONTENT_TYPE_UNKNOWN
                    }
                )
                .build();
            exoPlayer.setAudioAttributes(audioAttributes, playerConfig.handleAudioFocus);
            mediaSessionConnector.setPlayer(playerToUse)
            mediaSessionConnector.setMediaMetadataProvider {
                notificationManager.getMediaMetadataCompat()
            }
            
            // Set PlaybackPreparer to handle PREPARE actions for reduced latency
            mediaSessionConnector.setPlaybackPreparer(object : MediaSessionConnector.PlaybackPreparer {
                override fun getSupportedPrepareActions(): Long {
                    // Return bitmask of supported prepare actions
                    return PlaybackStateCompat.ACTION_PREPARE or
                           PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                           PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
                }

                override fun onCommand(
                    player: Player,
                    command: String,
                    extras: Bundle?,
                    cb: ResultReceiver?
                ): Boolean {
                    // Handle custom commands if needed
                    // Return false to indicate command was not handled
                    return false
                }

                override fun onPrepare(playWhenReady: Boolean) {
                    Timber.tag("GVATest").d("onPrepare called, playWhenReady: $playWhenReady")
                    // Prepare current media if available (without automatically playing)
                    if (exoPlayer.mediaItemCount > 0) {
                        exoPlayer.prepare()
                        // Only set playWhenReady if explicitly requested
                        exoPlayer.playWhenReady = playWhenReady
                    }
                }

                override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
                    Timber.tag("GVATest").d("onPrepareFromMediaId called: $mediaId, playWhenReady: $playWhenReady")
                    // PREPARE: Always prepare without playing (ignore playWhenReady from MediaSessionConnector)
                    mediaSessionCallback.handlePrepareFromMediaId(mediaId, extras)
                }

                override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
                    Timber.tag("GVATest").d("onPrepareFromSearch called: $query, playWhenReady: $playWhenReady")
                    // PREPARE: Always prepare without playing (ignore playWhenReady from MediaSessionConnector)
                    mediaSessionCallback.handlePrepareFromSearch(query, extras)
                }

                override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
                    Timber.tag("GVATest").d("onPrepareFromUri called: $uri, playWhenReady: $playWhenReady")
                    // Not implemented - app uses media IDs and search, not direct URIs
                }
            })
        }

        playerEventHolder.updateAudioPlayerState(AudioPlayerState.IDLE)
    }


    public fun getMediaSessionToken(): MediaSessionCompat.Token {
        return mediaSession.sessionToken
    }

    /**
     * Sets PlaybackState error for Google Assistant recognition.
     * Use this when actions fail or content is not available.
     * 
     * @param errorCode PlaybackStateCompat error code (e.g., ERROR_CODE_NOT_SUPPORTED, ERROR_CODE_APP_ERROR)
     * @param errorMessage User-readable error message
     */
    public fun setPlaybackStateError(errorCode: Int, errorMessage: String) {
        val playbackState = PlaybackStateCompat.Builder()
            .setState(
                PlaybackStateCompat.STATE_ERROR,
                exoPlayer.currentPosition,
                0f // playback speed
            )
            .setErrorMessage(errorCode, errorMessage)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    /**
     * Clears PlaybackState error and restores normal playback state.
     * Call this when error is resolved and playback can continue.
     */
    public fun clearPlaybackStateError() {
        // MediaSessionConnector will automatically update PlaybackState based on ExoPlayer state
        // No explicit clearing needed - normal state updates will override error state
    }

    /**
     * Set the PendingIntent for the Activity that should be launched when the user interacts with the media session.
     * This allows Google Assistant to launch the Activity when needed, rather than the service launching it directly.
     * 
     * @param pendingIntent The PendingIntent for the Activity to launch (typically MainActivity)
     */
    public fun setSessionActivity(pendingIntent: PendingIntent) {
        mediaSession.setSessionActivity(pendingIntent)
    }

    private fun createForwardingPlayer(): ForwardingPlayer {
        return object : ForwardingPlayer(exoPlayer) {
            override fun play() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PLAY)
            }

            override fun pause() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PAUSE)
            }

            override fun seekToNext() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
            }

            override fun seekToPrevious() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
            }

            override fun seekForward() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
            }

            override fun seekBack() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
            }

            override fun stop() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.SEEK(
                        positionMs
                    )
                )
            }

            override fun seekTo(positionMs: Long) {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.SEEK(
                        positionMs
                    )
                )
            }
        }
    }

    internal fun updateNotificationIfNecessary(overrideAudioItem: AudioItem? = null) {
        if (automaticallyUpdateNotificationMetadata) {
            notificationManager.overrideAudioItem = overrideAudioItem
        }
    }

    private fun setupBuffer(bufferConfig: BufferConfig): DefaultLoadControl {
        bufferConfig.apply {
            val multiplier =
                DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val minBuffer =
                if (minBuffer != null && minBuffer != 0) minBuffer else DEFAULT_MIN_BUFFER_MS
            val maxBuffer =
                if (maxBuffer != null && maxBuffer != 0) maxBuffer else DEFAULT_MAX_BUFFER_MS
            val playBuffer =
                if (playBuffer != null && playBuffer != 0) playBuffer else DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val backBuffer =
                if (backBuffer != null && backBuffer != 0) backBuffer else DEFAULT_BACK_BUFFER_DURATION_MS

            return Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playBuffer * multiplier)
                .setBackBuffer(backBuffer, false)
                .build()
        }
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     * @param playWhenReady Whether playback starts automatically.
     */
    open fun load(item: AudioItem, playWhenReady: Boolean = true) {
        exoPlayer.playWhenReady = playWhenReady
        load(item)
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     */
    open fun load(item: AudioItem) {
        val mediaSource = getMediaSourceFromAudioItem(item)
        exoPlayer.addMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    fun togglePlaying() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    var skipSilence: Boolean
        get() = exoPlayer.skipSilenceEnabled
        set(value) {
            exoPlayer.skipSilenceEnabled = value;
        }

    fun play() {
        exoPlayer.play()
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun prepare() {
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    /**
     * Stops playback, without clearing the active item. Calling this method will cause the playback
     * state to transition to AudioPlayerState.IDLE and the player will release the loaded media and
     * resources required for playback.
     */
    @CallSuper
    open fun stop() {
        playerState = AudioPlayerState.STOPPED
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
    }

    @CallSuper
    open fun clear() {
        exoPlayer.clearMediaItems()
    }

    /**
     * Pause playback whenever an item plays to its end.
     */
    fun setPauseAtEndOfItem(pause: Boolean) {
        exoPlayer.pauseAtEndOfMediaItems = pause
    }

    /**
     * Stops and destroys the player. Only call this when you are finished using the player, otherwise use [pause].
     */
    @CallSuper
    open fun destroy() {
        abandonAudioFocusIfHeld()
        stop()
        notificationManager.destroy()
        exoPlayer.release()
        cache?.release()
        cache = null
        mediaSession.isActive = false
    }

    open fun seek(duration: Long, unit: TimeUnit) {
        val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
        exoPlayer.seekTo(positionMs)
    }

    open fun seekBy(offset: Long, unit: TimeUnit) {
        val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
        exoPlayer.seekTo(positionMs)
    }

    protected fun getMediaSourceFromAudioItem(audioItem: AudioItem): MediaSource {
        val uri = Uri.parse(audioItem.audioUrl)
        val mediaItem = MediaItem.Builder()
            .setUri(audioItem.audioUrl)
            .setTag(AudioItemHolder(audioItem))
            .build()

        val userAgent =
            if (audioItem.options == null || audioItem.options!!.userAgent.isNullOrBlank()) {
                Util.getUserAgent(context, APPLICATION_NAME)
            } else {
                audioItem.options!!.userAgent
            }

        val factory: DataSource.Factory = when {
            audioItem.options?.resourceId != null -> {
                val raw = RawResourceDataSource(context)
                raw.open(DataSpec(uri))
                DataSource.Factory { raw }
            }
            isUriLocalFile(uri) -> {
                DefaultDataSourceFactory(context, userAgent)
            }
            else -> {
                val tempFactory = DefaultHttpDataSource.Factory().apply {
                    setUserAgent(userAgent)
                    setAllowCrossProtocolRedirects(true)

                    audioItem.options?.headers?.let {
                        setDefaultRequestProperties(it.toMap())
                    }
                }

                enableCaching(tempFactory)
            }
        }

        return when (audioItem.type) {
            MediaType.DASH -> createDashSource(mediaItem, factory)
            MediaType.HLS -> createHlsSource(mediaItem, factory)
            MediaType.SMOOTH_STREAMING -> createSsSource(mediaItem, factory)
            else -> createProgressiveSource(mediaItem, factory)
        }
    }

    private fun createDashSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createHlsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return HlsMediaSource.Factory(factory!!)
            .createMediaSource(mediaItem)
    }

    private fun createSsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return SsMediaSource.Factory(DefaultSsChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createProgressiveSource(
        mediaItem: MediaItem,
        factory: DataSource.Factory
    ): ProgressiveMediaSource {
        return ProgressiveMediaSource.Factory(
            factory, DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
        )
            .createMediaSource(mediaItem)
    }

    private fun enableCaching(factory: DataSource.Factory): DataSource.Factory {
        return if (cache == null || cacheConfig == null || (cacheConfig.maxCacheSize ?: 0) <= 0) {
            factory
        } else {
            CacheDataSource.Factory().apply {
                setCache(this@BaseAudioPlayer.cache!!)
                setUpstreamDataSourceFactory(factory)
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        Timber.d("Requesting audio focus...")

        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

        // Use same content type as ExoPlayer AudioAttributes to ensure consistency
        // Note: Android's AudioAttributes API doesn't have a separate CONTENT_TYPE_AUDIOBOOK.
        // Audiobooks should use CONTENT_TYPE_SPEECH since they are primarily spoken word content.
        val contentType = when (playerConfig.audioContentType) {
            AudioContentType.MUSIC -> CONTENT_TYPE_MUSIC
            AudioContentType.SPEECH -> CONTENT_TYPE_SPEECH  // Used for audiobooks, podcasts, etc.
            AudioContentType.SONIFICATION -> CONTENT_TYPE_SONIFICATION
            AudioContentType.MOVIE -> CONTENT_TYPE_MOVIE
            AudioContentType.UNKNOWN -> CONTENT_TYPE_MUSIC
        }

        focus = AudioFocusRequestCompat.Builder(AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(this)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(USAGE_MEDIA)
                    .setContentType(contentType)
                    .build()
            )
            .setWillPauseWhenDucked(playerOptions.alwaysPauseOnInterruption)
            .build()

        val result: Int = if (manager != null && focus != null) {
            AudioManagerCompat.requestAudioFocus(manager, focus!!)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        Timber.d("Abandoning audio focus...")

        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

        val result: Int = if (manager != null && focus != null) {
            AudioManagerCompat.abandonAudioFocusRequest(manager, focus!!)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        hasAudioFocus = (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Timber.d("Audio focus changed")
        val isPermanent = focusChange == AUDIOFOCUS_LOSS
        val isPaused = when (focusChange) {
            AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> playerOptions.alwaysPauseOnInterruption
            else -> false
        }
        if (!playerConfig.handleAudioFocus) {
            if (isPermanent) abandonAudioFocusIfHeld()

            val isDucking = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    && !playerOptions.alwaysPauseOnInterruption
            if (isDucking) {
                volumeMultiplier = 0.5f
                wasDucking = true
            } else if (wasDucking) {
                volumeMultiplier = 1f
                wasDucking = false
            }
        }

        playerEventHolder.updateOnAudioFocusChanged(isPaused, isPermanent)
    }

    companion object {
        const val APPLICATION_NAME = "react-native-track-player"
    }

    inner class PlayerListener : Listener {
        /**
         * Called when there is metadata associated with the current playback time.
         */
        override fun onMetadata(metadata: Metadata) {
            playerEventHolder.updateOnTimedMetadata(metadata)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            playerEventHolder.updateOnCommonMetadata(mediaMetadata)
        }

        /**
         * A position discontinuity occurs when the playing period changes, the playback position
         * jumps within the period currently being played, or when the playing period has been
         * skipped or removed.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            this@BaseAudioPlayer.oldPosition = oldPosition.positionMs

            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.AUTO(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK_FAILED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_REMOVE -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.QUEUE_CHANGED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SKIPPED_PERIOD(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_INTERNAL -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )
            }
        }

        /**
         * Called when playback transitions to a media item or starts repeating a media item
         * according to the current repeat mode. Note that this callback is also called when the
         * playlist becomes non-empty or empty as a consequence of a playlist change.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.AUTO(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.QUEUE_CHANGED(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.REPEAT(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.SEEK_TO_ANOTHER_AUDIO_ITEM(oldPosition)
                )
            }

            updateNotificationIfNecessary()
        }

        /**
         * Called when the value returned from Player.getPlayWhenReady() changes.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playerEventHolder.updatePlayWhenReadyChange(PlayWhenReadyChangeData(playWhenReady, pausedBecauseReachedEnd))
        }

        /**
         * The generic onEvents callback provides access to the Player object and specifies the set
         * of events that occurred together. It’s always called after the callbacks that correspond
         * to the individual events.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            // Note that it is necessary to set `playerState` in order, since each mutation fires an
            // event.
            for (i in 0 until events.size()) {
                when (events[i]) {
                    Player.EVENT_PLAYBACK_STATE_CHANGED -> {
                        val state = when (player.playbackState) {
                            Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
                            Player.STATE_READY -> AudioPlayerState.READY
                            Player.STATE_IDLE ->
                                // Avoid transitioning to idle from error or stopped
                                if (
                                    playerState == AudioPlayerState.ERROR ||
                                    playerState == AudioPlayerState.STOPPED
                                )
                                    null
                                else
                                    AudioPlayerState.IDLE
                            Player.STATE_ENDED ->
                                if (player.mediaItemCount > 0) AudioPlayerState.ENDED
                                else AudioPlayerState.IDLE
                            else -> null // noop
                        }
                        if (state != null && state != playerState) {
                            playerState = state
                        }
                    }
                    Player.EVENT_MEDIA_ITEM_TRANSITION -> {
                        playbackError = null
                        if (currentItem != null) {
                            playerState = AudioPlayerState.LOADING
                            if (isPlaying) {
                                playerState = AudioPlayerState.READY
                                playerState = AudioPlayerState.PLAYING
                            }
                        }
                    }
                    Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
                        if (!player.playWhenReady && playerState != AudioPlayerState.STOPPED) {
                            playerState = AudioPlayerState.PAUSED
                        }
                    }
                    Player.EVENT_IS_PLAYING_CHANGED -> {
                        if (player.isPlaying) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val _playbackError = PlaybackError(
                error.errorCodeName
                    .replace("ERROR_CODE_", "")
                    .lowercase(Locale.getDefault())
                    .replace("_", "-"),
                error.message
            )
            playerEventHolder.updatePlaybackError(_playbackError)
            playbackError = _playbackError
            playerState = AudioPlayerState.ERROR

            // Update PlaybackState with error code and message for Assistant recognition
            updatePlaybackStateWithError(error)
        }

        /**
         * Maps ExoPlayer 2.19.1 error codes to PlaybackStateCompat error codes and updates MediaSession.
         * This enables Google Assistant to recognize and handle errors appropriately.
         */
        private fun updatePlaybackStateWithError(error: PlaybackException) {
            val errorCode = mapExoPlayerErrorToPlaybackStateError(error)
            val errorMessage = error.message ?: "Playback error occurred"

            val playbackState = PlaybackStateCompat.Builder()
                .setState(
                    PlaybackStateCompat.STATE_ERROR,
                    exoPlayer.currentPosition,
                    0f // playback speed
                )
                .setErrorMessage(errorCode, errorMessage)
                .build()

            mediaSession.setPlaybackState(playbackState)
        }

        /**
         * Maps ExoPlayer 2.19.1 PlaybackException error codes to PlaybackStateCompat error codes.
         * This mapping enables Google Assistant to recognize error types and provide appropriate feedback.
         * Uses integer error codes to avoid compilation issues with missing constants.
         */
        private fun mapExoPlayerErrorToPlaybackStateError(error: PlaybackException): Int {
            return when (error.errorCode) {
                // Network/IO errors -> APP_ERROR
                // ERROR_CODE_IO_UNSPECIFIED = 2000
                2000,
                // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
                2001,
                // ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
                2002,
                // ERROR_CODE_IO_FILE_NOT_FOUND = 2005
                2005,
                // ERROR_CODE_IO_NO_PERMISSION = 2006
                2006,
                // ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED = 2007
                2007 -> {
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR
                }

                // Timeout errors -> APP_ERROR
                // ERROR_CODE_TIMEOUT = 1003
                1003 -> {
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR
                }

                // Parsing errors -> APP_ERROR
                // ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3001
                3001,
                // ERROR_CODE_PARSING_MANIFEST_MALFORMED = 3002
                3002,
                // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3003
                3003,
                // ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED = 3004
                3004 -> {
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR
                }

                // Decoding errors -> APP_ERROR
                // ERROR_CODE_DECODER_INIT_FAILED = 4001
                4001,
                // ERROR_CODE_DECODER_QUERY_FAILED = 4002
                4002,
                // ERROR_CODE_DECODING_FAILED = 4003
                4003,
                // ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
                4004,
                // ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005
                4005 -> {
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR
                }

                // Audio track errors -> APP_ERROR
                // ERROR_CODE_AUDIO_TRACK_INIT_FAILED = 5001
                5001,
                // ERROR_CODE_AUDIO_TRACK_WRITE_FAILED = 5002
                5002 -> {
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR
                }

                // DRM errors -> APP_ERROR
                // ERROR_CODE_DRM_SCHEME_UNSUPPORTED = 6001
                6001,
                // ERROR_CODE_DRM_PROVISIONING_FAILED = 6002
                6002,
                // ERROR_CODE_DRM_CONTENT_ERROR = 6003
                6003,
                // ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED = 6004
                6004,
                // ERROR_CODE_DRM_DISALLOWED_OPERATION = 6005
                6005,
                // ERROR_CODE_DRM_SYSTEM_ERROR = 6006
                6006,
                // ERROR_CODE_DRM_DEVICE_REVOKED = 6007
                6007 -> {
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR
                }

                // Unknown/unspecified errors -> UNKNOWN_ERROR
                // ERROR_CODE_UNSPECIFIED = 1000
                1000,
                // ERROR_CODE_REMOTE_ERROR = 1001
                1001,
                // ERROR_CODE_FAILED_RUNTIME_CHECK = 1004
                1004 -> {
                    PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR
                }

                // Default fallback for any unmapped error codes
                else -> {
                    PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR
                }
            }
        }
    }
}
