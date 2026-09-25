package brightnesslock.rongshangs.top.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RootSessionTest {
    private class FakeProcess(private val response: ((String) -> String?) = { "ok" }, private val failWrite: Boolean = false) : Process() {
        @Volatile var living = true
        var commands = 0
        private var bytes = ByteArrayInputStream(byteArrayOf())
        private val input = object : InputStream() {
            override fun available(): Int = minOf(bytes.available(), 3)
            override fun read(): Int { check(bytes.available() > 0); return bytes.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                check(bytes.available() > 0) { "Must not do a blocking read" }
                return bytes.read(b, off, minOf(len, available()))
            }
        }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = error("Expected one bounded command write")
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrite) throw IOException("broken pipe")
                commands++
                val command = String(b, off, len, Charsets.UTF_8)
                val marker = Regex("__BRIGHT_END_[a-f0-9-]+__").find(command)!!.value
                val text = response(command) ?: return
                bytes = ByteArrayInputStream("$text\n${marker}0\n".toByteArray(Charsets.UTF_8))
            }
        }
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (living) throw IllegalThreadStateException() else 0
        override fun isAlive(): Boolean = living
        override fun destroy() { living = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun readsFragmentedUtf8AndReusesTheSameProcess() {
        val process = FakeProcess({ "背屏\n没有结尾换行" })
        var creations = 0
        val session = RootSession({ creations++; process }, 0)
        repeat(2) {
            val result = session.execute("id", 500)
            assertTrue(result.isSuccess)
            assertEquals("背屏\n没有结尾换行", result.output)
        }
        assertEquals(1, creations)
        assertEquals(2, process.commands)
        session.close()
        assertFalse(process.living)
    }

    @Test fun stalledCommandTimesOutAndNextCommandGetsANewSession() {
        val stalled = FakeProcess({ null })
        val healthy = FakeProcess()
        var creations = 0
        val session = RootSession({ if (creations++ == 0) stalled else healthy }, 0)
        val start = System.nanoTime()
        assertFalse(session.execute("hang", 60).isSuccess)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
        assertFalse(stalled.living)
        assertTrue(session.execute("id", 500).isSuccess)
        assertEquals(2, creations)
        session.close()
    }

    @Test fun interruptedCommandExitsAndPreservesCancellation() {
        val process = FakeProcess({ null })
        val session = RootSession({ process }, 0)
        val done = CountDownLatch(1)
        var interrupted = false
        val thread = Thread {
            session.execute("hang", 5000)
            interrupted = Thread.currentThread().isInterrupted
            done.countDown()
        }
        thread.start()
        Thread.sleep(40)
        thread.interrupt()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertTrue(interrupted)
        assertFalse(process.living)
    }

    @Test fun failedWriteDoesNotReplayAMutatingCommand() {
        var creations = 0
        val session = RootSession({ creations++; FakeProcess(failWrite = true) }, 0)
        assertFalse(session.execute("change-state", 500).isSuccess)
        assertEquals(1, creations)
    }
}
