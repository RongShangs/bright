package brightnesslock.rongshangs.top.util

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One queue across panel recreation; accepted changes finish before cleanup/restore. */
object ControlQueue {
    private val executor = ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, LinkedBlockingQueue()) { job ->
        Thread(job, "bright-control").apply { isDaemon = true }
    }
    fun execute(block: () -> Unit) = executor.execute(block)
}

internal class OperationGeneration {
    private val generation = AtomicLong()
    fun next(): Long = generation.incrementAndGet()
    fun current(): Long = generation.get()
    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket
    fun cancel() { generation.incrementAndGet() }
}
