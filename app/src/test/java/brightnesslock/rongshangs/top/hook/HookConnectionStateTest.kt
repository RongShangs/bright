package brightnesslock.rongshangs.top.hook

import org.junit.Assert.*
import org.junit.Test

class HookConnectionStateTest {
    @Test fun onlyConnectedConfigurationIsEditable() {
        HookBridge.Connection.entries.forEach { connection ->
            assertEquals(connection == HookBridge.Connection.CONNECTED, HookBridge.State(connection).editable)
        }
    }

    @Test fun disconnectedEnabledStateMustNotBeReportedAsRestored() {
        assertFalse(HookBridge.State(dark = true).knownDisabled)
        assertFalse(HookBridge.State(cover = true).knownDisabled)
        assertFalse(HookBridge.State(dark = true, cover = true).knownDisabled)
    }

    @Test fun unconfirmedWriteIsNotEquivalentToBothSwitchesOff() {
        assertFalse(HookBridge.State(uncertain = true).knownDisabled)
        assertTrue(HookBridge.State().knownDisabled)
    }
}
