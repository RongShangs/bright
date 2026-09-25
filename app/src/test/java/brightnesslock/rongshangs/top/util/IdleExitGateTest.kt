package brightnesslock.rongshangs.top.util

import org.junit.Assert.*
import org.junit.Test

class IdleExitGateTest {
    @Test fun panelMustCloseBeforeExit() {
        val gate = IdleExitGate(); val panel = Any()
        gate.acquire(panel); assertNull(gate.ticket())
        gate.release(panel); assertTrue(gate.accepts(gate.ticket()!!))
    }
    @Test fun reopeningInvalidatesOldExitEvenAfterClosingAgain() {
        val gate = IdleExitGate(); val ticket = gate.ticket()!!; val panel = Any()
        gate.acquire(panel); assertFalse(gate.accepts(ticket))
        gate.release(panel); assertFalse(gate.accepts(ticket))
        assertTrue(gate.accepts(gate.ticket()!!))
    }
    @Test fun boundTilePreventsExitAfterPanelCloses() {
        val gate = IdleExitGate(); val panel = Any(); val tile = Any()
        gate.acquire(panel); gate.acquire(tile); gate.release(panel)
        assertNull(gate.ticket())
        gate.release(tile); assertNotNull(gate.ticket())
    }
}
