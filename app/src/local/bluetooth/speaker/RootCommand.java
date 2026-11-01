package local.bluetooth.speaker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.os.Looper;
import android.os.ParcelUuid;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

public final class RootCommand {
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            String command = args.length == 0 ? "status" : args[0];
            String argument = args.length > 1 ? args[1] : "";
            JSONObject result = new JSONObject();
            if ("diagnose".equals(command) || "check-support".equals(command)) {
                finish(DeviceDiagnostics.root("diagnose".equals(command)));
            }
            if ("apply-boot".equals(command)) finish(RootController.applyBoot());
            if ("install-module".equals(command)) throw new IllegalStateException("此版本不安装模块，请运行设备检测");
            if ("mode".equals(command)) {
                RootController.changeMode(argument);
                finish(result.put("ok", true).put("mode", argument));
            }
            if ("default-mode".equals(command)) {
                RootController.setDefault(argument);
                finish(result.put("ok", true).put("defaultMode", argument));
            }
            if ("restore".equals(command)) {
                RootController.restore();
                finish(result.put("ok", true));
            }
            if ("enable".equals(command) || "disable".equals(command)) {
                RootController.power("enable".equals(command));
                finish(result.put("ok", true));
            }
            if ("restart".equals(command)) {
                RootController.restart();
                finish(result.put("ok", true));
            }
            if ("prepare-components".equals(command)) {
                if (!DeviceDiagnostics.legacyAbi()) throw new IllegalStateException("当前后端不需要组件注册修改");
                ModuleControl.prepareComponents();
                finish(result.put("ok", true));
            }
            if (!"status".equals(command) && !"connect".equals(command) && !"disconnect".equals(command)
                    && !"discoverable".equals(command) && !"rename".equals(command)) {
                throw new IllegalArgumentException("Unknown command");
            }
            String backend = RootController.backend();
            if ("none".equals(backend)) {
                if (!"status".equals(command)) throw new IllegalStateException("当前系统尚无可用控制后端，请运行设备检测");
                finish(result.put("controllerReady", false).put("backend", backend).put("enabled", false)
                    .put("profileReady", false).put("mode", "source").put("defaultMode", "source")
                    .put("name", android.os.Build.MODEL).put("address", "")
                    .put("paired", new JSONArray()).put("connected", new JSONArray()));
            }
            if (!"status".equals(command)) RootController.requireBackend();
            BluetoothAdapter adapter = NativeBluetooth.adapter();
            if (adapter == null) throw new IllegalStateException("系统蓝牙接口未初始化");
            if ("discoverable".equals(command)) {
                if (!adapter.isEnabled()) throw new IllegalStateException("请先开启蓝牙");
                Object response;
                try {
                    Method scan = BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class);
                    response = scan.invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 120);
                } catch (NoSuchMethodException missingLegacyOverload) {
                    try { BluetoothAdapter.class.getMethod("setDiscoverableTimeout", int.class).invoke(adapter, 120); }
                    catch (NoSuchMethodException durationApi) {
                        BluetoothAdapter.class.getMethod("setDiscoverableTimeout", Duration.class)
                            .invoke(adapter, Duration.ofSeconds(120));
                    }
                    response = BluetoothAdapter.class.getMethod("setScanMode", int.class)
                        .invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                }
                finish(result.put("ok", response instanceof Boolean ? response : Integer.valueOf(0).equals(response)));
            }
            if ("rename".equals(command)) {
                if (argument.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
                finish(result.put("ok", adapter.setName(argument)));
            }

            String mode = RootController.mode();
            boolean sink = "sink".equals(mode);
            if ("status".equals(command)) {
                result.put("controllerReady", true);
                result.put("backend", backend);
                result.put("configurationStrategy", RootController.configuration());
                result.put("resourceStrategy", RootController.resources());
                result.put("bluetoothPackage", RootController.targetPackage());
                result.put("profileAccess", NativeBluetooth.access());
                result.put("profileInvocation", NativeBluetooth.abi());
                result.put("profileBindingRequired", "service-peek".equals(NativeBluetooth.access()));
                result.put("enabled", adapter.isEnabled());
                result.put("mode", mode);
                result.put("defaultMode", RootController.defaultMode());
                result.put("restoreOnBoot", RootController.standalone() && RootController.settings().optBoolean("restoreOnBoot"));
                result.put("profileVerified", RootController.standalone() && RootController.settings().optBoolean("profileVerified"));
                result.put("name", adapter.getName());
                result.put("address", adapter.getAddress());
                result.put("discoverable", adapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                JSONArray uuids = new JSONArray();
                try {
                    ParcelUuid[] advertised = (ParcelUuid[]) BluetoothAdapter.class.getMethod("getUuids").invoke(adapter);
                    if (advertised != null) for (ParcelUuid uuid : advertised) uuids.put(uuid.toString());
                } catch (Exception ignored) {}
                result.put("localUuids", uuids);
                JSONArray paired = new JSONArray();
                if (adapter.isEnabled()) for (BluetoothDevice device : adapter.getBondedDevices()) paired.put(deviceJson(device));
                result.put("paired", paired);
                result.put("connected", new JSONArray());
                result.put("profileReady", false);
                if (!adapter.isEnabled()) finish(result);
            } else if (!adapter.isEnabled()) throw new IllegalStateException("请先开启蓝牙");

            Object profile = NativeBluetooth.profile(adapter, sink);
            if (profile == null) {
                if ("status".equals(command)) finish(result);
                throw new IllegalStateException("蓝牙音频服务尚未就绪");
            }
            if ("connect".equals(command) || "disconnect".equals(command)) {
                if (!BluetoothAdapter.checkBluetoothAddress(argument)) throw new IllegalArgumentException("Invalid Bluetooth address");
                BluetoothDevice device = adapter.getRemoteDevice(argument);
                Object accepted = "connect".equals(command)
                    ? NativeBluetooth.call(profile, sink, "setConnectionPolicy", device, 100)
                    : NativeBluetooth.call(profile, sink, "disconnect", device);
                result.put("ok", accepted);
            } else {
                result.put("profileReady", true);
                JSONArray connected = new JSONArray();
                @SuppressWarnings("unchecked")
                List<BluetoothDevice> list = (List<BluetoothDevice>) NativeBluetooth.call(profile, sink, "getConnectedDevices");
                for (BluetoothDevice device : list) {
                    JSONObject item = deviceJson(device);
                    boolean session = Boolean.TRUE.equals(NativeBluetooth.call(profile, sink, "isA2dpPlaying", device));
                    item.put("sessionActive", session);
                    item.put("playing", !sink && session);
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

    private static JSONObject deviceJson(BluetoothDevice device) throws Exception {
        NativeBluetooth.attribute(device);
        JSONObject item = new JSONObject();
        item.put("address", device.getAddress());
        item.put("name", device.getName() == null ? device.getAddress() : device.getName());
        BluetoothClass classification = device.getBluetoothClass();
        item.put("majorClass", classification == null ? 0 : classification.getMajorDeviceClass());
        ParcelUuid[] uuids = device.getUuids();
        boolean source = false, sink = false;
        if (uuids != null) for (ParcelUuid uuid : uuids) {
            source |= "0000110a-0000-1000-8000-00805f9b34fb".equals(uuid.toString());
            sink |= "0000110b-0000-1000-8000-00805f9b34fb".equals(uuid.toString());
        }
        return item.put("audioSource", source).put("audioSink", sink).put("unknownServices", uuids == null || uuids.length == 0);
    }

    private static void finish(JSONObject result) {
        NativeBluetooth.close();
        System.out.println(result.toString());
        System.out.flush();
        System.exit(result.has("error") ? 1 : 0);
    }
}
