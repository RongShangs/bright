package top.rongshangs.brightnesslock.hook;

import android.app.AlarmManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.Handler;
import android.service.dreams.DreamService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** One-time structural resolution. Unknown layouts fail before any hook is installed. */
final class RearScreenProfile {
    final Field coverCallback, lightCallback, darkAlarm, handler, coverState, darkState, owner, branch, alarmTag;
    final Method sensorChanged, cancelAlarm, scheduleAlarm, fireAlarm, create, destroy;

    RearScreenProfile(ClassLoader loader) throws ReflectiveOperationException {
        this(Class.forName("com.xiaomi.subscreencenter.doze.DozeService", false, loader));
    }

    // Also used by fixture tests; does not instantiate the service or callbacks.
    RearScreenProfile(Class<?> service) throws ReflectiveOperationException {
        if (!DreamService.class.isAssignableFrom(service)) throw new ClassNotFoundException("Not DreamService");
        coverCallback = field(service, "k", null);
        Class<?> callback = implementation(coverCallback, SensorEventListener.class);
        lightCallback = field(service, "n", callback);
        darkAlarm = field(service, "j", null);
        Class<?> alarm = implementation(darkAlarm, AlarmManager.OnAlarmListener.class);
        field(service, "q", alarm); // Preserve the separate 90-second unlock timer.

        callback.getDeclaredConstructor(service, int.class);
        alarm.getDeclaredConstructor(AlarmManager.class, AlarmManager.OnAlarmListener.class, String.class, Handler.class);
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

    String describe() {
        return "sensorListener=" + coverCallback.getType().getName() +
                "; alarmListener=" + darkAlarm.getType().getName();
    }

    private static Class<?> implementation(Field field, Class<?> contract) throws ClassNotFoundException {
        Class<?> type = field.getType();
        // isAssignableFrom already checks all parent classes and inherited interfaces.
        if (!contract.isAssignableFrom(type) || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            throw new ClassNotFoundException("Unexpected implementation of " + contract.getName() + ": " + type.getName());
        }
        return type;
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (Modifier.isStatic(field.getModifiers()) || (expected != null && field.getType() != expected)) {
            throw new NoSuchFieldException(type.getName() + "." + name + " has unexpected type or is static");
        }
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, args);
        if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(name + " must be an instance void method");
        }
        method.setAccessible(true);
        return method;
    }
}
