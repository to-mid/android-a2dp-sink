package local.bluetooth.speaker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;

/** Creates the legacy service binding from a registered application process. */
public final class ProfileAccessService extends Service {
    private final Handler handler = new Handler();
    private boolean bound;
    private String bindingKey = "";
    private final Runnable finish = () -> stopSelf();
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {}
        public void onServiceDisconnected(ComponentName name) {}
    };

    @Override public int onStartCommand(Intent request, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("profile-access", "蓝牙音频服务", NotificationManager.IMPORTANCE_LOW));
        startForeground(3061, new Notification.Builder(this, "profile-access")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("正在连接蓝牙音频服务").build());
        if (request != null) {
            String packageName = request.getStringExtra("package");
            if (!"com.android.bluetooth".equals(packageName) && !"com.google.android.bluetooth".equals(packageName)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            boolean sink = request.getBooleanExtra("sink", true);
            String key = packageName + ":" + sink;
            if (bound && !key.equals(bindingKey)) {
                unbindService(connection);
                bound = false;
            }
            Intent intent = new Intent().setComponent(new ComponentName(packageName, sink
                ? "com.android.bluetooth.a2dpsink.A2dpSinkService" : "com.android.bluetooth.a2dp.A2dpService"));
            try {
                if (!bound) bound = bindService(intent, connection, BIND_AUTO_CREATE);
                bindingKey = key;
            }
            catch (RuntimeException unavailable) { stopSelf(); }
        }
        handler.removeCallbacks(finish);
        handler.postDelayed(finish, 8000);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacks(finish);
        if (bound) unbindService(connection);
        stopForeground(true);
        super.onDestroy();
    }
}
