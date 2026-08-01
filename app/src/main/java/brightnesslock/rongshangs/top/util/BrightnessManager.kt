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

    /**
     * 设置亮度核心逻辑 (去除了无效的sync，合并指令)
     */
    fun lockBrightnessOnce(targetValue: Int): Boolean {
        // 合并指令执行，去除无效的sync
        val cmd = "chmod 644 $BRIGHTNESS_PATH && echo $targetValue > $BRIGHTNESS_PATH && chmod 444 $BRIGHTNESS_PATH"
        val result = ShellUtils.execRoot(cmd)
        return result.isSuccess
    }

    /**
     * 恢复系统控制
     */
    fun restoreSystemControl(): Boolean {
        val cmd = "chmod 644 $BRIGHTNESS_PATH && echo 500 > $BRIGHTNESS_PATH"
        return ShellUtils.execRoot(cmd).isSuccess
    }

    /**
     * 检查当前接管状态
     */
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

    // --- 背屏AOD控制 ---

    fun isRearAodEnabled(): Boolean {
        val result = ShellUtils.execRoot("settings get secure rear_doze_always_on")
        return result.output.trim() == "1"
    }

    fun setRearAodEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val cmd = "settings put secure rear_doze_always_on $value"
        
        val success = ShellUtils.execRoot(cmd).isSuccess
        
        // 开启时自动唤醒背屏
        if (enabled && success) {
            try { Thread.sleep(50) } catch (e: Exception) {}
            ShellUtils.execRoot("input -d 1 keyevent KEYCODE_WAKEUP")
        }

        return success
    }
}
