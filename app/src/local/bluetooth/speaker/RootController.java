package local.bluetooth.speaker;

import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

final class RootController {
    static final String FOCUS_OVERLAY = "com.android.shell:android_a2dp_sink_focus";
    static final File STATE = new File("/data/user_de/0/local.bluetooth.speaker/files/root-control");
    private static final String[] PROPERTIES = {
        "bluetooth.profile.a2dp.source.enabled", "bluetooth.profile.a2dp.sink.enabled",
        "bluetooth.profile.avrcp.target.enabled", "bluetooth.profile.avrcp.controller.enabled"
    };
    private static final String[] LEGACY_PROPERTIES = {
        "persist.vendor.service.bt.a2dp.sink", "persist.vendor.bt.a2dp.sink_conn"
    };
    private static String targetPackage = "com.android.bluetooth";
    private static String selectedBackend;
    private static String selectedConfiguration;
    private static String selectedResources;
    private static JSONObject selectedPlan;

    static String backend() {
        if (selectedBackend == null) {
            JSONObject saved = settings().optJSONObject("adaptation");
            File apk = new File(saved == null ? "" : saved.optString("apk"));
            if (saved != null && saved.optInt("revision") == 1
                    && android.os.Build.FINGERPRINT.equals(saved.optString("fingerprint"))
                    && apk.isFile() && apk.length() == saved.optLong("apkSize")
                    && apk.lastModified() == saved.optLong("apkModified")) select(saved);
            else {
                try { select(DeviceDiagnostics.root()); }
                catch (Exception ignored) { selectedBackend = "none"; }
            }
        }
        return selectedBackend;
    }

    private static void select(JSONObject report) {
        selectedBackend = report.optBoolean("canControl") ? report.optString("backend", "none") : "none";
        selectedConfiguration = report.optString("configurationStrategy");
        selectedResources = report.optString("resourceStrategy");
        targetPackage = report.optString("bluetoothPackage", "com.android.bluetooth");
        try {
            File apk = new File(report.optString("apk"));
            selectedPlan = new JSONObject().put("revision", 1)
                .put("fingerprint", android.os.Build.FINGERPRINT).put("apk", apk.getPath())
                .put("apkSize", apk.length()).put("apkModified", apk.lastModified())
                .put("canControl", report.optBoolean("canControl")).put("backend", selectedBackend)
                .put("configurationStrategy", selectedConfiguration).put("resourceStrategy", selectedResources)
                .put("bluetoothPackage", targetPackage);
        } catch (Exception ignored) { selectedPlan = null; }
    }

    static String targetPackage() { backend(); return targetPackage; }
    static String configuration() { backend(); return selectedConfiguration; }
    static String resources() { backend(); return selectedResources; }
    static boolean modern() { backend(); return "profile-properties".equals(selectedConfiguration); }
    static boolean standalone() { return "aosp-root-apk".equals(backend()) || "resource-root-apk".equals(backend()); }
    private static boolean legacyStandalone() { backend(); return "vendor-properties".equals(selectedConfiguration); }
    private static boolean packagedResources() { backend(); return "packaged-idmap".equals(selectedResources); }
    private static boolean manageComponents() { return legacyStandalone() || packagedResources(); }

    static String mode() {
        String key = modern() ? PROPERTIES[1] : "persist.vendor.service.bt.a2dp.sink";
        return "true".equals(property(key)) ? "sink" : "source";
    }

    static String defaultMode() {
        return standalone() ? settings().optString("defaultMode", "source") : ModuleControl.defaultMode();
    }

