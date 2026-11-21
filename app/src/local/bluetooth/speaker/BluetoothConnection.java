package local.bluetooth.speaker;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class BluetoothConnection {
    static JSONObject connect(Object profile, boolean sink, BluetoothDevice device) throws Exception {
        NativeBluetooth.attribute(device);
        if (device.getBondState() != BluetoothDevice.BOND_BONDED)
            throw new IllegalStateException("请先在系统蓝牙设置中完成配对");
        int state = ((Number) NativeBluetooth.call(profile, sink, "getConnectionState", device)).intValue();
        Discovery discovery = new Discovery("not-needed", null);
        if (state == ConnectionAttempt.DISCONNECTED) {
            try { discovery = discover(device); }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception unavailable) { discovery = new Discovery("unavailable", null); }
        }
        ConnectionAttempt.Port port = new ConnectionAttempt.Port() {
            public int state() throws Exception {
                return ((Number) NativeBluetooth.call(profile, sink, "getConnectionState", device)).intValue();
            }
            public boolean allowed() throws Exception {
                return ((Number) NativeBluetooth.call(profile, sink, "getConnectionPolicy", device)).intValue() == 100;
            }
            public boolean allow() throws Exception {
                return Boolean.TRUE.equals(NativeBluetooth.call(profile, sink, "setConnectionPolicy", device, 100));
            }
            public boolean connect() throws Exception {
                return Boolean.TRUE.equals(NativeBluetooth.call(profile, sink, "connect", device));
            }
            public long now() { return SystemClock.elapsedRealtime(); }
            public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
        JSONObject result = new JSONObject().put("serviceDiscovery", discovery.status);
        if (discovery.uuids != null) result.put("remoteAudioSource", discovery.has("110a"))
            .put("remoteAudioSink", discovery.has("110b"));
        try {
            ConnectionAttempt.establish(port);
            return result.put("ok", true).put("connected", true).put("connectionState", 2);
        } catch (ConnectionAttempt.Failure failure) {
            String message;
            switch (failure.code) {
                case "disconnecting": message = "设备正在断开，请稍后再连接"; break;
                case "policy-refused": message = "系统未允许此设备的音频连接"; break;
                case "refused": message = "系统拒绝了音频连接请求，请检查对方是否可连接"; break;
                case "disconnected": message = "音频连接未能保持，设备已断开"; break;
                default: message = "音频连接超时，尚未建立连接";
            }
            if (discovery.uuids != null && !discovery.has(sink ? "110a" : "110b")) {
                message += sink ? "。对方本次服务发现未公布音频发送服务；若为电脑，请从电脑端连接手机"
                    : "。对方本次服务发现未公布音频接收服务";
            }
            return result.put("ok", false).put("connected", false).put("connectionError", failure.code)
                .put("error", message);
        }
    }

    private static final class Discovery {
        final String status;
        final ParcelUuid[] uuids;
        Discovery(String status, ParcelUuid[] uuids) { this.status = status; this.uuids = uuids; }
        boolean has(String shortUuid) {
            if (uuids != null) for (ParcelUuid uuid : uuids)
                if (("0000" + shortUuid + "-0000-1000-8000-00805f9b34fb").equals(uuid.toString())) return true;
            return false;
        }
    }

    private static Discovery discover(BluetoothDevice device) throws Exception {
        Context context = NativeBluetooth.context();
        HandlerThread thread = new HandlerThread("audio-service-discovery");
        thread.start();
        CountDownLatch done = new CountDownLatch(1);
        final ParcelUuid[][] response = new ParcelUuid[1][];
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                BluetoothDevice remote = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (remote == null || !device.getAddress().equals(remote.getAddress())) return;
                android.os.Parcelable[] values = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                if (values != null) {
                    java.util.ArrayList<ParcelUuid> uuids = new java.util.ArrayList<>();
                    for (android.os.Parcelable value : values) if (value instanceof ParcelUuid) uuids.add((ParcelUuid) value);
                    response[0] = uuids.toArray(new ParcelUuid[0]);
                }
                done.countDown();
            }
        };
        boolean registered = false;
        try {
            // RootCommand's main Looper does not run. Deliver SDP broadcasts on
            // a dedicated thread, and accept only a response for this address.
            context.registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_UUID),
                null, new Handler(thread.getLooper()));
            registered = true;
            if (!device.fetchUuidsWithSdp()) return new Discovery("not-started", null);
            if (!done.await(12, TimeUnit.SECONDS)) return new Discovery("timeout", null);
            return new Discovery(response[0] == null ? "failed" : "complete", response[0]);
        } catch (SecurityException | IllegalArgumentException unavailable) {
            // Discovery is an aid, not a prerequisite for profile connection.
            return new Discovery("unavailable", null);
        } finally {
            if (registered) context.unregisterReceiver(receiver);
            thread.quitSafely();
            thread.join(1000);
        }
    }
}
