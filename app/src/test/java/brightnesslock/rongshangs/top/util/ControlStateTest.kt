package brightnesslock.rongshangs.top.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ControlStateTest {
    @Test fun cancelledGenerationCannotBecomeCurrentAgain() {
        val generation = OperationGeneration()
        val old = generation.next()
        generation.cancel()
        val current = generation.next()
        assertFalse(generation.isCurrent(old))
        assertTrue(generation.isCurrent(current))
        generation.cancel()
        assertFalse(generation.isCurrent(current))
    }

    @Test fun restoreAndCleanupRunAfterPreviouslyAcceptedCommands() {
        val log = mutableListOf<String>()
        val done = CountDownLatch(1)
        ControlQueue.execute { log.add("write") }
        ControlQueue.execute { log.add("restore") }
        ControlQueue.execute { log.add("cleanup"); done.countDown() }
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("write", "restore", "cleanup"), log)
    }

    @Test fun actualGuardianHealthIsNotConfusedWithSettings() {
        assertEquals(false, BrightnessManager.parseWatchdogState("STOPPED")!!.running)
        val failedWake = BrightnessManager.parseWatchdogState("OK 1000 1 0 1")!!
        assertTrue(failedWake.active)
        assertFalse(failedWake.activeHealthy)
        assertTrue(failedWake.brightnessHealthy)
        assertNull(BrightnessManager.parseWatchdogState("OK 1000 1 yes 1"))
        assertNull(BrightnessManager.parseWatchdogState("OK 0 0 1 1"))
        assertNull(BrightnessManager.parseWatchdogState("permission denied"))
    }
}
