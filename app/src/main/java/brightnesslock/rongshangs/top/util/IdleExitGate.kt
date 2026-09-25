package brightnesslock.rongshangs.top.util

/** Main-thread lifecycle generation: an old exit must never kill a reopened panel. */
internal class IdleExitGate {
    private val owners = mutableSetOf<Any>()
    private var generation = 0L
    fun acquire(owner: Any) { owners.add(owner); generation++ }
    fun release(owner: Any) { owners.remove(owner); generation++ }
    fun ticket(): Long? = generation.takeIf { owners.isEmpty() }
    fun accepts(ticket: Long): Boolean = owners.isEmpty() && generation == ticket
}
