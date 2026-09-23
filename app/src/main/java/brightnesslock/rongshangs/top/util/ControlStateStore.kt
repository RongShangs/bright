package brightnesslock.rongshangs.top.util

import android.content.Context

/** Persistent state used by the quick-settings tile without issuing a Root command. */
object ControlStateStore {
    private const val PREFS_NAME = "config"
    private const val KEY_TAKEOVER = "is_takeover"

    fun isTakeoverActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TAKEOVER, false)

    fun setTakeoverActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TAKEOVER, active)
            .apply()
    }
}
