package brightnesslock.rongshangs.top.util

import android.util.Log

object BrightnessManager {
    private const val TAG = "BrightnessManager"
    private const val BRIGHTNESS_PATH = "/sys/class/backlight/panel1-backlight/brightness"
    private const val MAX_BRIGHTNESS_PATH = "/sys/class/backlight/panel1-backlight/max_brightness"

    const val WATCHDOG_BIN = "/data/local/tmp/bright_watchdog"
    private const val WATCHDOG_PID = "/data/local/tmp/bright_watchdog.pid"

    enum class BrightnessState {
        LOCKED, SYSTEM, UNKNOWN
    }

    fun getCurrentBrightness(): Int {
        return try {
            val result = ShellUtils.execRoot("cat $BRIGHTNESS_PATH")
            result.output.trim().toIntOrNull() ?: 500
        } catch (e: Exception) {
            Log.e(TAG, "获取亮度失败", e)
            500
        }
    }

    fun getMaxBrightness(): Int {
        return try {
            val result = ShellUtils.execRoot("cat $MAX_BRIGHTNESS_PATH")
            result.output.trim().toIntOrNull() ?: 4095
        } catch (e: Exception) {
            Log.e(TAG, "获取最大亮度失败", e)
            4095
        }
    }

    fun lockBrightnessOnce(targetValue: Int): Boolean {
        val cmd = "chmod 644 $BRIGHTNESS_PATH && echo $targetValue > $BRIGHTNESS_PATH && chmod 444 $BRIGHTNESS_PATH"
        return ShellUtils.execRoot(cmd).isSuccess
    }

    /**
     * 启动C语言守护进程
     */
    fun startWatchdog(target: Int) {
        stopWatchdog()
        ShellUtils.destroy()
        
        // 启动C守护（后台独立进程）
        ShellUtils.execRoot("nohup $WATCHDOG_BIN $target > /dev/null 2>&1 &")
        Log.d(TAG, "C Watchdog started, target=$target")
    }

    /**
     * 停止C语言守护进程
     */
    fun stopWatchdog() {
        ShellUtils.execRoot("if [ -f $WATCHDOG_PID ]; then kill ${'$'}(cat $WATCHDOG_PID) 2>/dev/null; rm -f $WATCHDOG_PID; fi")
        ShellUtils.execRoot("pkill -f bright_watchdog")
        Log.d(TAG, "Watchdog stopped")
    }

    fun restoreSystemControl(): Boolean {
        stopWatchdog()
        return ShellUtils.execRoot("chmod 644 $BRIGHTNESS_PATH && echo 500 > $BRIGHTNESS_PATH").isSuccess
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

    fun isRearAodEnabled(): Boolean {
        return ShellUtils.execRoot("settings get secure rear_doze_always_on").output.trim() == "1"
    }

    fun setRearAodEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val success = ShellUtils.execRoot("settings put secure rear_doze_always_on $value").isSuccess
        if (enabled && success) {
            try { Thread.sleep(50) } catch (e: Exception) {}
            ShellUtils.execRoot("input -d 1 keyevent KEYCODE_WAKEUP")
        }
        return success
    }
}
