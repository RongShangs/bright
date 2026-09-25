package brightnesslock.rongshangs.top.util

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object BrightnessManager {
    private const val BRIGHTNESS_PATH = "/sys/class/backlight/panel1-backlight/brightness"
    private const val MAX_PATH = "/sys/class/backlight/panel1-backlight/max_brightness"
    private const val TIME_KEY = "subscreen_display_time"
    private const val DEFAULT_TIME = "10000"
    private const val ACTIVE_TIME = "2147483647"
    private const val ROOT_DIR = "/data/adb/bright"
    const val WATCHDOG_BIN = "$ROOT_DIR/bright_watchdog"
    private var installed = false
    private val rootReadPaths = HashSet<String>()

    enum class BrightnessState { LOCKED, SYSTEM, UNKNOWN }
    data class WatchdogState(val running: Boolean, val target: Int = -1, val active: Boolean = false,
                             val activeHealthy: Boolean = true, val brightnessHealthy: Boolean = true)

    @Synchronized
    private fun readNumber(path: String): Int? {
        // Readable sysfs needs no fork or Root command for the 500ms panel refresh.
        if (path !in rootReadPaths) {
            val direct = runCatching { File(path).readText().trim().toIntOrNull() }
            direct.getOrNull()?.let { return it }
            if (direct.isFailure) rootReadPaths.add(path)
        }
        val result = ShellUtils.execRoot("IFS= read -r bright_value < '$path' && printf '%s\\n' \"\$bright_value\"")
        return if (result.isSuccess) result.output.trim().toIntOrNull() else null
    }
    fun getCurrentBrightness(): Int = readNumber(BRIGHTNESS_PATH) ?: -1
    fun getMaxBrightness(): Int = readNumber(MAX_PATH) ?: -1

    private fun prepare(context: Context): Boolean {
        if (installed) return true
        val secureDirectory = ShellUtils.execRoot("""
            test -d /data/adb && test ! -L /data/adb &&
            test ! -L '$ROOT_DIR' &&
            { test -d '$ROOT_DIR' || mkdir -m 700 '$ROOT_DIR'; } &&
            test "${'$'}(stat -c %u '$ROOT_DIR')" = 0 && chmod 700 '$ROOT_DIR'
        """.trimIndent()).isSuccess
        if (!secureDirectory || !stopLegacy()) return false
        return runCatching {
            val local = File(context.filesDir, "watchdog_c")
            context.assets.open("watchdog_c").use { input -> local.outputStream().use { output -> input.copyTo(output) } }
            val hash = MessageDigest.getInstance("SHA-256").digest(local.readBytes()).joinToString("") { "%02x".format(it) }
            val existing = ShellUtils.execRoot("test ! -L '$WATCHDOG_BIN' && sha256sum '$WATCHDOG_BIN'")
            if (!existing.isSuccess || existing.output.substringBefore(' ') != hash) {
                // Existing trusted installation must acknowledge STOP before replacement.
                if (existing.isSuccess && !ShellUtils.execRoot("'$WATCHDOG_BIN' --stop").isSuccess) return false
                val staging = "$ROOT_DIR/install-${UUID.randomUUID()}"
                val result = ShellUtils.execRoot("cp '${local.absolutePath}' '$staging' && chmod 700 '$staging' && mv -f '$staging' '$WATCHDOG_BIN'")
                if (!result.isSuccess) return false
            }
            installed = true
            true
        }.getOrDefault(false)
    }

    /** Migration only: never read the old untrusted PID file or execute the old binary. */
    private fun stopLegacy(): Boolean = ShellUtils.execRoot("""
        for bright_pid in ${'$'}(pidof bright_watchdog 2>/dev/null); do
            case "${'$'}(readlink /proc/${'$'}bright_pid/exe)" in
              /data/local/tmp/bright_watchdog|'/data/local/tmp/bright_watchdog (deleted)')
                kill -TERM "${'$'}bright_pid" 2>/dev/null
                bright_tries=0
                while test "${'$'}bright_tries" -lt 20 && test "${'$'}(readlink /proc/${'$'}bright_pid/exe)" = /data/local/tmp/bright_watchdog; do
                    sleep 0.05; bright_tries=${'$'}((bright_tries + 1))
                done
                case "${'$'}(readlink /proc/${'$'}bright_pid/exe)" in
                  /data/local/tmp/bright_watchdog|'/data/local/tmp/bright_watchdog (deleted)')
                    kill -KILL "${'$'}bright_pid" 2>/dev/null; sleep 0.05 ;;
                esac ;;
            esac
        done
        for bright_pid in ${'$'}(pidof bright_watchdog 2>/dev/null); do
            case "${'$'}(readlink /proc/${'$'}bright_pid/exe)" in
              /data/local/tmp/bright_watchdog|'/data/local/tmp/bright_watchdog (deleted)') exit 1 ;;
            esac
        done
        true
    """.trimIndent()).isSuccess

    internal fun parseWatchdogState(text: String): WatchdogState? {
        if (text.trim() == "STOPPED") return WatchdogState(false)
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 5 || parts[0] != "OK" || parts.drop(2).any { it != "0" && it != "1" }) return null
        val target = parts[1].toIntOrNull() ?: return null
        if (target != -1 && target !in 10..65535) return null
        return WatchdogState(true, target, parts[2] == "1", parts[3] == "1", parts[4] == "1")
    }

    @Synchronized
    fun watchdogState(context: Context): WatchdogState? {
        if (!prepare(context)) return null
        val result = ShellUtils.execRoot("'$WATCHDOG_BIN' --status")
        return if (result.isSuccess) parseWatchdogState(result.output) else null
    }

    @Synchronized
    fun startWatchdog(context: Context, target: Int, keepActive: Boolean): Boolean {
        if (target != -1 && target !in 10..65535) return false
        if (!prepare(context)) return false
        val current = watchdogState(context) ?: return false
        if (target == -1 && !keepActive) return stopWatchdog(context)
        if (!current.running) {
            if (!ShellUtils.execRoot("nohup '$WATCHDOG_BIN' --serve </dev/null >/dev/null 2>&1 &").isSuccess) return false
            var ready = false
            repeat(15) {
                if (!ready) {
                    Thread.sleep(20)
                    ready = watchdogState(context)?.running == true
                }
            }
            if (!ready) return false
        }
        val result = ShellUtils.execRoot("'$WATCHDOG_BIN' --set $target ${if (keepActive) 1 else 0}")
        val state = if (result.isSuccess) parseWatchdogState(result.output) else null
        return state?.let { it.target == target && it.active == keepActive && it.brightnessHealthy && (!keepActive || it.activeHealthy) } == true
    }

    @Synchronized
    fun stopWatchdog(context: Context): Boolean = prepare(context) && ShellUtils.execRoot("'$WATCHDOG_BIN' --stop").isSuccess

    private fun targetFor(context: Context, state: WatchdogState): Int? {
        if (state.running && state.target >= 10) return state.target
        return when (getCurrentState()) {
            BrightnessState.SYSTEM -> -1
            BrightnessState.UNKNOWN -> null
            BrightnessState.LOCKED -> ControlStateStore.getTargetBrightness(context).takeIf { it >= 10 }
                ?: getCurrentBrightness().takeIf { it >= 10 }
        }
    }

    @Synchronized
    fun ensureActiveModeWatchdog(context: Context): Boolean {
        val mode = readActiveModeTimeout() ?: return false
        val state = watchdogState(context) ?: return false
        val target = targetFor(context, state) ?: return false
        val active = mode == ACTIVE_TIME
        if (state.running && state.target == target && state.active == active && state.brightnessHealthy && (!active || state.activeHealthy)) return true
        // Faulted native wake monitoring already retries with backoff. Don't defeat that backoff.
        if (state.running && state.active == active && active && !state.activeHealthy) return false
        return startWatchdog(context, target, active)
    }

    @Synchronized
    fun restoreSystemControl(context: Context): Boolean {
        // Never write restored brightness while a previous writer can still run.
        if (!stopWatchdog(context)) return false
        val brightness = ShellUtils.execRoot("chmod 644 '$BRIGHTNESS_PATH' && printf '500' > '$BRIGHTNESS_PATH'").isSuccess
        val timeout = setTimeout(false)
        if (brightness && timeout) {
            ControlStateStore.setTakeoverActive(context, false)
            ControlStateStore.setActiveModeEnabled(context, false)
            ControlStateStore.setTargetBrightness(context, -1)
        }
        return brightness && timeout
    }

    fun getCurrentState(): BrightnessState {
        val result = ShellUtils.execRoot("stat -c %a '$BRIGHTNESS_PATH'")
        if (!result.isSuccess) return BrightnessState.UNKNOWN
        val permissions = result.output.trim().toIntOrNull(8) ?: return BrightnessState.UNKNOWN
        return if (permissions and 128 != 0) BrightnessState.SYSTEM else BrightnessState.LOCKED
    }

    fun isRearAodEnabled(): Boolean? {
        val result = ShellUtils.execRoot("settings get secure rear_doze_always_on")
        return if (!result.isSuccess) null else when (result.output.trim()) { "1" -> true; "0", "null" -> false; else -> null }
    }

    @Synchronized
    fun setRearAodEnabled(enabled: Boolean): Boolean {
        val success = ShellUtils.execRoot("settings put secure rear_doze_always_on ${if (enabled) 1 else 0}").isSuccess
        if (enabled && success) ShellUtils.execRoot("input -d 1 keyevent KEYCODE_WAKEUP")
        return success && isRearAodEnabled() == enabled
    }

    private fun setTimeout(enabled: Boolean): Boolean {
        val value = if (enabled) ACTIVE_TIME else DEFAULT_TIME
        val write = ShellUtils.execRoot("settings put system $TIME_KEY $value").isSuccess
        return write && readActiveModeTimeout() == value
    }

    @Synchronized
    fun setActiveMode(context: Context, enabled: Boolean): Boolean {
        val before = watchdogState(context) ?: return false
        val target = targetFor(context, before) ?: return false
        if (!enabled) {
            // Stop wake activity BEFORE touching settings; readback failure cannot leave it enabled.
            val disabled = startWatchdog(context, target, false)
            val stopped = disabled || stopWatchdog(context)
            val saved = setTimeout(false)
            if (stopped) ControlStateStore.setActiveModeEnabled(context, false)
            return stopped && saved && disabled
        }
        if (setTimeout(true) && startWatchdog(context, target, true)) {
            ControlStateStore.setActiveModeEnabled(context, true)
            return true
        }
        // Complete rollback, including an already launched daemon.
        val rolledBack = startWatchdog(context, target, false)
        if (!rolledBack) stopWatchdog(context)
        setTimeout(false)
        ControlStateStore.setActiveModeEnabled(context, false)
        return false
    }

    fun getActiveModeState(): Boolean? = readActiveModeTimeout()?.let { it == ACTIVE_TIME }
    private fun readActiveModeTimeout(): String? = ShellUtils.execRoot("settings get system $TIME_KEY").let {
        if (it.isSuccess) it.output.trim().let { value ->
            if (value == "null") DEFAULT_TIME else value.takeIf { it.toLongOrNull() != null }
        } else null
    }
}
