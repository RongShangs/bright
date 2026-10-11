package top.rongshangs.brightnesslock.hook;

import android.app.AlarmManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.Handler;
import android.os.IBinder;
import android.service.dreams.DreamService;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Synthetic layouts test resolution, not vendor firmware behavior or real Xposed injection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class RearScreenProfileTest {
    @Test public void originalLayoutResolvesFromFields() throws Exception {
        RearScreenProfile profile = new RearScreenProfile(LegacyService.class);
        assertEquals(LegacySensor.class, profile.sensorChanged.getDeclaringClass());
        assertEquals(LegacyAlarm.class, profile.scheduleAlarm.getDeclaringClass());
        assertEquals(LegacyService.class, profile.owner.getType());
        assertEquals("j", profile.darkAlarm.getName());
        assertEquals("onDestroy", profile.destroy.getName());
    }

    @Test public void renamedImplementationsNeedNoKnownShortNames() throws Exception {
        RearScreenProfile profile = new RearScreenProfile(ShuffledService.class);
        assertEquals(ShuffledSensor.class, profile.sensorChanged.getDeclaringClass());
        assertEquals(ShuffledAlarm.class, profile.fireAlarm.getDeclaringClass());
        assertEquals("sensorListener=" + ShuffledSensor.class.getName() +
                "; alarmListener=" + ShuffledAlarm.class.getName(), profile.describe());
    }

    @Test public void inheritedSensorInterfaceIsAccepted() throws Exception {
        assertFalse(java.util.Arrays.asList(LegacySensor.class.getInterfaces()).contains(SensorEventListener.class));
        assertEquals(LegacySensor.class, new RearScreenProfile(LegacyService.class).coverCallback.getType());
    }

    @Test public void unrelatedDeathRecipientIsRejected() {
        assertThrows(ClassNotFoundException.class, () -> new RearScreenProfile(UnrelatedService.class));
    }

    @Test public void differentCoverAndLightClassesAreRejected() {
        NoSuchFieldException error = assertThrows(NoSuchFieldException.class,
                () -> new RearScreenProfile(MixedSensorService.class));
        assertTrue(error.getMessage().contains(".n"));
    }

    @Test public void differentDarkAndUnlockAlarmClassesAreRejected() {
        NoSuchFieldException error = assertThrows(NoSuchFieldException.class,
                () -> new RearScreenProfile(MixedAlarmService.class));
        assertTrue(error.getMessage().contains(".q"));
    }

    @Test public void wrongCallbackConstructorIsRejected() {
        assertThrows(NoSuchMethodException.class, () -> new RearScreenProfile(BadConstructorService.class));
    }

    @Test public void wrongCallbackOwnerIsRejected() {
        NoSuchFieldException error = assertThrows(NoSuchFieldException.class,
                () -> new RearScreenProfile(BadOwnerService.class));
        assertTrue(error.getMessage().contains(".b"));
    }

    @Test public void nonVoidAlarmMethodIsRejected() {
        NoSuchMethodException error = assertThrows(NoSuchMethodException.class,
                () -> new RearScreenProfile(BadReturnService.class));
        assertTrue(error.getMessage().contains("instance void"));
    }

    @Test public void staticEventFieldsAreRejected() {
        assertThrows(NoSuchFieldException.class, () -> new RearScreenProfile(StaticCallbackService.class));
    }

    @Test public void nonDreamServiceAndMissingFieldsAreRejected() {
        assertThrows(ClassNotFoundException.class, () -> new RearScreenProfile(String.class));
        assertThrows(NoSuchFieldException.class, () -> new RearScreenProfile(MissingFieldService.class));
    }

    @Test public void abstractImplementationIsRejected() {
        assertThrows(ClassNotFoundException.class, () -> new RearScreenProfile(AbstractCallbackService.class));
    }

    public static class LegacyService extends DreamService {
        private LegacySensor k, n;
        private LegacyAlarm j, q;
        private Sensor g, l;
        private Handler b;
        private boolean h, m, s;
        @Override public void onCreate() {}
        @Override public void onDestroy() {}
    }
    public abstract static class SensorBase implements SensorEventListener {
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }
    public static class LegacySensor extends SensorBase {
        private int a;
        private LegacyService b;
        public LegacySensor(LegacyService service, int branch) {}
        @Override public void onSensorChanged(SensorEvent event) {}
    }
    public static class LegacyAlarm implements AlarmManager.OnAlarmListener {
        private String c;
        public LegacyAlarm(AlarmManager manager, AlarmManager.OnAlarmListener listener, String tag, Handler handler) {}
        public void a() {}
        public void b(long delay, int type) {}
        @Override public void onAlarm() {}
    }
    public static class ShuffledService extends DreamService {
        private ShuffledSensor k, n;
        private ShuffledAlarm j, q;
        private Sensor g, l;
        private Handler b;
        private boolean h, m, s;
        @Override public void onCreate() {}
        @Override public void onDestroy() {}
    }
    public static class ShuffledSensor implements SensorEventListener {
        private int a;
        private ShuffledService b;
        public ShuffledSensor(ShuffledService service, int branch) {}
        @Override public void onSensorChanged(SensorEvent event) {}
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }
    public static class ShuffledAlarm implements AlarmManager.OnAlarmListener {
        private String c;
        public ShuffledAlarm(AlarmManager manager, AlarmManager.OnAlarmListener listener, String tag, Handler handler) {}
        public void a() {}
        public void b(long delay, int type) {}
        @Override public void onAlarm() {}
    }
    public static class UnrelatedService extends DreamService { private DeathCallback k, n; }
    public static class DeathCallback implements IBinder.DeathRecipient { @Override public void binderDied() {} }
    public static class MixedSensorService extends DreamService { private LegacySensor k; private ShuffledSensor n; }
    public static class MixedAlarmService extends DreamService {
        private LegacySensor k, n;
        private LegacyAlarm j;
        private ShuffledAlarm q;
    }
    public static class BadConstructorService extends DreamService {
        private BadConstructorSensor k, n;
        private LegacyAlarm j, q;
    }
    public static class BadConstructorSensor extends SensorBase {
        public BadConstructorSensor(Object service, int branch) {}
        @Override public void onSensorChanged(SensorEvent event) {}
    }
    public static class BadOwnerService extends DreamService {
        private BadOwnerSensor k, n;
        private LegacyAlarm j, q;
        private Sensor g, l;
        private Handler b;
        private boolean h, m, s;
    }
    public static class BadOwnerSensor extends SensorBase {
        private int a;
        private Object b;
        public BadOwnerSensor(BadOwnerService service, int branch) {}
        @Override public void onSensorChanged(SensorEvent event) {}
    }
    public static class BadReturnService extends DreamService {
        private BadReturnSensor k, n;
        private BadReturnAlarm j, q;
        private Sensor g, l;
        private Handler b;
        private boolean h, m, s;
    }
    public static class BadReturnSensor extends SensorBase {
        private int a;
        private BadReturnService b;
        public BadReturnSensor(BadReturnService service, int branch) {}
        @Override public void onSensorChanged(SensorEvent event) {}
    }
    public static class BadReturnAlarm implements AlarmManager.OnAlarmListener {
        private String c;
        public BadReturnAlarm(AlarmManager manager, AlarmManager.OnAlarmListener listener, String tag, Handler handler) {}
        public int a() { return 0; }
        @Override public void onAlarm() {}
    }
    public static class StaticCallbackService extends DreamService { private static LegacySensor k; }
    public static class MissingFieldService extends DreamService { private LegacySensor k; }
    public static class AbstractCallbackService extends DreamService { private SensorBase k; }
}
