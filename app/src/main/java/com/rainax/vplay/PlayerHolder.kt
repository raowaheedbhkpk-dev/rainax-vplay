package com.rainax.vplay

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.log10

/**
 * One ExoPlayer for the whole app. It is created when the player screen opens and lives as long as
 * [PlaybackService], so audio can keep playing with the screen off.
 */
@UnstableApi
object PlayerHolder {

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    val fx = AudioFx()

    /** elapsedRealtime when the sleep timer fires, or 0 when off. */
    var sleepEndsAt: Long = 0L
        private set

    private val sleepRunnable = Runnable {
        player?.pause()
        sleepEndsAt = 0L
    }

    fun get(context: Context): ExoPlayer {
        player?.let { return it }

        val app = context.applicationContext
        val prefs = Prefs(app)

        val extensionMode = when (prefs.decoderMode) {
            "prefer_ffmpeg" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            "off" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        val renderers = DefaultRenderersFactory(app)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(app, renderers)
            .setSeekBackIncrementMs(prefs.seekStepMs)
            .setSeekForwardIncrementMs(prefs.seekStepMs)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setSelectUndeterminedTextLanguage(true)
            .build()

        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                fx.attach(audioSessionId, prefs.sp)
            }
        })
        fx.attach(p.audioSessionId, prefs.sp)

        player = p
        return p
    }

    fun release() {
        handler.removeCallbacks(sleepRunnable)
        sleepEndsAt = 0L
        fx.release()
        player?.release()
        player = null
    }

    /** minutes <= 0 turns the timer off. */
    fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepEndsAt = 0L
            return
        }
        val ms = minutes * 60_000L
        sleepEndsAt = SystemClock.elapsedRealtime() + ms
        handler.postDelayed(sleepRunnable, ms)
    }

    fun sleepRemainingMinutes(): Int {
        if (sleepEndsAt <= 0L) return 0
        val left = sleepEndsAt - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else ((left + 59_999L) / 60_000L).toInt()
    }
}

/** Equalizer and volume boost attached to the player's audio session. */
class AudioFx {
    private var eq: Equalizer? = null
    private var boost: LoudnessEnhancer? = null

    fun attach(sessionId: Int, sp: SharedPreferences) {
        release()
        if (sessionId == 0) return
        try {
            eq = Equalizer(0, sessionId)
        } catch (e: Exception) {
            eq = null
        }
        try {
            boost = LoudnessEnhancer(sessionId)
        } catch (e: Exception) {
            boost = null
        }
        refresh(sp)
    }

    fun release() {
        try {
            eq?.release()
        } catch (e: Exception) {
        }
        try {
            boost?.release()
        } catch (e: Exception) {
        }
        eq = null
        boost = null
    }

    val equalizerAvailable: Boolean get() = eq != null
    val boostAvailable: Boolean get() = boost != null

    fun bandCount(): Int = try {
        eq?.getNumberOfBands()?.toInt() ?: 0
    } catch (e: Exception) {
        0
    }

    fun minLevel(): Int = try {
        eq?.getBandLevelRange()?.get(0)?.toInt() ?: -1500
    } catch (e: Exception) {
        -1500
    }

    fun maxLevel(): Int = try {
        eq?.getBandLevelRange()?.get(1)?.toInt() ?: 1500
    } catch (e: Exception) {
        1500
    }

    fun centerFreqHz(band: Int): Int = try {
        (eq?.getCenterFreq(band.toShort()) ?: 0) / 1000
    } catch (e: Exception) {
        0
    }

    /** Re-reads eq_enabled / eq_levels / boost_percent from [sp] and applies them. */
    fun refresh(sp: SharedPreferences) {
        val e = eq
        if (e != null) {
            try {
                val on = sp.getBoolean("eq_enabled", false)
                val levels = (sp.getString("eq_levels", "") ?: "")
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                val min = minLevel()
                val max = maxLevel()
                for (b in 0 until bandCount()) {
                    val level = (levels.getOrNull(b) ?: 0).coerceIn(min, max)
                    e.setBandLevel(b.toShort(), level.toShort())
                }
                e.setEnabled(on)
            } catch (ex: Exception) {
                // effect not usable right now
            }
        }
        val b = boost
        if (b != null) {
            try {
                val percent = sp.getInt("boost_percent", 100)
                val gainMb = if (percent <= 100) 0 else (2000.0 * log10(percent / 100.0)).toInt()
                b.setTargetGain(gainMb)
                b.setEnabled(percent > 100)
            } catch (ex: Exception) {
                // effect not usable right now
            }
        }
    }
}
