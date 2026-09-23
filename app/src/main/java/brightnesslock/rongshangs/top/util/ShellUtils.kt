package brightnesslock.rongshangs.top.util

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.UUID

object ShellUtils {
    private const val TAG = "ShellUtils"
    private var suProcess: Process? = null
    private var os: DataOutputStream? = null
    private var isReader: BufferedReader? = null
    private const val TIMEOUT_MS: Long = 30000

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
            
            val marker = "__BRIGHT_END_${UUID.randomUUID()}__"
            
            // 2. Try to send command (catch EPIPE)
            try {
                writeCommand(command, marker)
                os!!.flush()
            } catch (e: IOException) {
                // Fix: su process died (EPIPE), recreate and retry once
                Log.w(TAG, "su process died (EPIPE), recreating and retrying...")
                destroy()
                createSuProcess()
                
                writeCommand(command, marker)
                os!!.flush()
            }

            // 3. Read merged stdout/stderr until the marker, with a hard timeout.
            val readFuture = FutureTask {
                readResult(marker)
            }
            Thread(readFuture, "bright-shell-reader").apply {
                isDaemon = true
                start()
            }
            return try {
                readFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                readFuture.cancel(true)
                destroyInternal()
                ShellResult(-1, "", "Command timed out")
            }
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
        suProcess = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()
        os = DataOutputStream(suProcess!!.outputStream)
        isReader = BufferedReader(InputStreamReader(suProcess!!.inputStream))
    }

    private fun writeCommand(command: String, marker: String) {
        os!!.writeBytes("{\n$command\n} 2>&1\n")
        os!!.writeBytes("__bright_status=\$?\n")
        os!!.writeBytes("echo $marker\$__bright_status\n")
    }

    private fun readResult(marker: String): ShellResult {
        val output = StringBuilder()
        while (true) {
            val line = isReader?.readLine()
                ?: return ShellResult(-1, output.toString().trim(), "Root shell closed")
            if (line.startsWith(marker)) {
                val exitCode = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                val text = output.toString().trim()
                return ShellResult(exitCode, text, if (exitCode == 0) "" else text)
            }
            output.append(line).append('\n')
        }
    }

    fun isRootAvailable(): Boolean {
        return execRoot("id").output.contains("uid=0")
    }

    @Synchronized
    fun destroy() {
        destroyInternal()
    }

    private fun destroyInternal() {
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
