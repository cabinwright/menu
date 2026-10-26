package dev.kiamenu.api.ui;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Plays an app's sound through the car's speakers. On these head units an ordinary app is
 * silent: Kia's AudioFlinger mutes every music track whose audio session isn't the one the
 * current source owns ("track_exclusive"), and the amplifier stays muted until the source's
 * app unmutes it. So an app has to become a source, the way Kia's own players do:
 * audio focus with Kia's "music streaming" mode, unmute, and play on that mode's session.
 * The knob and the volume bar then work as for any source, and releasing focus gives the
 * speakers back to whatever played before. See docs/audio.md.
 *
 * <pre>
 *   audio = KiaAudio.claim(context, listener);     // in onResume, or when playback starts
 *   new AudioTrack(AudioManager.STREAM_MUSIC, ..., audio.session());
 *   // or mediaPlayer.setAudioSessionId(audio.session()) before setDataSource
 *   audio.release();                               // in onPause, or when playback stops
 * </pre>
 *
 * Kia's calls (a hidden requestAudioFocus with a mode, MicomAudioManager in automotive.jar,
 * on every app's boot classpath) aren't in the SDK, so this reaches them by reflection. Off a
 * Kia head unit, or if they fail, it falls back to plain audio focus and a session of 0
 * (any session), so the same code runs on a phone.
 */
public final class KiaAudio {
    private static final String TAG = "KiaAudio";
    /** ModeConstants.ModeType.MUSICSTREAMING: a source slot no Kia app owns on US units. */
    static final int MODE_MUSIC_STREAMING = 0x82;
    /** AudioManager.SESSION_TRACK_* for that mode: only tracks with this session are heard. */
    static final int SESSION_MUSIC_STREAMING = 29;
    /** MicomAudioManager.setMute: 3 unmutes the AV channel (a mode change mutes it). */
    private static final int UNMUTE_AV = 3;

    private final AudioManager am;
    private final AudioManager.OnAudioFocusChangeListener listener;
    private final boolean kia;
    private boolean held;

    private KiaAudio(Context context, AudioManager.OnAudioFocusChangeListener listener) {
        this.am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener != null ? listener : new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int change) {
            }
        };
        this.kia = claimKia(context);
        if (!kia) {
            held = am.requestAudioFocus(this.listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    /**
     * Makes this app the car's audio source. The listener hears focus changes as usual:
     * on AUDIOFOCUS_LOSS (the driver picked the radio, a call came in) stop playing and
     * call {@link #release()}.
     */
    public static KiaAudio claim(Context context, AudioManager.OnAudioFocusChangeListener listener) {
        return new KiaAudio(context.getApplicationContext(), listener);
    }

    /** The audio session every track and player must use to be heard. */
    public int session() {
        return kia ? SESSION_MUSIC_STREAMING : 0;
    }

    /** True if the speakers were claimed (focus granted). */
    public boolean held() {
        return held;
    }

    /** Gives the speakers back; the previous source (radio, Android Auto...) resumes. */
    public void release() {
        if (held) am.abandonAudioFocus(listener);
        held = false;
    }

    private boolean claimKia(Context context) {
        try {
            Method focus = AudioManager.class.getMethod("requestAudioFocus",
                    AudioManager.OnAudioFocusChangeListener.class, int.class, int.class, int.class);
            Class<?> micom = Class.forName("com.hkmc.media.MicomAudioManager");
            int r = (Integer) focus.invoke(am, listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN, MODE_MUSIC_STREAMING);
            if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "music streaming focus refused (" + r + ")");
                return true;   // a Kia unit all the same: keep the Kia session, held stays false
            }
            held = true;
            Object m = micom.getConstructor(Context.class).newInstance(context);
            micom.getMethod("setMute", int.class).invoke(m, UNMUTE_AV);
            return true;
        } catch (Throwable t) {
            // not a Kia head unit (no hidden mode variant or no automotive.jar)
            if (held) am.abandonAudioFocus(listener);
            held = false;
            return false;
        }
    }
}
