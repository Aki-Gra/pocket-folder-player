package com.example.pocketfolderplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** MediaSessionService owns playback, so music keeps playing in the background. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    lateinit var equalizer: EqualizerEngine
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val savePosition = object : Runnable {
        override fun run() {
            persistPlayback()
            handler.postDelayed(this, 3_000L)
        }
    }

    companion object {
        // Activity and service run in the same app process. No player instance is held by the Activity.
        var active: PlaybackService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        equalizer = EqualizerEngine(this)
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.repeatMode = FolderStore.repeatMode(this)
        player.shuffleModeEnabled = FolderStore.shuffleEnabled(this)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer.attach(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                persistPlayback()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                persistPlayback()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                FolderStore.saveShuffle(this@PlaybackService, shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                FolderStore.saveRepeat(this@PlaybackService, repeatMode)
            }
        })
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
        active = this
        handler.postDelayed(savePosition, 3_000L)
    }

    private fun persistPlayback() {
        val item = player.currentMediaItem ?: return
        if (item.mediaId.isNotEmpty()) {
            FolderStore.savePlayback(this, item.mediaId, player.currentPosition)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePosition)
        persistPlayback()
        active = null
        equalizer.release()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }
}
