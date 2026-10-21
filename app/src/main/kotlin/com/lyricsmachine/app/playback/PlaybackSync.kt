package com.lyricsmachine.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lyricsmachine.core.playback.PlaybackPosition
import com.lyricsmachine.core.playback.TimingLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Mode 1: plays the song's audio with Media3 ExoPlayer and maps the playback position to the current
 * line/word through [lookup]. [position] is polled at ~30 Hz on Main while playing and once after each seek
 * or pause.
 *
 * Construct, use and [close] on the main thread (ExoPlayer is bound to the thread that built it).
 * Extras for the Sing UI: [positionMs] and [durationMs] (null until known) for a progress bar, and [error]
 * (the player's message once [Player.Listener.onPlayerError] fires, e.g. the file can't be opened).
 */
class PlaybackSync(context: Context, audioUri: Uri, private val lookup: TimingLookup) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .build()
    private var pollJob: Job? = null
    private var closed = false

    private val _position = MutableStateFlow(lookup.at(0))
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow<Long?>(null)
    val durationMs: StateFlow<Long?> = _durationMs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startPolling() else stopPolling()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                _durationMs.value = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
            }
            updatePosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error.message ?: error.errorCodeName
        }
    }

    init {
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(audioUri))
        player.prepare()
    }

    fun play() {
        if (closed) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() {
        if (closed) return
        player.pause()
        updatePosition()
    }

    /** Seeks to the saved start of [lineIndex]; does nothing when that line has no timing. */
    fun seekToLine(lineIndex: Int) {
        if (closed) return
        val startMs = lookup.startOfLine(lineIndex) ?: return
        player.seekTo(startMs)
        updatePosition()
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        player.removeListener(listener)
        player.release()
        _isPlaying.value = false
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                updatePosition()
                delay(POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        updatePosition()
    }

    private fun updatePosition() {
        if (closed) return
        val ms = player.currentPosition
        _positionMs.value = ms
        _position.value = lookup.at(ms)
    }

    private companion object {
        const val POLL_MS = 33L // ~30 Hz
    }
}
