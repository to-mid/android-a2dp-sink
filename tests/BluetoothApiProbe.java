import android.os.Build;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class BluetoothApiProbe {
    public static void main(String[] args) {
        Set<String> names = new HashSet<>(Arrays.asList("getProfile", "getProfileProxy", "getService",
            "getConnectedDevices", "setConnectionPolicy", "isA2dpPlaying", "connect", "disconnect",
            "bindBluetoothProfileService", "unbindBluetoothProfileService", "get", "getValue",
            "awaitResultNoInterrupt", "getBluetoothServiceManager", "setBluetoothServiceManager"));
        System.out.println(Build.MODEL + " API " + Build.VERSION.SDK_INT);
        for (String name : new String[]{"android.bluetooth.BluetoothAdapter", "android.bluetooth.IBluetoothA2dpSink",
                "android.bluetooth.IBluetoothA2dp", "android.bluetooth.IBluetoothManager",
                "android.bluetooth.BluetoothFrameworkInitializer", "android.os.BluetoothServiceManager",
                "com.android.modules.utils.SynchronousResultReceiver",
                "com.android.bluetooth.x.com.android.modules.utils.SynchronousResultReceiver"}) {
            try {
                Class<?> type = Class.forName(name);
                System.out.println("\n" + name);
                for (Method method : type.getDeclaredMethods()) {
                    String base = method.getName().replace("WithAttribution", "");
                    if (names.contains(base)) System.out.println(method.toGenericString());
                }
            } catch (Throwable error) { System.out.println(name + ": " + error); }
        }
    }
}
