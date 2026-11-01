package local.bluetooth.speaker;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DeviceDiagnostics {
    static final String FOCUS = "a2dp_sink_automatically_request_audio_focus";
    private static final String[] PACKAGES = {"com.android.bluetooth", "com.google.android.bluetooth"};
    private static final String[] CODE = {
        "Lcom/android/bluetooth/a2dpsink/A2dpSinkService;",
        "Lcom/android/bluetooth/avrcpcontroller/AvrcpControllerService;"
    };
    private static final String[] NATIVE = {
        "btif_a2dp_sink", "com/android/bluetooth/a2dpsink/", "A2dpSinkNativeInterface",
        "persist.vendor.service.bt.a2dp.sink", "persist.vendor.bt.a2dp.sink_conn"
    };
    private static String writer;

    static boolean legacyAbi() {
        try {
            Class.forName("android.bluetooth.IBluetoothA2dpSink", false,
                DeviceDiagnostics.class.getClassLoader()).getMethod("getConnectedDevices");
            return true;
        } catch (Throwable ignored) { return false; }
    }

    static boolean modernAbi() {
        return !"unsupported".equals(NativeBluetooth.abi()) && !"direct".equals(NativeBluetooth.abi())
            && !"none".equals(NativeBluetooth.access());
    }

    static JSONObject identity() throws Exception {
        JSONObject report = new JSONObject();
        report.put("schema", 3);
        report.put("model", Build.MODEL);
        report.put("manufacturer", Build.MANUFACTURER);
        report.put("device", Build.DEVICE);
        report.put("android", Build.VERSION.RELEASE);
        report.put("sdk", Build.VERSION.SDK_INT);
        report.put("hardware", Build.HARDWARE);
        report.put("abis", new JSONArray(Build.SUPPORTED_ABIS));
        report.put("rootAvailable", JSONObject.NULL);
        report.put("rootManager", "待检测");
        report.put("rootVersion", "");
        report.put("bluetoothHardware", JSONObject.NULL);
        report.put("bluetoothPackage", "");
        report.put("apk", "");
        report.put("sinkCode", "unknown");
        report.put("avrcpCode", "unknown");
        report.put("nativeSink", "unknown");
        report.put("nativeLibrary", "");
        report.put("sinkResource", "unknown");
        report.put("focusResource", "unknown");
        report.put("modernApi", modernAbi());
        report.put("profileAccess", NativeBluetooth.access());
        report.put("profileInvocation", NativeBluetooth.abi());
        report.put("level", "basic");
        return report;
    }

    static JSONObject basic(Context context) throws Exception {
        JSONObject report = identity();
        PackageManager manager = context.getPackageManager();
        report.put("bluetoothHardware", manager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH));
        for (String packageName : PACKAGES) {
            try {
                ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
                report.put("bluetoothPackage", packageName);
                report.put("apk", info.sourceDir);
                try {
                    Resources resources = manager.getResourcesForApplication(info);
                    report.put("sinkResource", resource(resources, packageName, "profile_supported_a2dp_sink"));
                    report.put("focusResource", resource(resources, packageName, FOCUS));
                } catch (Exception ignored) {}
                inspectApk(report, info.sourceDir);
                inspectNative(report, info.nativeLibraryDir);
                break;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        evaluate(report);
        return report;
    }

    static JSONObject root() throws Exception {
        return root(false);
    }

    static JSONObject root(boolean observeAudio) throws Exception {
        JSONObject report = identity();
        report.put("level", "root");
        report.put("rootAvailable", Process.myUid() == 0);
        if (Process.myUid() != 0) { evaluate(report); return report; }
        String id = command("/system/bin/id");
        String magisk = command("/system/bin/sh", "-c", "command -v magisk >/dev/null && magisk -v");
        String ksud = new File("/data/adb/ksud").canExecute()
            ? command("/data/adb/ksud", "--version") : "";
        if (!ksud.isEmpty() || id.contains("u:r:ksu:")) {
            report.put("rootManager", "KernelSU");
            report.put("rootVersion", ksud);
        } else if (!magisk.isEmpty()) {
            report.put("rootManager", "Magisk");
            report.put("rootVersion", magisk);
        } else if (new File("/data/adb/ap/bin/apd").exists()) {
            report.put("rootManager", "APatch");
        } else report.put("rootManager", "其他 root");
        report.put("propertyWriter", propertyWriter());
        String features = command("/system/bin/pm", "list", "features");
        if (!features.isEmpty()) report.put("bluetoothHardware", features.contains("feature:android.hardware.bluetooth\n"));
        for (String packageName : PACKAGES) {
            String path = command("/system/bin/pm", "path", packageName);
            for (String line : path.split("\n")) {
                if (!line.startsWith("package:") || !line.endsWith(".apk")) continue;
                report.put("bluetoothPackage", packageName);
                report.put("apk", line.substring(8).trim());
                break;
            }
            if (!report.optString("apk").isEmpty()) break;
        }
        String apk = report.optString("apk");
        if (!apk.isEmpty()) {
            inspectApk(report, apk);
            inspectNative(report, "");
            String packageName = report.optString("bluetoothPackage");
            report.put("sinkResource", rootResource(packageName, "profile_supported_a2dp_sink"));
            report.put("focusResource", rootResource(packageName, FOCUS));
            report.put("sourceResource", rootResource(packageName, "profile_supported_a2dp"));
            report.put("targetResource", rootResource(packageName, "profile_supported_avrcp_target"));
            report.put("controllerResource", rootResource(packageName, "profile_supported_avrcp_controller"));
        }
        JSONObject properties = new JSONObject();
        for (String name : new String[]{"bluetooth.profile.a2dp.source.enabled", "bluetooth.profile.a2dp.sink.enabled",
                "bluetooth.profile.avrcp.target.enabled", "bluetooth.profile.avrcp.controller.enabled",
                "persist.vendor.service.bt.a2dp.sink"}) {
            properties.put(name, command("/system/bin/getprop", name));
        }
        report.put("properties", properties);
        report.put("legacyModule", ModuleControl.enabled());
        report.put("packagedMapping", LegacyResources.supported(report.optString("bluetoothPackage")));
        report.put("fabricatedOverlay", fabricatedOverlay());
        evaluate(report);
        report.put("capabilityPass", report.optBoolean("canControl"));
        if (report.optBoolean("canControl")) {
            try {
                BluetoothAdapter adapter = NativeBluetooth.adapter();
                if (adapter == null) throw new IllegalStateException("没有可用的蓝牙适配器");
                adapter.getName();
                adapter.getAddress();
                if (adapter.isEnabled()) {
                    boolean sink = "true".equals(properties.optString(
                        "profile-properties".equals(report.optString("configurationStrategy"))
                            ? "bluetooth.profile.a2dp.sink.enabled" : "persist.vendor.service.bt.a2dp.sink"));
                    Object profile = NativeBluetooth.profile(adapter, sink, report.optString("bluetoothPackage"));
                    if (profile != null) {
                        NativeBluetooth.call(profile, sink, "getConnectedDevices");
                        report.put("profileProbe", "call-succeeded");
                    } else if ("service-peek".equals(report.optString("profileAccess"))) {
                        report.put("profileProbe", "binding-required");
                    } else {
                        String dump = command("/system/bin/dumpsys", "bluetooth_manager");
                        String expected = sink ? "Profile: A2dpSinkService" : "Profile: A2dpService";
                        if (dump.contains(expected + "\n"))
                            throw new IllegalStateException("音频服务已运行，但无法取得控制接口");
                        report.put("profileProbe", "not-ready");
                    }
                } else report.put("profileProbe", "bluetooth-off");
                report.put("runtimeControllerReady", true);
            } catch (Throwable error) {
                while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null)
                    error = error.getCause();
                report.put("runtimeControllerReady", false).put("controllerError", error.toString())
                    .put("canControl", false).put("verdict", "controller_error").put("title", "控制接口验证失败");
                report.put("reasons", new JSONArray().put("实际调用失败：" + error));
            } finally { NativeBluetooth.close(); }
            report.put("processCompatibility", NativeBluetooth.processCompatibility());
        }
        String sinkFlag = "aosp-root-apk".equals(report.optString("backend"))
            ? "bluetooth.profile.a2dp.sink.enabled" : "persist.vendor.service.bt.a2dp.sink";
        if (observeAudio && report.optBoolean("canControl") && "true".equals(properties.optString(sinkFlag))) {
            JSONObject audio = AudioRuntime.bluetoothMedia();
            report.put("runtimeAudio", audio);
            report.put("playbackObserved", audio.optBoolean("active"));
        }
        return report;
    }

    static String propertyWriter() {
        if (writer != null) return writer;
        for (String path : new String[]{"/data/adb/ksu/bin/resetprop", "/system/bin/resetprop",
                "/sbin/resetprop", "/debug_ramdisk/resetprop", "/data/adb/magisk/resetprop"}) {
            if (new File(path).canExecute()) { writer = path; return writer; }
        }
        String magiskPath = command("magisk", "--path");
        if (magiskPath.startsWith("/") && !magiskPath.contains("\n")) {
            File candidate = new File(magiskPath, "resetprop");
            if (candidate.canExecute()) { writer = candidate.getPath(); return writer; }
        }
        writer = "";
        return writer;
    }

    static String command(String... args) {
        try { return ModuleControl.execute(12, args).trim(); }
        catch (Exception ignored) { return ""; }
    }

    private static boolean fabricatedOverlay() {
        String help;
        try { help = ModuleControl.execute(8, "/system/bin/cmd", "overlay", "help"); }
        // Some Android releases print valid help and exit with -1.
        catch (Exception error) { help = String.valueOf(error.getMessage()); }
        return help.contains("Overlay manager") && help.contains("\n  fabricate ");
    }

    private static String resource(Resources resources, String pkg, String name) {
        try {
            int id = resources.getIdentifier(name, "bool", pkg);
            return id == 0 ? "missing" : String.valueOf(resources.getBoolean(id));
        } catch (Exception ignored) { return "unknown"; }
    }

    private static String rootResource(String pkg, String name) {
        try {
            String value = ModuleControl.execute(8, "/system/bin/cmd", "overlay", "lookup",
                pkg, pkg + ":bool/" + name).trim();
            return "true".equals(value) || "false".equals(value) ? value : "unknown";
        } catch (Exception error) {
            String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
            return message.contains("failed to get the resource") ? "missing" : "unknown";
        }
    }

    private static void inspectApk(JSONObject report, String path) throws Exception {
        boolean sink = false, avrcp = false, complete = true, foundDex = false;
        try (ZipFile apk = new ZipFile(path)) {
            java.util.Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                foundDex = true;
                boolean[] markers;
                try (InputStream input = apk.getInputStream(entry)) { markers = scan(input, CODE, 67108864); }
                sink |= markers[0];
                avrcp |= markers[1];
                complete &= markers[2];
                if (sink && avrcp) break;
            }
        } catch (Exception ignored) { complete = false; }
        report.put("sinkCode", sink ? "present" : foundDex && complete ? "missing" : "unknown");
        report.put("avrcpCode", avrcp ? "present" : foundDex && complete ? "missing" : "unknown");
    }

    private static void inspectNative(JSONObject report, String nativeDir) throws Exception {
        List<String> candidates = new ArrayList<>();
        if (nativeDir != null && !nativeDir.isEmpty()) {
            candidates.add(nativeDir + "/libbluetooth_jni.so");
            candidates.add(nativeDir + "/libbluetooth_qti_jni.so");
        }
        for (String path : new String[]{"/apex/com.android.btservices/lib64/libbluetooth_jni.so",
                "/apex/com.android.btservices/lib/libbluetooth_jni.so",
                "/system_ext/lib64/libbluetooth_qti.so", "/system/lib64/libbluetooth_qti_jni.so",
                "/system/lib64/libbluetooth_jni.so", "/system/lib/libbluetooth_jni.so"}) candidates.add(path);
        boolean inspected = false, incomplete = false;
        for (String path : candidates) {
            File file = new File(path);
            if (!file.isFile()) continue;
            try (InputStream input = new FileInputStream(file)) {
                boolean[] markers = scan(input, NATIVE, 67108864);
                inspected = true;
                incomplete |= !markers[NATIVE.length];
                for (int i = 0; i < 3; i++) if (markers[i]) {
                    report.put("nativeSink", "present");
                    report.put("nativeLibrary", path);
                    report.put("vendorPropertyReferences", markers[3] && markers[4]);
                    return;
                }
            } catch (Exception ignored) { incomplete = true; }
        }
        report.put("nativeSink", inspected && !incomplete ? "missing" : "unknown");
    }

    // Static markers are evidence of packaged code, not proof of successful audio playback.
    private static boolean[] scan(InputStream input, String[] markers, int limit) throws Exception {
        boolean[] result = new boolean[markers.length + 1];
        byte[] buffer = new byte[32768];
        String tail = "";
        int total = 0, count, longest = 0;
        for (String marker : markers) longest = Math.max(longest, marker.length());
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) return result;
            String chunk = tail + new String(buffer, 0, count, StandardCharsets.ISO_8859_1);
            boolean all = true;
            for (int i = 0; i < markers.length; i++) {
                result[i] |= chunk.contains(markers[i]);
                all &= result[i];
            }
            if (all) { result[markers.length] = true; return result; }
            tail = chunk.substring(Math.max(0, chunk.length() - longest));
        }
        result[markers.length] = true;
        return result;
    }

    private static void evaluate(JSONObject report) throws Exception {
        JSONArray reasons = new JSONArray();
        boolean code = "present".equals(report.optString("sinkCode"))
            && "present".equals(report.optString("avrcpCode"));
        boolean nativeCode = "present".equals(report.optString("nativeSink"));
        boolean root = report.optBoolean("rootAvailable");
        boolean focus = "true".equals(report.optString("focusResource")) || "false".equals(report.optString("focusResource"));
        JSONObject flags = report.optJSONObject("properties");
        boolean profileFlags = flags != null && (!flags.optString("bluetooth.profile.a2dp.source.enabled").isEmpty()
            || !flags.optString("bluetooth.profile.a2dp.sink.enabled").isEmpty())
            && (!flags.optString("bluetooth.profile.avrcp.target.enabled").isEmpty()
            || !flags.optString("bluetooth.profile.avrcp.controller.enabled").isEmpty());
        boolean api = !"none".equals(report.optString("profileAccess", "none"))
            && !"unsupported".equals(report.optString("profileInvocation", "unsupported"));
        boolean roleResources = booleanResource(report, "sinkResource") && booleanResource(report, "sourceResource")
            && booleanResource(report, "targetResource") && booleanResource(report, "controllerResource");
        AdaptationPlan plan = AdaptationPlan.choose(profileFlags, report.optBoolean("vendorPropertyReferences"),
            roleResources, focus, "true".equals(report.optString("focusResource")),
            report.optBoolean("fabricatedOverlay"), report.optBoolean("packagedMapping"),
            !report.optString("propertyWriter").isEmpty());
        boolean standalone = api && code && nativeCode && plan.supported();
        boolean legacy = legacyAbi() && code && nativeCode && report.optBoolean("legacyModule");
        String backend = standalone ? "profile-properties".equals(plan.configuration) ? "aosp-root-apk" : "resource-root-apk"
            : legacy ? "legacy-existing-module" : "none";
        report.put("configurationStrategy", standalone ? plan.configuration : legacy ? "existing-module" : "none");
        report.put("resourceStrategy", standalone ? plan.resources : legacy ? "existing-module" : "none");
        report.put("resourceHelperRequired", standalone && "packaged-idmap".equals(plan.resources));
        report.put("profileBindingRequired", "service-peek".equals(report.optString("profileAccess")));
        String verdict, title;
        if (report.has("bluetoothHardware") && Boolean.FALSE.equals(report.opt("bluetoothHardware"))) {
            verdict = "missing_components"; title = "未发现经典蓝牙功能";
            reasons.put("系统未声明经典蓝牙，无法使用 A2DP 接收。");
        } else if (root && (standalone || legacy)) {
            verdict = "capability_pass"; title = "能力检查通过";
            reasons.put(standalone ? "根据当前固件能力组合 Root APK 方案，不需要安装模块。" : "可继续使用已安装的旧系统兼容后端。");
            reasons.put("静态能力和接口已确认；实际播放还需要运行验证。");
        } else if ("root".equals(report.optString("level")) && !root) {
            verdict = "root_required"; title = "需要 root 授权";
            reasons.put("完整检测未获得 root，尚不能确认系统控制能力。");
        } else if (report.optString("apk").isEmpty()) {
            verdict = "insufficient"; title = "信息不足";
            reasons.put("未找到可读取的系统蓝牙 APK。");
        } else if (code || nativeCode) {
            verdict = root ? "needs_adaptation" : "insufficient";
            title = root ? "需要适配" : "需要完整检测";
            if (!root) reasons.put("基本检查发现接收端代码标识，请运行完整检测确认 root 和控制接口。");
            else {
                if (!api) reasons.put("未找到完整且兼容的蓝牙控制接口。");
                if ("none".equals(plan.configuration)) reasons.put("未确认角色属性，也未发现蓝牙原生库对厂商角色属性的引用。");
                if ("none".equals(plan.resources)) reasons.put("没有满足当前资源条件的覆盖方式。");
                if (!focus) reasons.put("无法确认自动音频焦点资源。");
                if (report.optString("propertyWriter").isEmpty()) reasons.put("未找到可用的运行时属性工具。");
            }
        } else {
            verdict = "insufficient"; title = "信息不足";
            reasons.put("未能确认接收端代码；未发现标识不等于硬件不支持。");
        }
        report.put("backend", backend);
        report.put("verdict", verdict);
        report.put("title", title);
        report.put("canControl", root && (standalone || legacy)
            && !Boolean.FALSE.equals(report.opt("bluetoothHardware")));
        report.put("reasons", reasons);
        report.put("evidence", "static-markers-and-interface-check");
        report.put("playbackVerified", false);
    }

    private static boolean booleanResource(JSONObject report, String name) {
        return "true".equals(report.optString(name)) || "false".equals(report.optString(name));
    }

    static String text(JSONObject report) {
        StringBuilder out = new StringBuilder();
        out.append(report.optString("title", "尚未检测")).append("\n\n");
        out.append("设备：").append(report.optString("manufacturer")).append(" ")
            .append(report.optString("model")).append(" / ").append(report.optString("device")).append('\n');
        out.append("系统：Android ").append(report.optString("android")).append(" / API ")
            .append(report.optInt("sdk")).append('\n');
        out.append("硬件：").append(report.optString("hardware")).append('\n');
        out.append("Root：").append(report.optString("rootManager")).append(" ")
            .append(report.optString("rootVersion")).append('\n');
        out.append("蓝牙包：").append(report.optString("bluetoothPackage")).append('\n');
        out.append("位置：").append(report.optString("apk")).append('\n');
        out.append("接收端代码：").append(label(report.optString("sinkCode"))).append('\n');
        out.append("媒体控制代码：").append(label(report.optString("avrcpCode"))).append('\n');
        out.append("原生接收代码：").append(label(report.optString("nativeSink"))).append('\n');
        out.append("原生库：").append(report.optString("nativeLibrary")).append('\n');
        out.append("自动音频焦点资源：").append(report.optString("focusResource")).append('\n');
        out.append("现代控制接口：").append(report.optBoolean("modernApi") ? "存在" : "未确认").append('\n');
        out.append("服务获取：").append(report.optString("profileAccess")).append('\n');
        out.append("接口调用：").append(report.optString("profileInvocation")).append('\n');
        out.append("实际控制调用：").append(report.optBoolean("runtimeControllerReady") ? "通过" : "未通过或尚未验证").append('\n');
        if (report.has("profileProbe")) out.append("当前音频接口：").append(report.optString("profileProbe")).append('\n');
        if (report.has("controllerError")) out.append("调用错误：").append(report.optString("controllerError")).append('\n');
        out.append("角色配置：").append(report.optString("configurationStrategy")).append('\n');
        out.append("资源覆盖：").append(report.optString("resourceStrategy")).append('\n');
        if (report.optBoolean("resourceHelperRequired")) out.append("首次启用会安装内置的资源辅助 APK。\n");
        out.append("后端：").append(report.optString("backend")).append("\n\n");
        JSONArray reasons = report.optJSONArray("reasons");
        if (reasons != null) for (int i = 0; i < reasons.length(); i++) out.append("• ").append(reasons.optString(i)).append('\n');
        JSONObject audio = report.optJSONObject("runtimeAudio");
        if (audio != null) {
            out.append("\n本次音频观察：").append(audio.optBoolean("active") ? "发现活动蓝牙音轨"
                : audio.optBoolean("observed") ? "音轨当前未活动，可能已暂停" : "尚未确认活动音轨").append('\n');
            if (audio.optBoolean("active")) {
                out.append("采样率：").append(audio.optInt("sampleRate")).append(" Hz\n");
                out.append("输出：").append(audio.optString("device")).append('\n');
            }
            out.append("这是一次即时观察，不是现场听感或长期稳定性保证。\n");
        }
        out.append("\n检测只读取系统信息，不改变蓝牙、USB 或调试设置。");
        return out.toString();
    }

    private static String label(String value) {
        return "present".equals(value) ? "发现标识" : "missing".equals(value) ? "未发现标识" : "无法读取";
    }

    static JSONObject denied(JSONObject basic, String error) throws Exception {
        JSONObject report = new JSONObject(basic.toString());
        report.put("rootAvailable", false);
        report.put("rootManager", "未授权");
        report.put("level", "root");
        report.put("rootError", error);
        evaluate(report);
        return report;
    }
}
