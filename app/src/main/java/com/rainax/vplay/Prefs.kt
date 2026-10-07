package com.rainax.vplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.preference.PreferenceManager

/** Typed access to the settings screen values. Defaults here must match res/xml/preferences.xml. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val theme: String get() = sp.getString("theme", "dark") ?: "dark"

    val defaultSpeed: Float
        get() = (sp.getString("default_speed", "1.0") ?: "1.0").toFloatOrNull() ?: 1f

    val seekStepMs: Long
        get() = ((sp.getString("seek_step", "10") ?: "10").toLongOrNull() ?: 10L) * 1000L

    val autoplayNext: Boolean get() = sp.getBoolean("autoplay_next", true)
    val rememberPosition: Boolean get() = sp.getBoolean("remember_position", true)
    val backgroundAudio: Boolean get() = sp.getBoolean("background_audio", true)
    val autoPip: Boolean get() = sp.getBoolean("auto_pip", false)
    val autoRotate: Boolean get() = sp.getBoolean("auto_rotate", true)

    val gestureBrightnessVolume: Boolean get() = sp.getBoolean("gesture_bv", true)
    val gestureSeek: Boolean get() = sp.getBoolean("gesture_seek", true)
    val gestureDoubleTap: Boolean get() = sp.getBoolean("gesture_double", true)
    val gestureLongPress: Boolean get() = sp.getBoolean("gesture_long", true)

    val decoderMode: String get() = sp.getString("decoder_mode", "auto") ?: "auto"

    val subSize: Float
        get() = when (sp.getString("sub_size", "medium")) {
            "small" -> 0.04f
            "large" -> 0.07f
            "xlarge" -> 0.09f
            else -> 0.0533f
        }

    val subColor: Int
        get() = when (sp.getString("sub_color", "white")) {
            "yellow" -> Color.parseColor("#FFEB3B")
            "cyan" -> Color.parseColor("#00E5FF")
            "green" -> Color.parseColor("#76FF03")
            else -> Color.WHITE
        }

    val subBackground: String get() = sp.getString("sub_bg", "outline") ?: "outline"

    val subPosition: Float
        get() = when (sp.getString("sub_pos", "normal")) {
            "low" -> 0.02f
            "high" -> 0.2f
            else -> 0.08f
        }

    fun themeRes(): Int =
        if (theme == "amoled") R.style.Theme_Rainax_Amoled else R.style.Theme_Rainax
}
