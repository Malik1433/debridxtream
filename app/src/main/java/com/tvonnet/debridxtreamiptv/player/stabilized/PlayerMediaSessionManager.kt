package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * The platform's view of "something is playing" (2026-09-02).
 *
 * Until this existed the app had no MediaSession at all: the remote's play/pause was only
 * understood while the player window held the key, the Assistant's "pause" went nowhere, and
 * the launcher's Now Playing knew nothing. A session bound to the live player fixes all three
 * without touching how playback itself works — the session is a *mirror* of the player, and
 * the player is still owned, tuned and released by [BasePlayerFragment].
 *
 * **Why the framework `android.media.session.MediaSession` and not media3-session.** media3
 * 1.5.0's legacy stub asserts the caller's package name is non-empty, and on Fire OS a media
 * key dispatched by the system arrives with an EMPTY one — `IllegalArgumentException:
 * packageName should be nonempty`, a hard crash of the player on the first remote pause,
 * reproduced on the Fire TV `.64` (see [PlayerMediaSessionManagerTest] for the contract that
 * replaced it). The framework session has no such assertion, needs no dependency, and
 * everything we want (media keys, Assistant transport, Now Playing) is what it was built for.
 *
 * Lifetime rule, and it is the whole contract: **the session exists exactly while this screen
 * holds a live player.** [bind] after every point that gives the screen a player (cold start,
 * Live adopt), [unbind] BEFORE every point that takes one away (release, black-video rebuild,
 * Live hand-back). A session left behind on a handed-back Live player would keep answering
 * the remote for a player this screen no longer owns.
 *
 * Deliberately no MediaSessionService: that is the background-playback + notification model,
 * a different product decision. This is foreground playback made a proper platform citizen.
 */
internal class PlayerMediaSessionManager(
    private val context: Context,
    private val sessionFactory: (Context) -> PlatformSession = { FrameworkSession(it) },
) {

    /** The slice of [MediaSession] this class uses — swapped for a fake under test. */
    internal interface PlatformSession {
        fun setActive(active: Boolean)
        fun setPlaybackState(state: PlaybackState)
        fun setMetadata(metadata: MediaMetadata)
        fun setCallback(callback: MediaSession.Callback?)
        fun setSessionActivity(intent: PendingIntent?)
        fun release()
    }

    private var session: PlatformSession? = null
    private var player: Player? = null
    private var title: String? = null
    private var listener: Player.Listener? = null

    /**
     * Binds [player]; a session already bound to another player is replaced, never stacked.
     * [title] is what Now Playing shows — the stream carries no metadata of its own, so the
     * screen passes the title it was launched with (channel name, movie, episode).
     */
    fun bind(player: Player, title: String? = null) {
        if (this.player === player && session != null) {
            if (title != this.title) { this.title = title; publish() }
            return
        }
        unbind() // clears title too - so it is set AFTER, never before
        this.title = title
        val s = sessionFactory(context)
        s.setCallback(TransportCallback(player))
        s.setSessionActivity(returnToPlayerIntent())
        s.setActive(true)
        session = s
        this.player = player
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
        }
        listener = l
        player.addListener(l)
        publish()
    }

    /** Releases the session. Safe to call when nothing is bound, and to call twice. */
    fun unbind() {
        listener?.let { player?.removeListener(it) }
        listener = null
        player = null
        title = null
        session?.let {
            it.setCallback(null)
            it.setActive(false)
            it.release()
        }
        session = null
    }

    fun isBound(): Boolean = session != null

    /** Mirrors the player into the session: state, position, and the title for Now Playing. */
    private fun publish() {
        val p = player ?: return
        val s = session ?: return
        s.setPlaybackState(playbackStateFor(p))
        val title = title ?: p.mediaMetadata.title?.toString()
        if (!title.isNullOrBlank()) {
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, p.duration.takeIf { it > 0 } ?: -1L)
                    .build()
            )
        }
    }

    /**
     * What the system may ask of us, mapped straight onto the player. NEXT/PREVIOUS are
     * deliberately absent: while the player is foreground those keys already zap through
     * [PlayerInputRouter], and a background "next" on a single-item VOD would be a no-op
     * dressed up as a feature. STOP is a pause — the session never releases a player it
     * does not own.
     */
    private class TransportCallback(private val player: Player) : MediaSession.Callback() {
        override fun onPlay() { player.play() }
        override fun onPause() { player.pause() }
        override fun onStop() { player.pause() }
        override fun onSeekTo(pos: Long) { if (player.isCurrentMediaItemSeekable) player.seekTo(pos) }
        override fun onFastForward() { if (player.isCurrentMediaItemSeekable) player.seekForward() }
        override fun onRewind() { if (player.isCurrentMediaItemSeekable) player.seekBack() }
    }

    /**
     * The Now Playing card / Assistant "open" lands back on the player. `singleTop` +
     * reorder-to-front resumes the running instance; no extras, so nothing re-tunes.
     */
    private fun returnToPlayerIntent(): PendingIntent {
        val intent = Intent(context, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            /* requestCode= */ 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** The real thing: a framework session on the main looper. */
    private class FrameworkSession(context: Context) : PlatformSession {
        private val session = MediaSession(context, TAG)
        private val mainHandler = Handler(Looper.getMainLooper())
        override fun setActive(active: Boolean) = session.setActive(active)
        override fun setPlaybackState(state: PlaybackState) = session.setPlaybackState(state)
        override fun setMetadata(metadata: MediaMetadata) = session.setMetadata(metadata)
        override fun setCallback(callback: MediaSession.Callback?) = session.setCallback(callback, mainHandler)
        override fun setSessionActivity(intent: PendingIntent?) = session.setSessionActivity(intent)
        override fun release() = session.release()
    }

    internal companion object {
        const val TAG = "PlayerMediaSession"

        private const val TRANSPORT_ACTIONS =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP

        private const val SEEK_ACTIONS =
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND

        /** Pure: the session state a player in this condition should advertise. */
        internal fun playbackStateFor(player: Player): PlaybackState = playbackStateFor(
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
            positionMs = player.currentPosition,
            seekable = player.isCurrentMediaItemSeekable,
            speed = player.playbackParameters.speed,
        )

        internal fun playbackStateFor(
            playbackState: Int,
            playWhenReady: Boolean,
            positionMs: Long,
            seekable: Boolean,
            speed: Float,
        ): PlaybackState {
            val state = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
                Player.STATE_READY -> if (playWhenReady) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
                Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
                else -> PlaybackState.STATE_NONE
            }
            val actions = TRANSPORT_ACTIONS or (if (seekable) SEEK_ACTIONS else 0L)
            val reportedSpeed = if (state == PlaybackState.STATE_PLAYING) speed else 0f
            return PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs, reportedSpeed)
                .build()
        }
    }
}
