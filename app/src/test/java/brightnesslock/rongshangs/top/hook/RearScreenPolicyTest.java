package brightnesslock.rongshangs.top.hook;

import org.junit.Test;
import static org.junit.Assert.*;
import static brightnesslock.rongshangs.top.hook.RearScreenPolicy.*;

public class RearScreenPolicyTest {
    @Test public void disabledPreservesAllEvents() {
        for (int code = 0; code <= 6; code++) {
            assertFalse(blocksSensor(SENSOR_LIGHT, code, 0));
            assertFalse(blocksSensor(SENSOR_COVER, code, 0));
        }
        assertFalse(blocksAlarm(DARK_ALARM_TAG, 0));
    }
    @Test public void darkBlocksOnlyLowVendorCodes() {
        for (int code = 1; code <= 3; code++) assertTrue(blocksSensor(SENSOR_LIGHT, code, DARK));
        for (int code : new int[]{-1, 0, 4, 5, 6}) assertFalse(blocksSensor(SENSOR_LIGHT, code, DARK));
        assertFalse(blocksSensor(SENSOR_COVER, 1, DARK));
    }
    @Test public void coverPreservesAwayAndDoesNotBlockLight() {
        assertTrue(blocksSensor(SENSOR_COVER, 1, COVER));
        assertTrue(blocksSensor(SENSOR_COVER, 2, COVER));
        assertFalse(blocksSensor(SENSOR_COVER, 0, COVER));
        assertFalse(blocksSensor(SENSOR_LIGHT, 1, COVER));
    }
    @Test public void onlyDarkAlarmIsBlocked() {
        assertTrue(blocksAlarm(DARK_ALARM_TAG, DARK));
        assertFalse(blocksAlarm("SubScreenAutoOffAlarmTimeout", DARK | COVER));
        assertFalse(blocksAlarm(DARK_ALARM_TAG, COVER));
        assertFalse(blocksAlarm(null, DARK | COVER));
    }
    @Test public void unrelatedSensorsAndInvalidValuesFailOpen() {
        assertFalse(blocksSensor(8, 1, DARK | COVER)); // Main-screen proximity sensor.
        assertFalse(blocksSensor(5, 1, DARK | COVER)); // Standard light sensor.
        assertFalse(blocksSensor(SENSOR_LIGHT, Float.NaN, DARK | COVER));
        assertFalse(blocksSensor(SENSOR_COVER, Float.POSITIVE_INFINITY, DARK | COVER));
    }
    @Test public void switchesCombineWithoutChangingRecoveryEvents() {
        assertTrue(blocksSensor(SENSOR_LIGHT, 3, DARK | COVER));
        assertTrue(blocksSensor(SENSOR_COVER, 1, DARK | COVER));
        assertFalse(blocksSensor(SENSOR_LIGHT, 5, DARK | COVER));
        assertFalse(blocksSensor(SENSOR_COVER, 0, DARK | COVER));
    }
}
