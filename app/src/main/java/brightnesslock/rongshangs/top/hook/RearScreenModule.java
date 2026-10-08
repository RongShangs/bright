package brightnesslock.rongshangs.top.hook;

import android.app.AlarmManager;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.Handler;
import android.service.dreams.DreamService;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Optional hooks inside the existing rear-screen center process, never system_server. */
public final class RearScreenModule extends XposedModule {
    private static final String TAG = "BrightRearPolicy";
    private volatile int policy;
    private boolean policyLoaded;
    private volatile boolean ready;
    private boolean attempted;
    private boolean targetProcess;
    private SharedPreferences preferences;
    // SharedPreferences keeps listeners weakly: retain this for the target process lifetime.
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> refreshPolicy();
    private volatile WeakReference<Object> currentService = new WeakReference<>(null);
    private final AtomicBoolean runtimeWarning = new AtomicBoolean();
    private final AtomicBoolean firstSensor = new AtomicBoolean();
    private final AtomicBoolean firstCoverBlock = new AtomicBoolean();
    private final AtomicBoolean firstDarkBlock = new AtomicBoolean();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        targetProcess = !param.isSystemServer() && RearScreenPolicy.TARGET_PACKAGE.equals(param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!targetProcess || attempted || !RearScreenPolicy.TARGET_PACKAGE.equals(param.getPackageName())) return;
        attempted = true;
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            Profile profile = new Profile(param.getClassLoader());
            preferences = getRemotePreferences(RearScreenPolicy.PREF_GROUP);
            preferences.registerOnSharedPreferenceChangeListener(listener);
            refreshPolicy();

