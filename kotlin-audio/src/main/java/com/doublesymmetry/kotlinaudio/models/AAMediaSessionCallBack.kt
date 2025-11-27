package com.doublesymmetry.kotlinaudio.models

import android.os.Bundle

interface AAMediaSessionCallBack {
    // PLAY actions
    fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?)
    fun handlePlayFromSearch(query: String?, extras: Bundle?)
    
    // PREPARE actions for reduced latency (always prepares without playing)
    fun handlePrepareFromMediaId(mediaId: String?, extras: Bundle?)
    fun handlePrepareFromSearch(query: String?, extras: Bundle?)
    
    // Queue navigation
    fun handleSkipToQueueItem(id: Long)
}