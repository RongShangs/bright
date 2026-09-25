package brightnesslock.rongshangs.top.util

import android.content.Context

/** Persistent state used by the quick-settings tile without issuing a Root command. */
object ControlStateStore {
    private const val PREFS_NAME = "config"
    private const val KEY_TAKEOVER = "is_takeover"
    private const val KEY_ACTIVE_MODE = "is_active_mode"
    private const val KEY_TARGET_BRIGHTNESS = "target_brightness"

    fun isTakeoverActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TAKEOVER, false)

    fun setTakeoverActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TAKEOVER, active)
            .apply()
    }

    fun isActiveModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE_MODE, false)

    fun setActiveModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE_MODE, enabled)
            .apply()
    }

    fun getTargetBrightness(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TARGET_BRIGHTNESS, -1)

    fun setTargetBrightness(context: Context, target: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TARGET_BRIGHTNESS, target)
            .apply()
    }
}
