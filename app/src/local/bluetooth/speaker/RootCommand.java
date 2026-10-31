package local.bluetooth.speaker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** A short-lived privileged helper. The UI never changes the system hidden-API policy. */
public final class RootCommand {
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            String command = args.length == 0 ? "status" : args[0];
            String argument = args.length > 1 ? args[1] : "";
            JSONObject result = new JSONObject();
            if ("install-module".equals(command)) {
                finish(ModuleControl.install());
            } else if ("prepare-components".equals(command)) {
                ModuleControl.prepareComponents();
                finish(result.put("ok", true));
            } else if ("mode".equals(command)) {
                ModuleControl.changeMode(argument);
                finish(result.put("ok", true).put("mode", argument));
            } else if ("default-mode".equals(command)) {
                ModuleControl.setDefault(argument);
                finish(result.put("ok", true).put("defaultMode", argument));
            } else if ("reboot".equals(command)) {
                new ProcessBuilder("/system/bin/reboot").start();
                finish(result.put("ok", true));
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) throw new IllegalStateException("No Bluetooth adapter");
            if ("enable".equals(command)) {
                finish(result.put("ok", adapter.isEnabled() || adapter.enable()));
            } else if ("disable".equals(command)) {
                finish(result.put("ok", !adapter.isEnabled() || adapter.disable()));
            } else if ("restart".equals(command)) {
                boolean wasOn = adapter.isEnabled();
                if (wasOn) {
                    adapter.disable();
                    long deadline = System.currentTimeMillis() + 12000;
                    while (adapter.getState() != BluetoothAdapter.STATE_OFF
                            && adapter.getState() != 15 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(200);
                    }
                    if (adapter.getState() != BluetoothAdapter.STATE_OFF && adapter.getState() != 15) {
                        throw new IllegalStateException("Bluetooth disable timed out");
                    }
                }
                finish(result.put("ok", !wasOn || adapter.enable()));
            } else if ("discoverable".equals(command)) {
                if (!adapter.isEnabled()) throw new IllegalStateException("请先开启蓝牙");
                Object response;
                try {
                    Method scan = BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class);
                    response = scan.invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 120);
                } catch (NoSuchMethodException missingLegacyOverload) {
                    BluetoothAdapter.class.getMethod("setDiscoverableTimeout", int.class).invoke(adapter, 120);
                    response = BluetoothAdapter.class.getMethod("setScanMode", int.class)
                        .invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                }
                finish(result.put("ok", response instanceof Boolean ? response : Integer.valueOf(0).equals(response)));
            } else if ("rename".equals(command)) {
                if (argument.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
                finish(result.put("ok", adapter.setName(argument)));
            } else if (!"status".equals(command) && !"connect".equals(command) && !"disconnect".equals(command)) {
                throw new IllegalArgumentException("Unknown command");
            }

            String mode = systemProperty("persist.vendor.service.bt.a2dp.sink").equals("true") ? "sink" : "source";
            boolean sink = "sink".equals(mode);
            if ("status".equals(command)) {
                result.put("enabled", adapter.isEnabled());
                result.put("mode", mode);
                result.put("defaultMode", ModuleControl.defaultMode());
                result.put("module", ModuleControl.enabled());
                result.put("moduleInstalled", ModuleControl.ACTIVE.isDirectory() || ModuleControl.STAGED.isDirectory());
                result.put("moduleVersion", ModuleControl.version());
                result.put("rebootRequired", ModuleControl.STAGED.isDirectory());
                result.put("name", adapter.getName());
                result.put("address", adapter.getAddress());
                result.put("discoverable", adapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                JSONArray uuids = new JSONArray();
                ParcelUuid[] advertised = (ParcelUuid[]) BluetoothAdapter.class.getMethod("getUuids").invoke(adapter);
                if (advertised != null) for (ParcelUuid uuid : advertised) uuids.put(uuid.toString());
                result.put("localUuids", uuids);
                JSONArray paired = new JSONArray();
                for (BluetoothDevice device : adapter.getBondedDevices()) paired.put(deviceJson(device));
                result.put("paired", paired);
                result.put("connected", new JSONArray());
                result.put("profileReady", false);
                if (!adapter.isEnabled()) finish(result);
            } else if (!adapter.isEnabled()) {
                throw new IllegalStateException("请先开启蓝牙");
            }

            Object profile = peekProfile(sink);
            if (profile == null) {
                if ("status".equals(command)) finish(result);
                throw new IllegalStateException("蓝牙音频服务尚未就绪，请稍后重试");
            }
            Class<?> type = Class.forName(sink ? "android.bluetooth.IBluetoothA2dpSink" : "android.bluetooth.IBluetoothA2dp");
            if ("connect".equals(command) || "disconnect".equals(command)) {
                if (!BluetoothAdapter.checkBluetoothAddress(argument)) throw new IllegalArgumentException("Invalid Bluetooth address");
                BluetoothDevice device = adapter.getRemoteDevice(argument);
                if ("connect".equals(command)) {
                    result.put("ok", type.getMethod("setConnectionPolicy", BluetoothDevice.class, int.class)
                        .invoke(profile, device, 100));
                } else {
                    result.put("ok", type.getMethod("disconnect", BluetoothDevice.class).invoke(profile, device));
                }
            } else {
                result.put("profileReady", true);
                result.put("sinkReady", sink);
                result.put("sourceReady", !sink);
                JSONArray connected = new JSONArray();
                @SuppressWarnings("unchecked")
                List<BluetoothDevice> connectedDevices = (List<BluetoothDevice>) type.getMethod("getConnectedDevices").invoke(profile);
                for (BluetoothDevice device : connectedDevices) {
                    JSONObject item = deviceJson(device);
                    item.put("playing", type.getMethod("isA2dpPlaying", BluetoothDevice.class).invoke(profile, device));
                    connected.put(item);
                }
                result.put("connected", connected);
            }
            finish(result);
        } catch (Throwable error) {
            try {
                while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
                finish(new JSONObject().put("error", error.toString()));
            } catch (Throwable ignored) { System.exit(1); }
        }
    }

    private static String systemProperty(String name) throws Exception {
        return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
            .invoke(null, name, "");
    }

    private static JSONObject deviceJson(BluetoothDevice device) throws Exception {
        JSONObject item = new JSONObject();
        item.put("address", device.getAddress());
        item.put("name", device.getName() == null ? device.getAddress() : device.getName());
        ParcelUuid[] uuids = device.getUuids();
        boolean source = false;
        boolean sink = false;
        if (uuids != null) for (ParcelUuid uuid : uuids) {
            if ("0000110a-0000-1000-8000-00805f9b34fb".equals(uuid.toString())) source = true;
            if ("0000110b-0000-1000-8000-00805f9b34fb".equals(uuid.toString())) sink = true;
        }
        item.put("audioSource", source);
        item.put("audioSink", sink);
        item.put("unknownServices", uuids == null || uuids.length == 0);
        return item;
    }

    private static Object peekProfile(boolean sink) throws Exception {
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.bluetooth", sink
            ? "com.android.bluetooth.a2dpsink.A2dpSinkService" : "com.android.bluetooth.a2dp.A2dpService"));
        // The activity binds with its real ApplicationThread; the privileged helper uses that binder.
        IBinder binder = (IBinder) Class.forName("android.app.IActivityManager")
            .getMethod("peekService", Intent.class, String.class, String.class).invoke(manager, intent, null, "android");
        if (binder == null) return null;
        return Class.forName(sink ? "android.bluetooth.IBluetoothA2dpSink$Stub" : "android.bluetooth.IBluetoothA2dp$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static void finish(JSONObject result) {
        System.out.println(result.toString());
        System.out.flush();
        System.exit(result.has("error") ? 1 : 0);
    }
}
