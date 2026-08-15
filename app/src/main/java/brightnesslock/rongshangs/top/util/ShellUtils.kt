package brightnesslock.rongshangs.top.util

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
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
        scheduler.scheduleWithFixedDelay({
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
            // 1. Ensure su process exists and is alive
            if (suProcess == null || !isAlive()) {
                destroy()
                createSuProcess()
            }
            
            lastUsedTime = System.currentTimeMillis()
            val marker = "__END_${System.currentTimeMillis()}__"
            
            // 2. Try to send command (catch EPIPE)
            try {
                os!!.writeBytes("$command\n")
                os!!.writeBytes("echo $marker\n")
                os!!.flush()
            } catch (e: IOException) {
                // Fix: su process died (EPIPE), recreate and retry once
                Log.w(TAG, "su process died (EPIPE), recreating and retrying...")
                destroy()
                createSuProcess()
                
                os!!.writeBytes("$command\n")
                os!!.writeBytes("echo $marker\n")
                os!!.flush()
            }

            // 3. Read output until marker
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

    /**
     * Create su process (for initial use and reconstruction)
     */
    private fun createSuProcess() {
        suProcess = Runtime.getRuntime().exec("su")
        os = DataOutputStream(suProcess!!.outputStream)
        isReader = BufferedReader(InputStreamReader(suProcess!!.inputStream))
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
