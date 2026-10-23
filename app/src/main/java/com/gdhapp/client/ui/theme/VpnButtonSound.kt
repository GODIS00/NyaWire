package com.gdhapp.client.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gdhapp.client.R
import com.gdhapp.client.i18n.AppText

/** One prepared player owned by the visible app; no overlapping effects or volume changes. */
internal class VpnButtonSound(context: Context, private val createPlayer: () -> MediaPlayer = { MediaPlayer() },
    private val onFailure: (Int) -> Unit = {}) : AutoCloseable {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) stop() }, Handler(Looper.getMainLooper())).build()
    private var player: MediaPlayer? = null
    private var pending = false
    private var released = false
    private var ownsFocus = false
    private var retriesLeft = 0
    @Volatile var isLoaded = false
        private set
    val isPlaying get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    val positionMs get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    var enabled = true
        set(value) { field = value; if (!value) stop() }

    init { prepare() }

    private fun prepare(): Boolean {
        try {
            player = createPlayer()
            player!!.let { media ->
                media.setAudioAttributes(attributes)
                media.setVolume(1f, 1f)
                media.setOnPreparedListener {
                    if (released || player !== media) return@setOnPreparedListener
                    isLoaded = true
                    if (pending) startOrPrepare()
                }
                media.setOnCompletionListener { if (player === media) abandonFocus() }
                media.setOnErrorListener { _, what, extra ->
                    if (player === media && !released) {
                        Log.w("VpnButtonSound", "MediaPlayer error $what/$extra")
                        recover(pending || ownsFocus)
                    }
                    true
                }
                context.resources.openRawResourceFd(R.raw.vpn_button).use { source ->
                    media.setDataSource(source.fileDescriptor, source.startOffset, source.length)
                }
                media.prepareAsync()
            }
            return true
        } catch (error: Exception) {
            Log.w("VpnButtonSound", "Cannot prepare connection sound", error)
            disposePlayer()
            return false
        }
    }

    fun play(): Boolean {
        if (released || !enabled) return false
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            stop()
            onFailure(R.string.sound_media_muted)
            return false
        }
        pending = true
        retriesLeft = 1
        return startOrPrepare()
    }

    private fun startOrPrepare(): Boolean {
        if (released || !enabled) { stop(); return false }
        if (player == null) {
            if (prepare()) return true
            onFailure(R.string.sound_playback_failed)
            return false
        }
        val media = player!!
        try {
            if (!ownsFocus) {
                ownsFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!ownsFocus) { pending = false; onFailure(R.string.sound_focus_unavailable); return false }
            }
            if (!isLoaded) { pending = true; return true }
            pending = false
            if (media.isPlaying) media.pause()
            media.seekTo(0)
            media.start()
            return true
        } catch (error: Exception) {
            Log.w("VpnButtonSound", "Cannot play connection sound", error)
            return recover(requested = true)
        }
    }

    /** Retry a failed tap once; future taps can always create a fresh player. */
    private fun recover(requested: Boolean): Boolean {
        disposePlayer()
        if (requested && !released && enabled && retriesLeft > 0) {
            retriesLeft--
            pending = true
            if (prepare()) return true
        }
        if (requested) onFailure(R.string.sound_playback_failed)
        return false
    }

    fun stop() {
        pending = false
        runCatching { player?.let { if (isLoaded && it.isPlaying) it.pause() } }
        abandonFocus()
    }

    private fun abandonFocus() {
        if (ownsFocus) { ownsFocus = false; audio.abandonAudioFocusRequest(focus) }
    }

    private fun disposePlayer() {
        pending = false
        isLoaded = false
        abandonFocus()
        val previous = player
        player = null
        runCatching { previous?.release() }
    }

    override fun close() {
        if (released) return
        released = true
        disposePlayer()
    }
}

@Composable
internal fun rememberVpnButtonSound(enabled: Boolean): () -> Unit {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sound = remember(context) { VpnButtonSound(context) { message ->
        Toast.makeText(context, AppText.text(message), Toast.LENGTH_SHORT).show()
    } }
    DisposableEffect(sound) {
        // This fixed half-second effect belongs to the tap. Let it finish even if an OEM
        // permission window stops the Activity before audio output has warmed up.
        onDispose { sound.close() }
    }
    SideEffect { sound.enabled = enabled }
    return { if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) sound.play(); Unit }
}