    static JSONObject settings() {
        try { return new JSONObject(new String(Files.readAllBytes(new File(STATE, "settings.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static void save(JSONObject settings) throws Exception {
        if (selectedPlan != null && !"none".equals(selectedBackend)) settings.put("adaptation", selectedPlan);
        if (!STATE.isDirectory() && !STATE.mkdirs()) throw new IllegalStateException("无法创建配置目录");
        File temporary = new File(STATE, "settings.json.new");
        Files.write(temporary.toPath(), settings.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(STATE, "settings.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static <T> T locked(Callable<T> action) throws Exception {
        if (!STATE.isDirectory() && !STATE.mkdirs()) throw new IllegalStateException("无法创建配置目录");
        try (RandomAccessFile file = new RandomAccessFile(new File(STATE, "control.lock"), "rw")) {
            FileLock lock = null;
            long deadline = System.currentTimeMillis() + 15000;
            while (lock == null && System.currentTimeMillis() < deadline) {
                lock = file.getChannel().tryLock();
                if (lock == null) Thread.sleep(150);
            }
            if (lock == null) throw new IllegalStateException("另一个蓝牙操作正在进行");
            try { return action.call(); } finally { lock.release(); }
        }
    }

    static void requireBackend() throws Exception {
        JSONObject report = DeviceDiagnostics.root();
        if (!report.optBoolean("canControl")) throw new IllegalStateException(
            report.optString("title") + "：" + report.optJSONArray("reasons").optString(0));
        select(report);
    }

    static void setDefault(String value) throws Exception {
        if (!ModuleControl.validMode(value, true)) throw new IllegalArgumentException("Invalid boot mode");
        requireBackend();
        if (!standalone()) { ModuleControl.setDefault(value); return; }
        locked(() -> {
            JSONObject config = settings();
            config.put("defaultMode", value);
            config.put("restoreOnBoot", true);
            save(config);
            return null;
        });
    }

    static void changeMode(String value) throws Exception {
        if (!ModuleControl.validMode(value, false)) throw new IllegalArgumentException("Invalid audio mode");
        requireBackend();
        if (!standalone()) { ModuleControl.changeMode(value); return; }
        locked(() -> {
            apply(value);
            JSONObject config = settings();
            config.put("lastAppliedBoot", DeviceDiagnostics.command("/system/bin/cat", "/proc/sys/kernel/random/boot_id"));
            save(config);
            return null;
        });
    }

    static JSONObject applyBoot() throws Exception {
        if (!standalone()) return new JSONObject().put("ok", true).put("skipped", true);
        JSONObject saved = settings();
        if (!saved.optBoolean("restoreOnBoot")) return new JSONObject().put("ok", true).put("skipped", true);
        requireBackend();
        return locked(() -> {
            JSONObject config = settings();
            String boot = DeviceDiagnostics.command("/system/bin/cat", "/proc/sys/kernel/random/boot_id");
            if (!boot.isEmpty() && boot.equals(config.optString("lastAppliedBoot"))) {
                return new JSONObject().put("ok", true).put("alreadyApplied", true);
            }
            String desired = config.optString("defaultMode", "source");
            if ("last".equals(desired)) desired = config.optString("lastMode", "source");
            if (!ModuleControl.validMode(desired, false)) desired = "source";
            apply(desired);
            config = settings();
            config.put("lastAppliedBoot", boot);
            save(config);
            return new JSONObject().put("ok", true).put("mode", desired);
        });
    }

    static void restore() throws Exception {
        requireBackend();
        if (!standalone()) {
            ModuleControl.setDefault("source");
            ModuleControl.changeMode("source");
            return;
        }
        locked(() -> {
            JSONObject config = settings();
            JSONObject original = config.optJSONObject("originalProperties");
            if (original == null) {
                config.put("restoreOnBoot", false);
                save(config);
                return null;
            }
            boolean wasOn = isOn();
            String previousMode = mode();
            JSONObject previous = properties();
            JSONObject previousComponents = manageComponents() ? LegacyResources.components() : null;
            boolean previousFocus = overlayEnabled();
            if (sameValues(previous, original) && !previousFocus
                    && (!manageComponents() || sameValues(previousComponents, config.optJSONObject("originalComponents")))
                    && (!wasOn || profileReady(previousMode))) {
                config.put("restoreOnBoot", false).put("lastMode", previousMode).put("profileVerified", false);
                save(config);
                return null;
            }
            try {
                stop();
                writeProperties(original);
                focus(false);
                if (manageComponents()) LegacyResources.restoreComponents(config.optJSONObject("originalComponents"));
                restartCachedProcess();
                if (wasOn) {
                    powerUnchecked(true);
                    waitForProfile("true".equals(original.optString(modern() ? PROPERTIES[1] : LEGACY_PROPERTIES[0])) ? "sink" : "source");
                }
                config.put("restoreOnBoot", false);
                config.put("lastMode", mode());
                config.put("profileVerified", false);
                save(config);
            } catch (Exception error) {
                try {
                    stop();
                    writeProperties(previous);
                    focus(previousFocus);
                    if (manageComponents()) LegacyResources.restoreComponents(previousComponents);
                    restartCachedProcess();
                    if (wasOn) {
                        powerUnchecked(true);
                        waitForProfile(previousMode);
                    }
                } catch (Exception rollback) { error.addSuppressed(rollback); }
                throw error;
            }
            return null;
        });
    }

    static void power(boolean on) throws Exception {
        requireBackend();
        if (standalone()) {
            locked(() -> {
                boolean before = isOn();
                JSONObject config = settings();
                if (before == on && (!on || profileReady(mode()) && resourcesReady(mode()))) {
                    config.put("profileVerified", on);
                    save(config);
                    return null;
                }
                JSONObject savedConfig = new JSONObject(config.toString());
                JSONObject previous = properties();
                JSONObject previousComponents = manageComponents() ? LegacyResources.components() : null;
                boolean previousFocus = overlayEnabled();
                String previousMode = mode();
                try {
                    if (on && "sink".equals(mode())) {
                        if (!config.has("originalProperties")) {
                            config.put("originalProperties", properties());
                            if (manageComponents()) config.put("originalComponents", previousComponents);
                            save(config);
                        }
                        focus(true);
                    }
                    powerUnchecked(on);
                    waitForState(on);
                    if (on) waitForProfile(mode());
                    config.put("profileVerified", on);
                    save(config);
                } catch (Exception error) {
                    try {
                        powerUnchecked(false);
                        waitForState(false);
                        writeProperties(previous);
                        focus(previousFocus);
                        if (manageComponents()) LegacyResources.restoreComponents(previousComponents);
                        restartCachedProcess();
                        if (before) {
                            powerUnchecked(true);
                            waitForState(true);
                            waitForProfile(previousMode);
                        }
                        save(savedConfig);
                    } catch (Exception rollback) { error.addSuppressed(rollback); }
                    throw error;
                }
                return null;
            });
        } else {
            ModuleControl.execute(15, "/system/bin/svc", "bluetooth", on ? "enable" : "disable");
        }
    }

    static void restart() throws Exception {
        requireBackend();
        if (!isOn()) return;
        if (standalone()) {
            locked(() -> {
                String desired = mode();
                stop();
                restartCachedProcess();
                powerUnchecked(true);
                waitForProfile(desired);
                return null;
            });
        } else {
            ModuleControl.execute(15, "/system/bin/svc", "bluetooth", "disable");
            Thread.sleep(1500);
            ModuleControl.execute(15, "/system/bin/svc", "bluetooth", "enable");
        }
    }

    private static void apply(String desired) throws Exception {
        JSONObject config = settings();
        JSONObject previous = properties();
        JSONObject previousComponents = manageComponents() ? LegacyResources.components() : null;
        if (!config.has("originalProperties")) {
            config.put("originalProperties", previous);
            if (manageComponents()) config.put("originalComponents", previousComponents);
            save(config);
        }
        String oldMode = mode();
        boolean wasOn = isOn();
        if (wasOn && desired.equals(oldMode) && profileReady(desired) && resourcesReady(desired)) {
            config.put("lastMode", desired).put("restoreOnBoot", true).put("profileVerified", true);
            save(config);
            return;
        }
        boolean oldFocus = overlayEnabled();
        try {
            stop();
            boolean sink = "sink".equals(desired);
            JSONObject values = new JSONObject();
            if (legacyStandalone()) {
                values.put(LEGACY_PROPERTIES[0], String.valueOf(sink));
                values.put(LEGACY_PROPERTIES[1], sink ? "1"
                    : config.getJSONObject("originalProperties").optString(LEGACY_PROPERTIES[1]));
            } else {
                values.put(PROPERTIES[0], String.valueOf(!sink));
                values.put(PROPERTIES[1], String.valueOf(sink));
                values.put(PROPERTIES[2], String.valueOf(!sink));
                values.put(PROPERTIES[3], String.valueOf(sink));
            }
            writeProperties(values);
            focus(sink);
            restartCachedProcess();
            if (wasOn) {
                powerUnchecked(true);
                waitForProfile(desired);
            }
            config.put("lastMode", desired).put("restoreOnBoot", true).put("profileVerified", wasOn);
            if (!config.has("defaultMode")) config.put("defaultMode", "source");
            save(config);
        } catch (Exception error) {
            try {
                stop();
                writeProperties(previous);
                focus(oldFocus);
                if (manageComponents()) LegacyResources.restoreComponents(previousComponents);
                restartCachedProcess();
                if (wasOn) {
                    powerUnchecked(true);
                    waitForProfile(oldMode);
                }
            } catch (Exception rollback) { error.addSuppressed(rollback); }
            throw new IllegalStateException("切换失败，已尝试恢复原配置：" + error.getMessage(), error);
        }
    }

    private static JSONObject properties() throws Exception {
        JSONObject values = new JSONObject();
        for (String name : legacyStandalone() ? LEGACY_PROPERTIES : PROPERTIES) values.put(name, property(name));
        return values;
    }

    private static void writeProperties(JSONObject values) throws Exception {
        String writer = DeviceDiagnostics.propertyWriter();
        if (writer.isEmpty()) throw new IllegalStateException("没有可用的属性工具");
        for (String name : legacyStandalone() ? LEGACY_PROPERTIES : PROPERTIES) {
            String value = values.optString(name, "");
            if (value.isEmpty()) {
                if (!property(name).isEmpty()) ModuleControl.execute(8, writer, "--delete", name);
            }
            else ModuleControl.execute(8, writer, "-n", name, value);
        }
    }

    private static boolean overlayEnabled() {
        return DeviceDiagnostics.command("/system/bin/cmd", "overlay", "list", "--user", "0", targetPackage)
            .contains("[x] " + (packagedResources() ? LegacyResources.PACKAGE : FOCUS_OVERLAY));
    }

    private static boolean resourcesReady(String desired) {
        if (!"sink".equals(desired)) return !overlayEnabled();
        if ("system-default".equals(selectedResources)) return "true".equals(DeviceDiagnostics.command(
            "/system/bin/cmd", "overlay", "lookup", targetPackage, targetPackage + ":bool/" + DeviceDiagnostics.FOCUS));
        if (!overlayEnabled()) return false;
        if (packagedResources()) return LegacyResources.ready();
        if (legacyStandalone()) {
            for (int i = 0; i < LegacyResources.RESOURCES.length; i++) {
                if (!String.valueOf(i < 3).equals(DeviceDiagnostics.command("/system/bin/cmd", "overlay", "lookup",
                        targetPackage, targetPackage + ":bool/" + LegacyResources.RESOURCES[i]))) return false;
            }
            return true;
        }
        return "true".equals(DeviceDiagnostics.command("/system/bin/cmd", "overlay", "lookup",
            targetPackage, targetPackage + ":bool/" + DeviceDiagnostics.FOCUS));
    }

    private static boolean sameValues(JSONObject actual, JSONObject expected) {
        if (expected == null) return true;
        if (actual == null) return false;
        java.util.Iterator<String> keys = expected.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!actual.has(key) || !String.valueOf(actual.opt(key)).equals(String.valueOf(expected.opt(key)))) return false;
        }
        return true;
    }

    private static void focus(boolean on) throws Exception {
        if (packagedResources()) {
            if (on) LegacyResources.enable(); else LegacyResources.disable();
            return;
        }
        if ("system-default".equals(selectedResources)) {
            if (on && !resourcesReady("sink")) throw new IllegalStateException("系统自动音频焦点资源已变化，请重新检测");
            return;
        }
        if (on) {
            String[] resources = legacyStandalone() ? LegacyResources.RESOURCES : new String[]{DeviceDiagnostics.FOCUS};
            for (int i = 0; i < resources.length; i++) {
                String resource = resources[i];
                String name = overlayName(resource);
                boolean value = !legacyStandalone() || i < 3;
                ModuleControl.execute(10, "/system/bin/cmd", "overlay", "fabricate", "--target", targetPackage,
                    "--name", name, targetPackage + ":bool/" + resource, "0x12", value ? "0x1" : "0x0");
                ModuleControl.execute(10, "/system/bin/cmd", "overlay", "enable", "--user", "0", "com.android.shell:" + name);
                if (!String.valueOf(value).equals(ModuleControl.execute(8, "/system/bin/cmd", "overlay", "lookup",
                        targetPackage, targetPackage + ":bool/" + resource).trim())) {
                    throw new IllegalStateException("资源覆盖未生效：" + resource);
                }
            }
            if (legacyStandalone()) LegacyResources.prepareComponents();
        } else {
            String overlays = DeviceDiagnostics.command("/system/bin/cmd", "overlay", "list", "--user", "0", targetPackage);
            for (String resource : LegacyResources.RESOURCES) {
                String id = "com.android.shell:" + overlayName(resource);
                if (overlays.contains(id)) ModuleControl.execute(10, "/system/bin/cmd", "overlay", "disable", "--user", "0", id);
            }
        }
    }

    private static String overlayName(String resource) {
        return DeviceDiagnostics.FOCUS.equals(resource) ? "android_a2dp_sink_focus" : "android_a2dp_sink_" + resource;
    }
    static String property(String name) { return DeviceDiagnostics.command("/system/bin/getprop", name); }

    static boolean isOn() {
        return "1".equals(DeviceDiagnostics.command("/system/bin/settings", "get", "global", "bluetooth_on"));
    }

    private static void powerUnchecked(boolean on) throws Exception {
        if (legacyStandalone()) ModuleControl.execute(15, "/system/bin/svc", "bluetooth", on ? "enable" : "disable");
        else ModuleControl.execute(15, "/system/bin/cmd", "bluetooth_manager", on ? "enable" : "disable");
    }

    private static void stop() throws Exception {
        if (!isOn()) return;
        powerUnchecked(false);
        waitForState(false);
    }

    private static void waitForState(boolean on) throws Exception {
        for (int i = 0; i < 24; i++) {
            String dump = DeviceDiagnostics.command("/system/bin/dumpsys", "bluetooth_manager");
            if (on ? dump.contains("  state: ON") : dump.contains("  state: OFF") || dump.contains("  state: BLE_ON")) return;
            Thread.sleep(400);
        }
        throw new IllegalStateException(on ? "蓝牙未能开启" : "蓝牙未能关闭");
    }

    private static boolean profileReady(String mode) {
        String dump = DeviceDiagnostics.command("/system/bin/dumpsys", "bluetooth_manager");
        String expected = "sink".equals(mode) ? "Profile: A2dpSinkService" : "Profile: A2dpService";
        String other = "sink".equals(mode) ? "Profile: A2dpService" : "Profile: A2dpSinkService";
        return dump.contains("  state: ON") && dump.contains(expected + "\n") && !dump.contains(other + "\n");
    }

    private static void waitForProfile(String mode) throws Exception {
        for (int i = 0; i < 24; i++) {
            if (profileReady(mode)) return;
            Thread.sleep(400);
        }
        throw new IllegalStateException("目标蓝牙音频服务未启动");
    }

    private static void restartCachedProcess() throws Exception {
        // Newer stacks cache profile flags per process. Most exit after Bluetooth is off.
        String ids = DeviceDiagnostics.command("/system/bin/pidof", targetPackage).trim();
        if (ids.isEmpty()) return;
        Thread.sleep(700);
        ids = DeviceDiagnostics.command("/system/bin/pidof", targetPackage).trim();
        if (ids.isEmpty()) return;
        for (String id : ids.split("\\s+")) {
            if (id.matches("[0-9]+")) ModuleControl.execute(5, "/system/bin/kill", "-TERM", id);
        }
        Thread.sleep(800);
    }
}
