package dev.kiamenu.api.ui

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Plays an app's sound through the car's speakers. On these head units an ordinary app is
 * silent: Kia's AudioFlinger mutes every music track whose audio session isn't the one the
 * current source owns ("track_exclusive"), and the amplifier stays muted until the source's
 * app unmutes it. So an app has to become a source, the way Kia's own players do:
 * audio focus with Kia's "music streaming" mode, unmute, and play on that mode's session.
 * The knob and the volume bar then work as for any source, and releasing focus gives the
 * speakers back to whatever played before. See docs/audio.md.
 *
 * ```
 * val audio = KiaAudio.claim(context, listener)   // in onResume, or when playback starts
 * AudioTrack(AudioManager.STREAM_MUSIC, ..., audio.session())
 * // or mediaPlayer.audioSessionId = audio.session() before setDataSource
 * audio.release()                                 // in onPause, or when playback stops
 * ```
 *
 * Kia's calls (a hidden requestAudioFocus with a mode, MicomAudioManager in automotive.jar,
 * on every app's boot classpath) aren't in the SDK, so this reaches them by reflection. Off a
 * Kia head unit, or if they fail, it falls back to plain audio focus and a session of 0
 * (any session), so the same code runs on a phone.
 */
class KiaAudio private constructor(context: Context, listener: AudioManager.OnAudioFocusChangeListener?) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = listener ?: AudioManager.OnAudioFocusChangeListener { }
    private var held = false
    private val kia = claimKia(context)

    init {
        if (!kia) {
            held = am.requestAudioFocus(
                this.listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /** The audio session every track and player must use to be heard. */
    fun session(): Int = if (kia) SESSION_MUSIC_STREAMING else 0

    /** True if the speakers were claimed (focus granted). */
    fun held(): Boolean = held

    /** Gives the speakers back; the previous source (radio, Android Auto...) resumes. */
    fun release() {
        if (held) am.abandonAudioFocus(listener)
        held = false
    }

    private fun claimKia(context: Context): Boolean {
        // false: not a Kia head unit (no hidden mode variant or no automotive.jar)
        val claimed = probeHidden(false) { requestKiaFocus(context) }
        if (!claimed) release()
        return claimed
    }

    private fun requestKiaFocus(context: Context): Boolean {
        val focus = AudioManager::class.java.getMethod(
            "requestAudioFocus",
            AudioManager.OnAudioFocusChangeListener::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val micom = Class.forName("com.hkmc.media.MicomAudioManager")
        val r = focus.invoke(
            am,
            listener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
            MODE_MUSIC_STREAMING,
        ) as Int
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "music streaming focus refused ($r)")
            // a Kia unit all the same: keep the Kia session, held stays false
        } else {
            held = true
            val m = micom.getConstructor(Context::class.java).newInstance(context)
            micom.getMethod("setMute", Int::class.javaPrimitiveType).invoke(m, UNMUTE_AV)
        }
        return true
    }

    companion object {
        private const val TAG = "KiaAudio"

        /** ModeConstants.ModeType.MUSICSTREAMING: a source slot no Kia app owns on US units. */
        const val MODE_MUSIC_STREAMING = 0x82

        /** AudioManager.SESSION_TRACK_* for that mode: only tracks with this session are heard. */
        const val SESSION_MUSIC_STREAMING = 29

        /** MicomAudioManager.setMute: 3 unmutes the AV channel (a mode change mutes it). */
        private const val UNMUTE_AV = 3

        /**
         * Makes this app the car's audio source. The listener hears focus changes as usual:
         * on AUDIOFOCUS_LOSS (the driver picked the radio, a call came in) stop playing and
         * call [release].
         */
        @JvmStatic
        fun claim(context: Context, listener: AudioManager.OnAudioFocusChangeListener?): KiaAudio =
            KiaAudio(context.applicationContext, listener)
    }
}
