package local.bluetooth.speaker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.os.Looper;
import java.lang.reflect.Method;

/** Read-only adapter ABI inventory unless an explicit class value is supplied. */
public final class ProfileProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        BluetoothAdapter adapter = NativeBluetooth.adapter();
        for (Method method : BluetoothAdapter.class.getDeclaredMethods())
            if (method.getName().contains("BluetoothClass")) System.out.println(method);
        System.out.println("class=" + BluetoothAdapter.class.getMethod("getBluetoothClass").invoke(adapter));
        if (args.length == 1) {
            java.lang.reflect.Constructor<BluetoothClass> constructor = BluetoothClass.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            Object desired = constructor.newInstance(Integer.decode(args[0]));
            System.out.println("set=" + BluetoothAdapter.class.getMethod("setBluetoothClass", BluetoothClass.class).invoke(adapter, desired));
            System.out.println("class=" + BluetoothAdapter.class.getMethod("getBluetoothClass").invoke(adapter));
        }
        System.exit(0);
    }
}
