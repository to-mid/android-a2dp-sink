package local.bluetooth.speaker;

import android.bluetooth.BluetoothAdapter;
import android.os.Looper;

public final class BluetoothRuntimeProbe {
    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Looper.prepareMainLooper();
            System.out.println("adapter");
            BluetoothAdapter adapter = NativeBluetooth.adapter();
            System.out.println("adapter=" + adapter);
            if (adapter == null) throw new IllegalStateException("No Bluetooth adapter");
            System.out.println("enabled=" + adapter.isEnabled());
            System.out.println("name=" + adapter.getName());
            System.out.println("address=" + adapter.getAddress());
            System.out.println("scanMode=" + adapter.getScanMode());
            System.out.println("bonded=" + adapter.getBondedDevices().size());
            boolean sink = java.util.Arrays.asList(args).contains("sink");
            System.out.println("profile " + (sink ? "sink" : "source") + " via " + NativeBluetooth.access());
            Object profile = NativeBluetooth.profile(adapter, sink);
            System.out.println("profile=" + profile);
            if (profile != null) System.out.println("connected=" + NativeBluetooth.call(profile, sink, "getConnectedDevices"));
            else if (!"service-peek".equals(NativeBluetooth.access())) throw new IllegalStateException("No profile binder");
            System.out.println("PASS: adapter metadata and current profile query");
        } catch (Throwable error) {
            exitCode = 1;
            while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null)
                error = error.getCause();
            error.printStackTrace(System.out);
        } finally { NativeBluetooth.close(); }
        System.exit(exitCode);
    }
}
