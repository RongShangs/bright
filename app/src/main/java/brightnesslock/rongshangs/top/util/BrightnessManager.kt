package brightnesslock.rongshangs.top.util

import android.util.Log

object BrightnessManager {
    private const val TAG = "BrightnessManager"
    private const val BRIGHTNESS_PATH = "/sys/class/backlight/panel1-backlight/brightness"
    private const val MAX_BRIGHTNESS_PATH = "/sys/class/backlight/panel1-backlight/max_brightness"

    enum class BrightnessState {
        LOCKED,
        SYSTEM,
        UNKNOWN
    }

    fun getCurrentState(): BrightnessState {
        val result = ShellUtils.execRoot("ls -l $BRIGHTNESS_PATH")
        if (!result.isSuccess) return BrightnessState.UNKNOWN

        val output = result.output.trim()
        return when {
            output.startsWith("-r--r--r--") -> BrightnessState.LOCKED
            output.startsWith("-rw-r--r--") -> BrightnessState.SYSTEM
            else -> BrightnessState.UNKNOWN
        }
    }

    fun lockBrightness(targetValue: Int): Boolean {
        for (attempt in 0 until 3) {
            ShellUtils.execRoot("chmod 644 $BRIGHTNESS_PATH")
            ShellUtils.execRoot("echo $targetValue > $BRIGHTNESS_PATH")
            ShellUtils.execRoot("chmod 444 $BRIGHTNESS_PATH")
            
            Thread.sleep(100)
            if (getCurrentBrightness() == targetValue) return true
        }
        return false
    }

    fun getMaxBrightness(): Int {
        val result = ShellUtils.execRoot("cat $MAX_BRIGHTNESS_PATH")
        return result.output.trim().toIntOrNull() ?: 4095
    }

    fun getCurrentBrightness(): Int {
        val result = ShellUtils.execRoot("cat $BRIGHTNESS_PATH")
        return result.output.trim().toIntOrNull() ?: 500
    }

    fun restoreSystemControl(): Boolean {
        val command = """
            chmod 644 $BRIGHTNESS_PATH
            echo 500 > $BRIGHTNESS_PATH
        """.trimIndent()
        return ShellUtils.execRoot(command).isSuccess
    }

    // AOD Controls
    fun isRearAodEnabled(): Boolean {
        val result = ShellUtils.execRoot("settings get secure rear_doze_always_on")
        return result.output.trim() == "1"
    }

    fun setRearAodEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val success = ShellUtils.execRoot("settings put secure rear_doze_always_on $value").isSuccess
        if (enabled && success) {
            Thread.sleep(50)
            ShellUtils.execRoot("input -d 1 keyevent KEYCODE_WAKEUP")
        }
        return success
    }
}
