package brightnesslock.rongshangs.top.hook;

/** Pure policy: no polling, Android calls, or global screen-state overrides. */
public final class RearScreenPolicy {
    public static final String TARGET_PACKAGE = "com.xiaomi.subscreencenter";
    public static final String PREF_GROUP = "rear_screen_policy_v2";
    public static final String KEY_DARK = "block_dark_sleep";
    public static final String KEY_COVER = "block_cover_sleep";
    public static final String DARK_ALARM_TAG = "SubScreenOffAlarmTimeout";
    public static final int SENSOR_COVER = 33171227;
    public static final int SENSOR_LIGHT = 33171129;
    public static final int DARK = 1;
    public static final int COVER = 2;

    private RearScreenPolicy() {}

    public static boolean blocksSensor(int sensorType, float value, int policy) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return false;
        if (sensorType == SENSOR_COVER) {
            return (policy & COVER) != 0 && value >= 1f;
        }
        if (sensorType == SENSOR_LIGHT && (policy & DARK) != 0) {
            int code = (int) value;
            // These are vendor event codes, not ambient illuminance in lux.
            return code == 1 || code == 2 || code == 3;
        }
        return false;
    }

    public static boolean blocksAlarm(String tag, int policy) {
        return (policy & DARK) != 0 && DARK_ALARM_TAG.equals(tag);
    }
}
