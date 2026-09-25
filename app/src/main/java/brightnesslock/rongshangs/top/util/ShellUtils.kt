package brightnesslock.rongshangs.top.util

import java.io.ByteArrayOutputStream
import java.util.UUID

/** One shell; only read bytes known to be available, without a blocking reader thread. */
internal class RootSession(
    private val processFactory: () -> Process = { ProcessBuilder("su").redirectErrorStream(true).start() },
    private val authorizationTimeoutMs: Long = 30_000,
) {
    private var process: Process? = null

    @Synchronized
    fun execute(command: String, timeoutMs: Long = 8_000): ShellUtils.ShellResult {
        if (Thread.currentThread().isInterrupted) return ShellUtils.ShellResult(-1, "", "Interrupted")
        try {
            val fresh = process?.isAlive != true
            if (fresh) {
                close()
                process = processFactory()
            }
            val current = checkNotNull(process)
            val marker = "__BRIGHT_END_${UUID.randomUUID()}__"
            val deadline = System.nanoTime() + (if (fresh) maxOf(timeoutMs, authorizationTimeoutMs) else timeoutMs) * 1_000_000
            current.outputStream.write(("{\n$command\n} 2>&1\n__bright_status=\$?\nprintf '\\n$marker%s\\n' \"\$__bright_status\"\n").toByteArray(Charsets.UTF_8))
            current.outputStream.flush()
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (System.nanoTime() < deadline) {
                val available = current.inputStream.available()
                if (available > 0) {
                    val n = current.inputStream.read(chunk, 0, minOf(available, chunk.size))
                    if (n < 0) break
                    output.write(chunk, 0, n)
                    if (output.size() > 131_072) error("Root output too large")
                    val text = output.toString("UTF-8")
                    val start = text.indexOf("\n$marker")
                    val end = if (start >= 0) text.indexOf('\n', start + 1) else -1
                    if (end >= 0) {
                        val code = text.substring(start + 1 + marker.length, end).trim().toIntOrNull() ?: -1
                        val result = text.substring(0, start).trim()
                        return ShellUtils.ShellResult(code, result, if (code == 0) "" else result)
                    }
                } else {
                    if (!current.isAlive) break
                    Thread.sleep(10)
                }
            }
            close()
            return ShellUtils.ShellResult(-1, "", "Root command timed out or shell exited")
        } catch (_: InterruptedException) {
            close()
            Thread.currentThread().interrupt()
            return ShellUtils.ShellResult(-1, "", "Interrupted")
        } catch (error: Exception) {
            close()
            return ShellUtils.ShellResult(-1, "", error.message ?: "Root command failed")
        }
    }

    @Synchronized
    fun close() {
        val old = process ?: return
        process = null
        runCatching { old.destroyForcibly() }
        runCatching { old.outputStream.close() }
        runCatching { old.inputStream.close() }
        runCatching { old.errorStream.close() }
    }
}

object ShellUtils {
    private val session = RootSession()
    fun execRoot(command: String): ShellResult = session.execute(command)
    fun isRootAvailable(): Boolean = execRoot("id -u").let { it.isSuccess && it.output.trim() == "0" }
    fun destroy() = session.close()
    data class ShellResult(val exitCode: Int, val output: String, val error: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
