package com.unshoo.pixelmusic.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Wraps the session-facing player with Mochy-Music transport transitions:
 * - When manually skipping (next/previous) or picking a song, smoothly eases the outgoing
 *   track out (fade out), switches cleanly once volume is 0, awaits the incoming song to start
 *   playing, and eases it in (fade in).
 * - Avoids clashing manual crossfades / simultaneous playback on skip.
 * - Double-tapping next/previous or seeking immediately cancels active fades and acts at once with zero lag.
 * - When pausing, eases the volume down to 0 before pausing.
 * - When playing/resuming, starts playback and eases volume up to target level.
 * - Preserves ReplayGain and user volume target without resetting or clipping.
 */
@OptIn(UnstableApi::class)
class FadingPlayer(
    val innerPlayer: Player,
    private val scope: CoroutineScope,
) : ForwardingPlayer(innerPlayer) {

    private enum class Phase { Idle, Pausing, Skipping, Resuming }

    @Volatile private var phase = Phase.Idle
    @Volatile private var fadeJob: Job? = null

    // Volume the player should sit at when no fade is running (e.g. set by ReplayGain or user).
    @Volatile private var logicalVolume: Float = innerPlayer.volume
    private var level: Float = 1f
    private var heard: Float = 1f

    override fun setVolume(volume: Float) {
        logicalVolume = volume
        if (phase == Phase.Idle) {
            super.setVolume(volume)
        }
    }

    override fun getVolume(): Float {
        return if (phase != Phase.Idle) logicalVolume else super.getVolume()
    }

    private fun applyLevel(player: Player) {
        player.volume = (logicalVolume * level).coerceIn(0f, 1f)
    }

    private fun currentLevel(): Float = if (phase == Phase.Idle) 1f else heard

    private fun shouldFade(player: Player): Boolean =
        player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE

    private fun stopFade(keepVolume: Boolean = false) {
        fadeJob?.cancel()
        fadeJob = null
        phase = Phase.Idle
        if (!keepVolume) {
            level = 1f
            heard = 1f
            applyLevel(innerPlayer)
        }
    }

    fun cancel() = stopFade(keepVolume = false)

    private suspend fun runFade(player: Player, lengthMs: Float, step: (Float) -> Boolean): Boolean {
        val start = System.nanoTime()
        while (true) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000f
            val p = (elapsedMs / lengthMs).coerceIn(0f, 1f)
            if (!step(p)) return false
            applyLevel(player)
            if (p >= 1f) return true
            delay(FRAME_MS)
        }
    }

    private suspend fun awaitNewSong(player: Player, oldSongId: String?, oldIndex: Int) {
        var waited = 0L
        while (waited < START_WAIT_MS) {
            val hasChanged = player.isPlaying && (
                player.currentMediaItemIndex != oldIndex ||
                player.currentMediaItem?.mediaId != oldSongId ||
                player.currentPosition < 1_000L
            )
            if (hasChanged) break
            delay(FRAME_MS)
            waited += FRAME_MS
        }
    }

    private fun fadeIn(player: Player, lengthMs: Float, from: Float) {
        phase = Phase.Resuming
        fadeJob = scope.launch(Dispatchers.Main) {
            runFade(player, lengthMs) { p ->
                level = from + (1f - from) * smoothstep(p)
                heard = level
                true
            }
            level = 1f
            applyLevel(player)
            phase = Phase.Idle
        }
    }

    private fun switchSong(keepIfInterrupted: Boolean, change: (Player) -> Unit) {
        val player = innerPlayer
        // If not playing, or already in skipping phase (user pressed skip again quickly), or scope inactive:
        if (!player.playWhenReady || !shouldFade(player) || phase == Phase.Skipping || !scope.isActive) {
            stopFade()
            change(player)
            return
        }

        val from = currentLevel()
        stopFade(keepVolume = true)
        val outMs = SKIP_OUT_MAX_MS
        val inMs = SKIP_IN_MIN_MS
        val songId = player.currentMediaItem?.mediaId
        val index = player.currentMediaItemIndex
        phase = Phase.Skipping

        fadeJob = scope.launch(Dispatchers.Main) {
            val done = runFade(player, outMs) { p ->
                if (player.currentMediaItem?.mediaId != songId) return@runFade false
                heard = from * (1f - smoothstep(p))
                level = heard
                true
            }
            if (!done) {
                stopFade()
                if (keepIfInterrupted) change(player)
                return@launch
            }
            level = 0f
            heard = 0f
            applyLevel(player)
            change(player)
            awaitNewSong(player, songId, index)
            phase = Phase.Resuming
            fadeIn(player, inMs, from = 0f)
        }
    }

    private fun doSkip(player: Player, previous: Boolean) {
        if (previous) {
            if (player.currentPosition > RESTART_AFTER_MS || !player.hasPreviousMediaItem()) {
                player.seekTo(0)
            } else {
                player.seekToPreviousMediaItem()
            }
        } else {
            player.seekToNext()
        }
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            player.prepare()
        }
        player.playWhenReady = true
    }

    override fun seekToNext() = switchSong(keepIfInterrupted = false) { doSkip(it, previous = false) }

    override fun seekToNextMediaItem() = seekToNext()

    override fun seekToPrevious() = switchSong(keepIfInterrupted = false) { doSkip(it, previous = true) }

    override fun seekToPreviousMediaItem() = seekToPrevious()

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (mediaItemIndex == innerPlayer.currentMediaItemIndex) {
            cancel()
            super.seekToDefaultPosition(mediaItemIndex)
        } else {
            switchSong(keepIfInterrupted = true) {
                it.seekToDefaultPosition(mediaItemIndex)
                if (it.playbackState == Player.STATE_IDLE || it.playbackState == Player.STATE_ENDED) {
                    it.prepare()
                }
                it.playWhenReady = true
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        cancel()
        super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == innerPlayer.currentMediaItemIndex) {
            cancel()
            super.seekTo(mediaItemIndex, positionMs)
        } else {
            switchSong(keepIfInterrupted = true) {
                it.seekTo(mediaItemIndex, positionMs)
                if (it.playbackState == Player.STATE_IDLE || it.playbackState == Player.STATE_ENDED) {
                    it.prepare()
                }
                it.playWhenReady = true
            }
        }
    }

    override fun play() {
        val player = innerPlayer
        val resumingFrom = if (phase == Phase.Pausing) currentLevel() else 0f
        if (phase != Phase.Pausing && player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            stopFade()
            return
        }
        if (!shouldFade(player) || !scope.isActive) {
            stopFade()
            readyToPlay(player)
            player.playWhenReady = true
            return
        }
        stopFade(keepVolume = true)
        val length = if (resumingFrom > 0f) max(RESUME_FADE_MS * (1f - resumingFrom), 150f) else RESUME_FADE_MS
        level = resumingFrom
        heard = resumingFrom
        applyLevel(player)
        readyToPlay(player)
        player.playWhenReady = true
        fadeIn(player, length, resumingFrom)
    }

    override fun pause() {
        val player = innerPlayer
        if (phase == Phase.Pausing) {
            // Second press pauses immediately
            finishPause(player, rewindTo = null)
            return
        }
        if (!player.playWhenReady) {
            stopFade()
            return
        }
        if (!shouldFade(player) || !scope.isActive) {
            stopFade()
            player.pause()
            return
        }
        val from = currentLevel()
        stopFade(keepVolume = true)
        val anchor = player.currentPosition
        val songId = player.currentMediaItem?.mediaId
        phase = Phase.Pausing
        fadeJob = scope.launch(Dispatchers.Main) {
            val done = runFade(player, PAUSE_FADE_MS) { p ->
                if (player.currentMediaItem?.mediaId != songId) return@runFade false
                heard = from * (1f - smoothstep(p))
                level = heard
                true
            }
            if (!done) {
                stopFade()
                return@launch
            }
            finishPause(player, rewindTo = anchor)
        }
    }

    private fun finishPause(player: Player, rewindTo: Long?) {
        fadeJob?.cancel()
        fadeJob = null
        level = 0f
        applyLevel(player)
        player.pause()
        if (rewindTo != null && rewindTo >= 0) player.seekTo(rewindTo)
        level = 1f
        heard = 1f
        applyLevel(player)
        phase = Phase.Idle
    }

    private fun readyToPlay(player: Player) {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun stop() {
        cancel()
        super.stop()
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        cancel()
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        cancel()
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        cancel()
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        cancel()
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        cancel()
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        cancel()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    private companion object {
        const val FRAME_MS = 16L
        const val SKIP_OUT_MAX_MS = 320f
        const val SKIP_IN_MIN_MS = 280f
        const val PAUSE_FADE_MS = 300f
        const val RESUME_FADE_MS = 300f
        const val RESTART_AFTER_MS = 3_000L
        const val START_WAIT_MS = 5_000L

        fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)
    }
}
