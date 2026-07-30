package brightnesslock.rongshangs.top.util

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ShellUtils {
    private const val TAG = "ShellUtils"
    private var suProcess: Process? = null
    private var os: DataOutputStream? = null
    private var isReader: BufferedReader? = null
    private var lastUsedTime: Long = 0
    private const val TIMEOUT_MS: Long = 30000
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    init {
        scheduler.scheduleAtFixedRate({
            checkAndRelease()
        }, 1, 1, TimeUnit.MINUTES)
    }

    private fun isAlive(): Boolean {
        return try {
            suProcess?.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    @Synchronized
    fun execRoot(command: String): ShellResult {
        try {
            if (suProcess == null || !isAlive()) {
                destroy()
                suProcess = Runtime.getRuntime().exec("su")
                os = DataOutputStream(suProcess!!.outputStream)
                isReader = BufferedReader(InputStreamReader(suProcess!!.inputStream))
            }
            lastUsedTime = System.currentTimeMillis()
            
            val marker = "__END_${System.currentTimeMillis()}__"
            os!!.writeBytes("$command\n")
            os!!.writeBytes("echo $marker\n")
            os!!.flush()

            val output = StringBuilder()
            var line: String?
            while (true) {
                line = isReader!!.readLine()
                if (line == null || line.contains(marker)) break
                output.append(line).append("\n")
            }

            return ShellResult(0, output.toString().trim(), "")
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: $command", e)
            destroy()
            return ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }

    fun isRootAvailable(): Boolean {
        return execRoot("id").output.contains("uid=0")
    }

    @Synchronized
    private fun checkAndRelease() {
        if (suProcess != null && System.currentTimeMillis() - lastUsedTime > TIMEOUT_MS) {
            destroy()
        }
    }

    @Synchronized
    fun destroy() {
        try {
            os?.writeBytes("exit\n")
            os?.flush()
            os?.close()
            isReader?.close()
            suProcess?.destroy()
        } catch (e: Exception) {
            // Ignore
        } finally {
            os = null
            isReader = null
            suProcess = null
        }
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