            handles.add(hook(profile.sensorChanged).intercept(chain -> {
                int snapshot = policy;
                if (!firstSensor.get() && firstSensor.compareAndSet(false, true)) {
                    log(Log.INFO, TAG, "Rear sensor callback reached; policy=" + snapshot + "; ready=" + ready);
                }
                if (ready && snapshot != 0) {
                    try {
                        Object callback = chain.getThisObject();
                        SensorEvent event = (SensorEvent) chain.getArg(0);
                        if (event != null && event.sensor != null && event.values != null && event.values.length > 0) {
                            Object service = profile.owner.get(callback);
                            int branch = profile.branch.getInt(callback);
                            boolean correctCallback = branch == 0 && profile.coverCallback.get(service) == callback &&
                                    event.sensor.getType() == RearScreenPolicy.SENSOR_COVER;
                            correctCallback |= branch == 1 && profile.lightCallback.get(service) == callback &&
                                    event.sensor.getType() == RearScreenPolicy.SENSOR_LIGHT;
                            if (correctCallback && RearScreenPolicy.blocksSensor(event.sensor.getType(), event.values[0], snapshot)) {
                                AtomicBoolean first = branch == 0 ? firstCoverBlock : firstDarkBlock;
                                if (!first.get() && first.compareAndSet(false, true)) {
                                    log(Log.INFO, TAG, "Blocked rear " + (branch == 0 ? "cover" : "dark") + " event; type=" + event.sensor.getType());
                                }
                                return null;
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        warnOnce(error);
                    }
                }
                return chain.proceed();
            }));

            XposedInterface.Hooker alarmHook = chain -> {
                int snapshot = policy;
                if (ready && (snapshot & RearScreenPolicy.DARK) != 0) {
                    try {
                        Object alarm = chain.getThisObject();
                        if (RearScreenPolicy.blocksAlarm((String) profile.alarmTag.get(alarm), snapshot)) {
                            profile.cancelAlarm.invoke(alarm);
                            return null;
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        warnOnce(error);
                    }
                }
                return chain.proceed();
            };
            handles.add(hook(profile.scheduleAlarm).intercept(alarmHook));
            handles.add(hook(profile.fireAlarm).intercept(alarmHook));
            handles.add(hook(profile.create).intercept(chain -> {
                Object result = chain.proceed();
                currentService = new WeakReference<>(chain.getThisObject());
                reconcile(profile);
                return result;
            }));
            handles.add(hook(profile.destroy).intercept(chain -> {
                if (currentService.get() == chain.getThisObject()) currentService = new WeakReference<>(null);
                return chain.proceed();
            }));
            activeProfile = profile;
            ready = true;
            log(Log.INFO, TAG, "Rear AOD dark/cover hooks installed; no wake loop; firmware profile OS4.0.0.41.XBLCNXM");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            ready = false;
            policy = 0;
            for (XposedInterface.HookHandle handle : handles) {
                try { handle.unhook(); } catch (RuntimeException ignored) { /* fail open */ }
            }
            if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(listener);
            log(Log.WARN, TAG, "Unsupported rear-center profile or remote preferences; original behavior retained", error);
        }
    }

    private volatile Profile activeProfile;

    private synchronized void refreshPolicy() {
        try {
            int next = preferences.getBoolean(RearScreenPolicy.KEY_DARK, false) ? RearScreenPolicy.DARK : 0;
            if (preferences.getBoolean(RearScreenPolicy.KEY_COVER, false)) next |= RearScreenPolicy.COVER;
            // One remote commit notifies each changed key. Reconcile the completed map once.
            if (policyLoaded && policy == next) return;
            policyLoaded = true;
            policy = next;
            log(Log.INFO, TAG, "Rear policy loaded; mask=" + next);
            Profile profile = activeProfile;
            if (profile != null) reconcile(profile);
        } catch (RuntimeException error) {
            policy = 0;
            policyLoaded = false;
            warnOnce(error);
        }
    }

    private void reconcile(Profile profile) {
        WeakReference<Object> reference = currentService;
        Object service = reference.get();
        if (service == null) return;
        try {
            Handler handler = (Handler) profile.handler.get(service);
            handler.post(() -> {
                Object instance = reference.get();
                if (!ready || instance == null || currentService.get() != instance) return;
                try {
                    if ((policy & RearScreenPolicy.DARK) != 0) {
                        Object alarm = profile.darkAlarm.get(instance);
                        if (alarm != null && RearScreenPolicy.DARK_ALARM_TAG.equals(profile.alarmTag.get(alarm))) {
                            profile.cancelAlarm.invoke(alarm);
                            profile.darkState.setBoolean(instance, false);
                        }
                    }
                    if ((policy & RearScreenPolicy.COVER) != 0) profile.coverState.setBoolean(instance, false);
                    // Do not wake an already-off display: it may have been switched off manually.
                } catch (ReflectiveOperationException | RuntimeException error) {
                    warnOnce(error);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            warnOnce(error);
        }
    }

    private void warnOnce(Throwable error) {
        if (runtimeWarning.compareAndSet(false, true)) {
            log(Log.WARN, TAG, "Rear policy hook failed; this event follows original behavior", error);
        }
    }

    /** Strict profile from the supplied APK. Unknown shapes fail open, not broad power hooks. */
    private static final class Profile {
        final Field coverCallback, lightCallback, darkAlarm, handler, coverState, darkState, owner, branch, alarmTag;
        final Method sensorChanged, cancelAlarm, scheduleAlarm, fireAlarm, create, destroy;

        Profile(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> service = Class.forName("com.xiaomi.subscreencenter.doze.DozeService", false, loader);
            if (!DreamService.class.isAssignableFrom(service)) throw new ClassNotFoundException("Not DreamService");
            Class<?> callback = Class.forName("m2.g", false, loader);
            Class<?> alarm = Class.forName("C2.a", false, loader);
            if (!SensorEventListener.class.isAssignableFrom(callback) || !AlarmManager.OnAlarmListener.class.isAssignableFrom(alarm)) {
                throw new ClassNotFoundException("Unexpected callback types");
            }
            callback.getDeclaredConstructor(service, int.class);
            alarm.getDeclaredConstructor(AlarmManager.class, AlarmManager.OnAlarmListener.class, String.class, Handler.class);
            coverCallback = field(service, "k", callback);
            lightCallback = field(service, "n", callback);
            darkAlarm = field(service, "j", alarm);
            field(service, "q", alarm); // Separate 90-second unlock timer must remain untouched.
            field(service, "g", Sensor.class);
            field(service, "l", Sensor.class);
            handler = field(service, "b", Handler.class);
            coverState = field(service, "h", boolean.class);
            darkState = field(service, "m", boolean.class);
            field(service, "s", boolean.class);
            owner = field(callback, "b", service);
            branch = field(callback, "a", int.class);
            alarmTag = field(alarm, "c", String.class);
            cancelAlarm = method(alarm, "a");
            scheduleAlarm = method(alarm, "b", long.class, int.class);
            fireAlarm = method(alarm, "onAlarm");
            sensorChanged = method(callback, "onSensorChanged", SensorEvent.class);
            create = method(service, "onCreate");
            destroy = method(service, "onDestroy");
        }

        private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
            Field field = type.getDeclaredField(name);
            if (field.getType() != expected) throw new NoSuchFieldException(type.getName() + "." + name);
            field.setAccessible(true);
            return field;
        }

        private static Method method(Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
            Method method = type.getDeclaredMethod(name, args);
            if (method.getReturnType() != void.class) throw new NoSuchMethodException(name + " must return void");
            method.setAccessible(true);
            return method;
        }
    }
}
