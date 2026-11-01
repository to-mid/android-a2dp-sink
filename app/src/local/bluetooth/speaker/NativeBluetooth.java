package local.bluetooth.speaker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.lang.reflect.Method;

final class NativeBluetooth {
    private static boolean initialized;
    private static Context systemContext;
    private static Object frameworkProxy;
    private static String processCompatibility = "not-required";

    static BluetoothAdapter adapter() throws Exception {
        if (!initialized && android.os.Process.myUid() == 0) {
            try {
                java.lang.reflect.Field config = android.app.AppOpsManager.class.getDeclaredField("sConfig");
                config.setAccessible(true);
                Class<?> type = config.getType();
                if (type.getMethod("getSampledOpCode").getReturnType() == int.class
                        && type.getMethod("getAcceptableLeftDistance").getReturnType() == int.class
                        && type.getMethod("getExpirationTimeSinceBootMillis").getReturnType() == long.class) {
                    // Root app_process is not an installed app. Skip its optional stack sampler,
                    // which can crash inside system_server; permission checks remain in place.
                    config.set(null, type.getConstructor(int.class, int.class, long.class)
                        .newInstance(-1, 0, Long.MAX_VALUE));
                    processCompatibility = "process-local-stack-sampling";
                }
            } catch (ReflectiveOperationException ignored) {}
        }
        if (!initialized && Build.VERSION.SDK_INT >= 31) {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Class<?> initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            if (initializer.getMethod("getBluetoothServiceManager").invoke(null) == null) {
                Class<?> manager = Class.forName("android.os.BluetoothServiceManager");
                initializer.getMethod("setBluetoothServiceManager", manager)
                    .invoke(null, manager.getConstructor().newInstance());
            }
        }
        initialized = true;
        return BluetoothAdapter.getDefaultAdapter();
    }

    static Object legacyProfile(boolean sink, String packageName) throws Exception {
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, sink
            ? "com.android.bluetooth.a2dpsink.A2dpSinkService" : "com.android.bluetooth.a2dp.A2dpService"));
        IBinder binder = (IBinder) Class.forName("android.app.IActivityManager")
            .getMethod("peekService", Intent.class, String.class, String.class).invoke(manager, intent, null, "android");
        if (binder == null) return null;
        return Class.forName(interfaceName(sink) + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    static Object attribution() throws Exception {
        return Class.forName("android.content.AttributionSource").getMethod("myAttributionSource").invoke(null);
    }

    static Object profile(BluetoothAdapter adapter, boolean sink) throws Exception {
        return profile(adapter, sink, RootController.targetPackage());
    }

    static Object profile(BluetoothAdapter adapter, boolean sink, String packageName) throws Exception {
        String access = access();
        if ("service-peek".equals(access)) return legacyProfile(sink, packageName);
        if ("framework-connector".equals(access)) {
            Class<?> proxyType = Class.forName(sink ? "android.bluetooth.BluetoothA2dpSink" : "android.bluetooth.BluetoothA2dp");
            java.lang.reflect.Constructor<?> constructor = proxyType.getDeclaredConstructor(
                Context.class, BluetoothProfile.ServiceListener.class, BluetoothAdapter.class);
            constructor.setAccessible(true);
            frameworkProxy = constructor.newInstance(systemContext, null, adapter);
            Method get = proxyType.getDeclaredMethod("getService");
            get.setAccessible(true);
            for (int i = 0; i < 40; i++) {
                Object service = get.invoke(frameworkProxy);
                if (service != null) return service;
                Thread.sleep(100);
            }
            return null;
        }
        if (!"adapter-binder".equals(access)) throw new IllegalStateException("没有兼容的蓝牙服务获取接口");
        Method get = BluetoothAdapter.class.getDeclaredMethod("getProfile", int.class);
        get.setAccessible(true);
        IBinder binder = (IBinder) get.invoke(adapter, sink ? 11 : 2);
        if (binder == null) return null;
        return Class.forName(interfaceName(sink) + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    static String processCompatibility() { return processCompatibility; }
    static String interfaceName(boolean sink) {
        return sink ? "android.bluetooth.IBluetoothA2dpSink" : "android.bluetooth.IBluetoothA2dp";
    }

    static String access() {
        try {
            if (BluetoothAdapter.class.getDeclaredMethod("getProfile", int.class).getReturnType() == IBinder.class)
                return "adapter-binder";
        } catch (ReflectiveOperationException ignored) {}
        try {
            for (String name : new String[]{"android.bluetooth.BluetoothA2dpSink", "android.bluetooth.BluetoothA2dp"}) {
                Class<?> type = Class.forName(name);
                type.getDeclaredConstructor(Context.class, BluetoothProfile.ServiceListener.class, BluetoothAdapter.class);
                type.getDeclaredMethod("getService");
                type.getDeclaredField("mProfileConnector");
            }
            Class<?> manager = Class.forName("android.bluetooth.IBluetoothManager");
            for (Method method : manager.getMethods()) {
                if (method.getName().equals("bindBluetoothProfileService")) return "framework-connector";
            }
        } catch (ReflectiveOperationException ignored) {}
        try {
            Class.forName("android.app.IActivityManager").getMethod("peekService", Intent.class, String.class, String.class);
            return "service-peek";
        } catch (ReflectiveOperationException ignored) { return "none"; }
    }

    static String abi() {
        try {
            String result = "";
            for (boolean sink : new boolean[]{true, false}) {
                Class<?> type = Class.forName(interfaceName(sink));
                Method connected = BluetoothInvocation.resolve(type, "getConnectedDevices");
                BluetoothInvocation.resolve(type, "setConnectionPolicy", BluetoothDevice.class, int.class);
                BluetoothInvocation.resolve(type, "disconnect", BluetoothDevice.class);
                BluetoothInvocation.resolve(type, "isA2dpPlaying", BluetoothDevice.class);
                String current = BluetoothInvocation.abi(connected);
                if (!result.isEmpty() && !result.equals(current)) return "mixed";
                result = current;
            }
            return result;
        } catch (ReflectiveOperationException ignored) { return "unsupported"; }
    }

    static void close() {
        if (frameworkProxy == null) return;
        try {
            Method close = frameworkProxy.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(frameworkProxy);
        } catch (Exception ignored) {}
        frameworkProxy = null;
    }

    static Object call(Object profile, boolean sink, String name, Object... args) throws Exception {
        Class<?> type = Class.forName(interfaceName(sink));
        Class<?>[] arguments = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) arguments[i] = args[i].getClass();
        return BluetoothInvocation.invoke(profile, BluetoothInvocation.resolve(type, name, arguments),
            NativeBluetooth::attribution, args);
    }

    static void attribute(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            device.getClass().getMethod("setAttributionSource", Class.forName("android.content.AttributionSource"))
                .invoke(device, attribution());
        } catch (Exception ignored) {}
    }
}
