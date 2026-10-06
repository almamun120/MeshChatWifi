package com.meshchat.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/** Shared EGL context + the two video tracks the call screen draws. One call at a time, so a singleton is enough. */
object VideoHub {
    val egl: EglBase by lazy { EglBase.create() }

    private val _local = MutableStateFlow<VideoTrack?>(null)
    private val _remote = MutableStateFlow<VideoTrack?>(null)
    private val _frontCamera = MutableStateFlow(true)

    val local: StateFlow<VideoTrack?> = _local
    val remote: StateFlow<VideoTrack?> = _remote
    /** True while the front camera is used: the self-view is mirrored then. */
    val frontCamera: StateFlow<Boolean> = _frontCamera

    fun setLocal(t: VideoTrack?) { _local.value = t }
    fun setRemote(t: VideoTrack?) { _remote.value = t }
    fun setFront(front: Boolean) { _frontCamera.value = front }
    fun clear() {
        _local.value = null
        _remote.value = null
        _frontCamera.value = true
    }
}
