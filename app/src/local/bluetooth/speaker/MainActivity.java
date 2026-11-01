package local.bluetooth.speaker;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends Activity {
    private static final String[] DEFAULT_VALUES = {"source", "sink", "last"};
    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<View> controls = new ArrayList<>();
    private TextView name, address, state, playing, backend, notice, deviceHeading, diagnosisTitle, diagnosisSummary;
    private LinearLayout devices;
    private RadioGroup modes;
    private RadioButton sourceMode, sinkMode;
    private Spinner bootMode;
    private Switch bluetooth;
    private Button pairing, detect, report;
    private SeekBar volume;
    private JSONObject current = new JSONObject();
    private JSONObject diagnosis = new JSONObject();
    private boolean busy, visible, rendering, profileBound, canControl;
    private String boundMode = "";
    private long pairingUntil;
    private final Runnable refreshAfterEvent = () -> refresh();
    private final ServiceConnection profileConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName component, IBinder binder) {
            handler.postDelayed(refreshAfterEvent, 250);
        }
        @Override public void onServiceDisconnected(ComponentName component) {
            handler.postDelayed(refreshAfterEvent, 500);
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(refreshAfterEvent);
            handler.postDelayed(refreshAfterEvent, 650);
        }
    };
    private final Runnable polling = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (!busy && canControl) refresh();
            handler.postDelayed(this, 10000);
        }
    };
    private final Runnable countdown = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            long left = Math.max(0, (pairingUntil - System.currentTimeMillis() + 999) / 1000);
            pairing.setText(left > 0 ? "可被发现 · " + left + " 秒"
                : current.optBoolean("discoverable") ? "可被发现" : "开始配对");
            if (left > 0) handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        createDeviceProtectedStorageContext().getFilesDir();
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(24));
        scroll.addView(root);
        setContentView(scroll);
        TextView title = text("蓝牙音频接收器", 24, Color.BLACK);
        title.setCompoundDrawablesWithIntrinsicBounds(R.drawable.speaker, 0, 0, 0);
        title.setCompoundDrawablePadding(dp(10));
        root.addView(title);
        add(root, text("作者：橘橙的记忆", 13, Color.DKGRAY), 5);
        name = text(Build.MODEL, 19, Color.BLACK);
        add(root, name, 18);
        address = text("Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT, 12, Color.DKGRAY);
        add(root, address, 4);

        LinearLayout diagnosisRow = row();
        diagnosisTitle = text("正在基本检测…", 16, Color.rgb(160, 88, 32));
        diagnosisRow.addView(diagnosisTitle, new LinearLayout.LayoutParams(0, -2, 1));
        detect = plainButton("完整检测", () -> diagnose(true));
        diagnosisRow.addView(detect, new LinearLayout.LayoutParams(-2, dp(44)));
        report = plainButton("报告", this::showReport);
        report.setEnabled(false);
        diagnosisRow.addView(report, new LinearLayout.LayoutParams(dp(64), dp(44)));
        add(root, diagnosisRow, 16);
        diagnosisSummary = text("", 13, Color.DKGRAY);
        add(root, diagnosisSummary, 4);

        add(root, text("当前模式", 15, Color.BLACK), 18);
        modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        sourceMode = radio("普通发送");
        sinkMode = radio("音箱接收");
        modes.addView(sourceMode, weight(48));
        modes.addView(sinkMode, weight(48));
        modes.setOnCheckedChangeListener((group, checked) -> {
            if (rendering || busy) return;
            String chosen = checked == sinkMode.getId() ? "sink" : "source";
            if (chosen.equals(current.optString("mode", "source"))) return;
            if (!canControl) { toast("请先完成设备检测"); return; }
            releaseProfile();
            run("mode", chosen, null);
        });
        add(root, modes, 2);
        LinearLayout defaults = row();
        defaults.addView(text("开机默认", 15, Color.BLACK), new LinearLayout.LayoutParams(dp(86), -2));
        bootMode = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{"普通发送", "音箱接收", "记住上次"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bootMode.setAdapter(choices);
        bootMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = DEFAULT_VALUES[position];
                if (!rendering && !busy && canControl
                        && !value.equals(current.optString("defaultMode", "source"))) {
                    run("default-mode", value, result -> {
                        try { current.put("defaultMode", value); } catch (Exception ignored) {}
                    });
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        controls.add(bootMode);
        defaults.addView(bootMode, new LinearLayout.LayoutParams(0, dp(48), 1));
        add(root, defaults, 4);
        backend = text("", 13, Color.DKGRAY);
        add(root, backend, 4);
        state = text("等待检测", 15, Color.rgb(160, 88, 32));
        add(root, state, 12);
        playing = text("", 15, Color.BLACK);
        add(root, playing, 7);
        notice = text("", 13, Color.DKGRAY);
        notice.setTextIsSelectable(true);
        notice.setVisibility(View.GONE);
        add(root, notice, 6);

        LinearLayout actions = row();
        pairing = button("开始配对", () -> run("discoverable", null, result -> {
            pairingUntil = System.currentTimeMillis() + 120000;
            handler.removeCallbacks(countdown);
            handler.post(countdown);
        }));
        actions.addView(pairing, weight(52));
        bluetooth = new Switch(this);
        bluetooth.setText("蓝牙");
        bluetooth.setTextSize(14);
        bluetooth.setPadding(dp(16), 0, 0, 0);
        bluetooth.setOnCheckedChangeListener((view, enabled) -> {
            if (!rendering && !busy && canControl && enabled != current.optBoolean("enabled")) {
                releaseProfile();
                run(enabled ? "enable" : "disable", null, null);
            }
        });
        controls.add(bluetooth);
        actions.addView(bluetooth, new LinearLayout.LayoutParams(dp(122), dp(52)));
        add(root, actions, 10);
        add(root, text("媒体音量", 15, Color.BLACK), 18);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        volume = new SeekBar(this);
        volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        add(root, volume, 6);
        deviceHeading = text("已配对设备", 18, Color.BLACK);
        add(root, deviceHeading, 18);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        add(root, devices, 6);
        LinearLayout tools = row();
        tools.addView(button("修改名称", this::rename), weight(50));
        tools.addView(button("刷新", this::refresh), weight(50));
        add(root, tools, 18);
        add(root, button("重启蓝牙", () -> new AlertDialog.Builder(this)
            .setTitle("重启蓝牙").setMessage("当前蓝牙连接会断开。")
            .setNegativeButton("取消", null)
            .setPositiveButton("重启", (dialog, which) -> {
                releaseProfile();
                run("restart", null, null);
            }).show()), 4);
        add(root, button("恢复原配置", () -> new AlertDialog.Builder(this)
            .setTitle("恢复原配置").setMessage("恢复首次切换前保存的蓝牙配置，并关闭开机自动恢复。当前连接会断开。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复", (dialog, which) -> {
                releaseProfile();
                run("restore", null, null);
            }).show()), 4);
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED");
        filter.addAction("android.bluetooth.device.action.BOND_STATE_CHANGED");
        filter.addAction("android.bluetooth.device.action.UUID");
        registerReceiver(receiver, filter);
        gates();
        diagnose(false);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        handler.post(polling);
        handler.post(countdown);
    }
    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(polling);
        handler.removeCallbacks(countdown);
        super.onPause();
    }
    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        releaseProfile();
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void diagnose(boolean full) {
        if (busy || isDestroyed()) return;
        busy = true;
        gates();
        message(full ? "正在检测系统组件和 root 能力…" : "正在读取设备信息…");
        worker.execute(() -> {
            JSONObject result;
            try {
                JSONObject basic = DeviceDiagnostics.basic(this);
                if (full) {
                    try {
                        JSONObject complete = RootClient.call(this, "diagnose", null);
                        result = complete.has("error") ? DeviceDiagnostics.denied(basic, complete.optString("error")) : complete;
                    } catch (Exception error) {
                        result = DeviceDiagnostics.denied(basic, String.valueOf(error.getMessage()));
                    }
                } else result = basic;
            } catch (Exception error) {
                result = new JSONObject();
                try { result.put("title", "检测失败").put("canControl", false).put("error", error.toString()); }
                catch (Exception ignored) {}
            }
            JSONObject response = result;
            handler.post(() -> {
                if (isDestroyed()) return;
                busy = false;
                diagnosis = response;
                canControl = response.optBoolean("canControl");
                renderDiagnosis();
                gates();
                if (canControl) handler.post(refreshAfterEvent);
            });
        });
    }

    private void renderDiagnosis() {
        diagnosisTitle.setText(diagnosis.optString("title", "尚未检测"));
        diagnosisTitle.setTextColor(canControl ? Color.rgb(24, 121, 78) : Color.rgb(160, 88, 32));
        String summary = "Android " + diagnosis.optString("android", Build.VERSION.RELEASE)
            + " · API " + diagnosis.optInt("sdk", Build.VERSION.SDK_INT)
            + " · " + diagnosis.optString("rootManager", "待检测");
        JSONArray reasons = diagnosis.optJSONArray("reasons");
        if (reasons != null && reasons.length() > 0) summary += "\n" + reasons.optString(0);
        String bootError = createDeviceProtectedStorageContext()
            .getSharedPreferences("startup", MODE_PRIVATE).getString("lastError", "");
        if (canControl && !bootError.isEmpty()) summary += "\n上次开机恢复失败：" + bootError;
        diagnosisSummary.setText(summary);
        message(diagnosis.has("rootError") ? "请在 root 管理器中允许本应用，再运行完整检测。"
            : diagnosis.has("error") ? diagnosis.optString("error") : "");
        if (!canControl) {
            releaseProfile();
            backend.setText("");
            state.setText("控制功能未启用");
            playing.setText("");
            devices.removeAllViews();
        }
    }

    private void showReport() {
        TextView content = text(DeviceDiagnostics.text(diagnosis), 14, Color.BLACK);
        content.setTextIsSelectable(true);
        content.setPadding(dp(18), dp(12), dp(18), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("设备检测报告").setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制报告", (dialog, which) -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("设备检测报告", DeviceDiagnostics.text(diagnosis)));
                toast("已复制");
            }).show();
    }

    private void gates() {
        boolean ready = !busy && canControl && current.optBoolean("controllerReady");
        for (View control : controls) control.setEnabled(ready);
        detect.setEnabled(!busy);
        report.setEnabled(!busy && diagnosis.length() > 0);
        volume.setEnabled(ready);
        if (pairing != null) pairing.setEnabled(ready && current.optBoolean("enabled"));
        for (View control : controls) {
            if ("device".equals(control.getTag())) control.setEnabled(ready && current.optBoolean("profileReady"));
        }
    }

    private void ensureProfile(String mode, boolean enabled) {
        if (!enabled || !current.optBoolean("profileBindingRequired")) {
            releaseProfile();
            return;
        }
        if (profileBound && mode.equals(boundMode)) return;
        releaseProfile();
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(current.optString("bluetoothPackage", "com.android.bluetooth"), "sink".equals(mode)
            ? "com.android.bluetooth.a2dpsink.A2dpSinkService" : "com.android.bluetooth.a2dp.A2dpService"));
        try {
            profileBound = bindService(intent, profileConnection, BIND_AUTO_CREATE);
            if (profileBound) boundMode = mode;
        } catch (SecurityException ignored) {}
    }
    private void releaseProfile() {
        if (profileBound) {
            try { unbindService(profileConnection); } catch (IllegalArgumentException ignored) {}
        }
        profileBound = false;
        boundMode = "";
    }
    private void refresh() { if (canControl) run("status", null, null); }

    private void run(String command, String argument, Consumer<JSONObject> success) {
        if (busy || isDestroyed() || !canControl) return;
        busy = true;
        gates();
        message("status".equals(command) ? "" : "mode".equals(command)
            ? "正在切换蓝牙配置…" : "正在处理…");
        worker.execute(() -> {
            JSONObject result;
            try { result = RootClient.call(this, command, argument); }
            catch (Exception error) {
                result = new JSONObject();
                try { result.put("error", error.getMessage()); } catch (Exception ignored) {}
            }
            JSONObject response = result;
            handler.post(() -> {
                if (isDestroyed()) return;
                busy = false;
                if (response.has("error")) {
                    message("操作失败：" + response.optString("error"));
                    if ("mode".equals(command)) handler.postDelayed(refreshAfterEvent, 1500);
                } else if ("status".equals(command)) {
                    current = response;
                    render();
                } else if (response.has("ok") && !response.optBoolean("ok")) {
                    message("系统未接受操作，请刷新检查状态。");
                } else {
                    if (success != null) success.accept(response);
                    if ("mode".equals(command) || "default-mode".equals(command)) startup(true);
                    if ("restore".equals(command)) startup(false);
                    message("操作已完成");
                    handler.postDelayed(refreshAfterEvent, 500);
                }
                gates();
            });
        });
    }

    private void startup(boolean value) {
        String selectedBackend = diagnosis.optString("backend");
        createDeviceProtectedStorageContext().getSharedPreferences("startup", MODE_PRIVATE)
            .edit().putBoolean("enabled", value && ("aosp-root-apk".equals(selectedBackend)
                || "resource-root-apk".equals(selectedBackend))).apply();
    }

    private void render() {
        rendering = true;
        String mode = current.optString("mode", "source");
        boolean sink = "sink".equals(mode);
        boolean enabled = current.optBoolean("enabled");
        boolean ready = current.optBoolean("profileReady");
        name.setText(current.optString("name", Build.MODEL));
        address.setText(current.optString("address"));
        modes.check(sink ? sinkMode.getId() : sourceMode.getId());
        bluetooth.setChecked(enabled);
        String selected = current.optString("defaultMode", "source");
        for (int i = 0; i < DEFAULT_VALUES.length; i++) if (DEFAULT_VALUES[i].equals(selected)) bootMode.setSelection(i);
        boolean standalone = "aosp-root-apk".equals(current.optString("backend"))
            || "resource-root-apk".equals(current.optString("backend"));
        backend.setText(standalone ? "控制方式：Root APK（无需模块）" : "控制方式：已有旧系统兼容配置");
        startup(current.optBoolean("restoreOnBoot"));
        state.setText(!enabled ? "蓝牙已关闭" : ready
            ? sink ? "音箱接收已就绪" : "普通发送已就绪" : "正在准备蓝牙音频服务…");
        state.setTextColor(ready ? Color.rgb(24, 121, 78) : Color.rgb(160, 88, 32));
        JSONArray connected = current.optJSONArray("connected");
        StringBuilder message = new StringBuilder();
        if (connected != null) for (int i = 0; i < connected.length(); i++) {
            JSONObject device = connected.optJSONObject(i);
            if (i > 0) message.append('\n');
            message.append(device.optString("name")).append(device.optBoolean("playing") ? " · 正在播放" : " · 已连接");
        }
        if (sink && connected != null) {
            boolean session = false;
            for (int i = 0; i < connected.length(); i++) session |= connected.optJSONObject(i).optBoolean("sessionActive");
            if (session) message.append("\n蓝牙音频会话活动");
        }
        playing.setText(message.length() == 0 ? sink ? "未连接音频源" : "未连接耳机或音箱" : message.toString());
        deviceHeading.setText(sink ? "已配对音频源" : "已配对耳机／音箱");
        for (int i = controls.size() - 1; i >= 0; i--) if ("device".equals(controls.get(i).getTag())) controls.remove(i);
        devices.removeAllViews();
        JSONArray paired = current.optJSONArray("paired");
        int displayed = 0;
        if (paired != null) for (int i = 0; i < paired.length(); i++) {
            JSONObject device = paired.optJSONObject(i);
            String mac = device.optString("address");
            boolean isConnected = false;
            if (connected != null) for (int j = 0; j < connected.length(); j++) {
                if (mac.equals(connected.optJSONObject(j).optString("address"))) isConnected = true;
            }
            boolean advertised = device.optBoolean(sink ? "audioSource" : "audioSink");
            boolean possibleSource = sink && (device.optInt("majorClass") == 0x100 || device.optInt("majorClass") == 0x200);
            if (!isConnected && !advertised && !possibleSource && !device.optBoolean("unknownServices")) continue;
            displayed++;
            LinearLayout entry = row();
            entry.setPadding(0, dp(8), 0, dp(8));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(device.optString("name"), 15, Color.BLACK));
            labels.addView(text(mac, 12, Color.DKGRAY));
            if (!isConnected && !advertised) labels.addView(text("音频能力待确认", 12, Color.rgb(160, 88, 32)));
            entry.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            boolean disconnect = isConnected;
            Button action = button(disconnect ? "断开" : "连接",
                () -> run(disconnect ? "disconnect" : "connect", mac, null));
            action.setTag("device");
            entry.addView(action, new LinearLayout.LayoutParams(dp(80), dp(48)));
            devices.addView(entry);
            View divider = new View(this);
            divider.setBackgroundColor(Color.rgb(232, 235, 233));
            devices.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        if (displayed == 0) devices.addView(text("暂无匹配的已配对设备", 14, Color.DKGRAY));
        rendering = false;
        ensureProfile(mode, enabled);
        handler.post(countdown);
    }

    private void rename() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current.optString("name", ""));
        new AlertDialog.Builder(this).setTitle("蓝牙名称").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                String value = input.getText().toString().trim();
                if (!value.isEmpty()) run("rename", value, null); else toast("名称不能为空");
            }).show();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void message(String value) {
        notice.setText(value);
        notice.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private RadioButton radio(String label) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(14);
        button.setLetterSpacing(0);
        controls.add(button);
        return button;
    }
    private Button plainButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setLetterSpacing(0);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }
    private Button button(String label, Runnable action) {
        Button button = plainButton(label, action);
        controls.add(button);
        return button;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLetterSpacing(0);
        return view;
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private LinearLayout.LayoutParams weight(int height) { return new LinearLayout.LayoutParams(0, dp(height), 1); }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
