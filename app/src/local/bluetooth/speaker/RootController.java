package local.bluetooth.speaker;

import android.bluetooth.BluetoothAdapter;
import android.os.ParcelUuid;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

final class RootController {
    static final String FOCUS_OVERLAY = "com.android.shell:android_a2dp_sink_focus";
    static final File STATE = new File("/data/user_de/0/local.bluetooth.speaker/files/root-control");
    private static final int PLAN_REVISION = 2;
    private static final String[] PROPERTIES = {
        "bluetooth.profile.a2dp.source.enabled", "bluetooth.profile.a2dp.sink.enabled",
        "bluetooth.profile.avrcp.target.enabled", "bluetooth.profile.avrcp.controller.enabled",
        "persist.vendor.service.bt.a2dp.sink", "persist.vendor.bt.a2dp.sink_conn"
    };
    private static String targetPackage = "com.android.bluetooth";
    private static String selectedBackend;
    private static String selectedConfiguration;
    private static String selectedResources;
    private static JSONObject selectedPlan;

    static String preference() { return settings().optString("schemePreference", "auto"); }

    static JSONObject cachedPlan() {
        JSONObject saved = settings().optJSONObject("adaptation");
        File apk = new File(saved == null ? "" : saved.optString("apk"));
        return saved != null && saved.optInt("revision") == PLAN_REVISION
            && android.os.Build.FINGERPRINT.equals(saved.optString("fingerprint"))
            && apk.isFile() && apk.length() == saved.optLong("apkSize")
            && apk.lastModified() == saved.optLong("apkModified")
            && AdaptationPlan.fromId(saved.optString("planId")) != null ? saved : null;
    }

    static String backend() {
        if (selectedBackend == null) {
            JSONObject saved = cachedPlan();
            try { select(saved == null ? DeviceDiagnostics.root() : saved); }
            catch (Exception ignored) { selectedBackend = "none"; }
        }
        return selectedBackend;
    }

    private static void select(JSONObject report) throws Exception {
        selectedBackend = report.optBoolean("canControl") ? report.optString("backend", "none") : "none";
        selectedConfiguration = report.optString("configurationStrategy");
        selectedResources = report.optString("resourceStrategy");
        targetPackage = report.optString("bluetoothPackage", "com.android.bluetooth");
        File apk = new File(report.optString("apk"));
        selectedPlan = new JSONObject().put("revision", PLAN_REVISION)
            .put("fingerprint", android.os.Build.FINGERPRINT).put("apk", apk.getPath())
            .put("apkSize", apk.length()).put("apkModified", apk.lastModified())
            .put("canControl", report.optBoolean("canControl")).put("backend", selectedBackend)
            .put("configurationStrategy", selectedConfiguration).put("resourceStrategy", selectedResources)
            .put("bluetoothPackage", targetPackage).put("planId", report.optString("planId"))
            .put("receiverVerified", report.optBoolean("receiverVerified"));
    }

    private static void select(JSONObject report, AdaptationPlan plan) throws Exception {
        JSONObject candidate = new JSONObject(report.toString())
            .put("canControl", true).put("configurationStrategy", plan.configuration)
            .put("resourceStrategy", plan.resources).put("planId", plan.id())
            .put("backend", "existing-module".equals(plan.configuration) ? "legacy-existing-module"
                : "profile-properties".equals(plan.configuration) ? "aosp-root-apk" : "resource-root-apk");
        JSONObject cached = cachedPlan();
        candidate.put("receiverVerified", cached != null && plan.id().equals(cached.optString("planId"))
            && cached.optBoolean("receiverVerified"));
        select(candidate);
    }

    static String targetPackage() { backend(); return targetPackage; }
    static String configuration() { backend(); return selectedConfiguration; }
    static String resources() { backend(); return selectedResources; }
    static String planId() { backend(); return selectedPlan == null ? "" : selectedPlan.optString("planId"); }
    static boolean receiverVerified() { backend(); return selectedPlan != null && selectedPlan.optBoolean("receiverVerified"); }
    static boolean modern() { backend(); return "profile-properties".equals(selectedConfiguration); }
    static boolean standalone() { return "aosp-root-apk".equals(backend()) || "resource-root-apk".equals(backend()); }
    private static boolean vendor() { backend(); return "vendor-properties".equals(selectedConfiguration); }
    private static boolean packagedResources() { backend(); return "packaged-idmap".equals(selectedResources); }

    static String mode() {
        return "true".equals(property(modern() ? PROPERTIES[1] : PROPERTIES[4])) ? "sink" : "source";
    }

    static String defaultMode() {
        return standalone() ? settings().optString("defaultMode", "source") : ModuleControl.defaultMode();
    }

    static JSONObject settings() {
        try { return new JSONObject(new String(Files.readAllBytes(new File(STATE, "settings.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static void save(JSONObject config) throws Exception {
        if (selectedPlan != null && !"none".equals(selectedBackend)) config.put("adaptation", selectedPlan);
        saveExact(config);
    }

    private static void saveExact(JSONObject config) throws Exception {
        if (!STATE.isDirectory() && !STATE.mkdirs()) throw new IllegalStateException("无法创建配置目录");
        File temporary = new File(STATE, "settings.json.new");
        Files.write(temporary.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
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

    private static void requireReport(JSONObject report) throws Exception {
        if (!report.optBoolean("canControl")) throw new IllegalStateException(
            report.optString("title") + "：" + report.optJSONArray("reasons").optString(0));
    }

    static void requireBackend() throws Exception {
        JSONObject report = DeviceDiagnostics.root();
        requireReport(report);
        select(report);
    }

    static void setDefault(String value) throws Exception {
        if (!ModuleControl.validMode(value, true)) throw new IllegalArgumentException("Invalid boot mode");
        requireBackend();
        if (!standalone()) { ModuleControl.setDefault(value); return; }
        locked(() -> {
            JSONObject config = settings();
            config.put("defaultMode", value).put("restoreOnBoot", true);
            save(config);
            return null;
        });
    }

    static void setScheme(String value) throws Exception {
        if (!"auto".equals(value) && AdaptationPlan.fromId(value) == null)
            throw new IllegalArgumentException("未知的适配方案");
        JSONObject report = DeviceDiagnostics.root(false, value);
        requireReport(report);
        locked(() -> {
            backend();
            applyCandidates(mode(), value, report, false, false, false);
            return null;
        });
    }

    static void changeMode(String value) throws Exception {
        if (!ModuleControl.validMode(value, false)) throw new IllegalArgumentException("Invalid audio mode");
        JSONObject report = DeviceDiagnostics.root();
        requireReport(report);
        locked(() -> {
            applyCandidates(value, preference(), report, false, false, true);
            JSONObject config = settings();
            config.put("lastAppliedBoot", DeviceDiagnostics.command("/system/bin/cat", "/proc/sys/kernel/random/boot_id"));
            save(config);
            return null;
        });
    }

    static JSONObject applyBoot() throws Exception {
        if (!settings().optBoolean("restoreOnBoot")) return new JSONObject().put("ok", true).put("skipped", true);
        JSONObject report = DeviceDiagnostics.root();
        requireReport(report);
        return locked(() -> {
            JSONObject config = settings();
            String boot = DeviceDiagnostics.command("/system/bin/cat", "/proc/sys/kernel/random/boot_id");
            if (!boot.isEmpty() && boot.equals(config.optString("lastAppliedBoot")))
                return new JSONObject().put("ok", true).put("alreadyApplied", true);
            String desired = config.optString("defaultMode", "source");
            if ("last".equals(desired)) desired = config.optString("lastMode", "source");
            if (!ModuleControl.validMode(desired, false)) desired = "source";
            applyCandidates(desired, preference(), report, false, false, false);
            config = settings();
            config.put("lastAppliedBoot", boot);
            save(config);
            return new JSONObject().put("ok", true).put("mode", desired);
        });
    }

    private static List<AdaptationPlan> candidates(JSONObject report, String preference, String desired) {
        List<AdaptationPlan> plans = new ArrayList<>();
        JSONArray available = report.optJSONArray("plans");
        JSONObject cached = cachedPlan();
        String preferred = cached != null && (!"sink".equals(desired) || cached.optBoolean("receiverVerified"))
            ? cached.optString("planId") : "";
        if (available != null) for (int i = 0; i < available.length(); i++) {
            AdaptationPlan plan = AdaptationPlan.fromId(available.optJSONObject(i).optString("id"));
            if (plan == null || !"auto".equals(preference) && !preference.equals(plan.id())) continue;
            if (plan.id().equals(preferred)) plans.add(0, plan); else plans.add(plan);
        }
        return plans;
    }

    private static final class Snapshot {
        final JSONObject config = settings();
        final JSONObject properties = properties();
        final JSONObject components = LegacyResources.components();
        final JSONObject overlays = overlayStates();
        final JSONObject plan;
        final boolean helperInstalled = !LegacyResources.installed().isEmpty();
        final boolean on = isOn();
        final String mode;

        Snapshot() throws Exception {
            plan = new JSONObject(selectedPlan.toString());
            String dump = DeviceDiagnostics.command("/system/bin/dumpsys", "bluetooth_manager");
            mode = dump.contains("Profile: A2dpSinkService\n") ? "sink"
                : dump.contains("Profile: A2dpService\n") ? "source" : mode();
        }
    }

    private static void applyCandidates(String desired, String preference, JSONObject report,
            boolean enable, boolean force, boolean remember) throws Exception {
        backend();
        List<AdaptationPlan> plans = candidates(report, preference, desired);
        if (plans.isEmpty()) throw new IllegalStateException("所选方案当前不可用");
        if ("existing-module".equals(plans.get(0).configuration)) {
            select(report, plans.get(0));
            ModuleControl.changeMode(desired);
            save(settings().put("schemePreference", preference));
            return;
        }
        Snapshot before = new Snapshot();
        JSONArray attempts = new JSONArray();
        long deadline = System.currentTimeMillis() + 180000;
        for (AdaptationPlan plan : plans) {
            select(report, plan);
            try {
                JSONObject config = new JSONObject(before.config.toString());
                apply(desired, enable || before.on, force, before, config);
                selectedPlan.put("receiverVerified", selectedPlan.optBoolean("receiverVerified")
                    || (enable || before.on) && "sink".equals(desired));
                attempts.put(new JSONObject().put("id", plan.id()).put("ok", true));
                config.put("schemePreference", preference).put("lastMode", desired)
                    .put("profileVerified", enable || before.on).put("lastSelectionAttempts", attempts);
                if (remember) config.put("restoreOnBoot", true);
                if (!config.has("defaultMode")) config.put("defaultMode", "source");
                save(config);
                return;
            } catch (Exception error) {
                attempts.put(new JSONObject().put("id", plan.id()).put("ok", false).put("error", error.toString()));
                try { rollback(before); }
                catch (Exception rollback) {
                    error.addSuppressed(rollback);
                    throw new IllegalStateException("恢复原配置失败，已停止自动尝试：" + rollback.getMessage(), error);
                }
                if (System.currentTimeMillis() > deadline) break;
            }
        }
        throw new IllegalStateException("方案切换失败，已恢复原配置：" + attempts);
    }

    private static void merge(JSONObject target, JSONObject values) throws Exception {
        java.util.Iterator<String> names = values.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!target.has(name)) target.put(name, values.get(name));
        }
    }

    private static void backup(JSONObject config, Snapshot before) throws Exception {
        boolean existing = config.has("originalProperties");
        JSONObject original = config.optJSONObject("originalProperties");
        if (original == null) original = new JSONObject();
        merge(original, before.properties);
        config.put("originalProperties", original);
        JSONObject components = config.optJSONObject("originalComponents");
        if (components == null) components = new JSONObject();
        merge(components, before.components);
        config.put("originalComponents", components);
        if (!config.has("originalOverlays")) config.put("originalOverlays", existing ? new JSONObject() : before.overlays);
        saveExact(config);
    }

    private static void apply(String desired, boolean on, boolean force, Snapshot before, JSONObject config) throws Exception {
        backup(config, before);
        if (!force && before.plan.optString("planId").equals(planId()) && desired.equals(before.mode)
                && on == before.on && resourcesReady(desired) && (!on || profileReady(desired))) {
            if (on) verifyController(desired);
            return;
        }
        stop();
        // Each candidate starts from the saved baseline, including the other protocol's flags.
        writeProperties(config.getJSONObject("originalProperties"));
        restoreOverlays(config.optJSONObject("originalOverlays"));
        LegacyResources.restoreComponents(config.optJSONObject("originalComponents"));
        boolean sink = "sink".equals(desired);
        JSONObject values = new JSONObject();
        if (vendor()) {
            values.put(PROPERTIES[4], String.valueOf(sink));
            values.put(PROPERTIES[5], sink ? "1" : config.getJSONObject("originalProperties").optString(PROPERTIES[5]));
        } else {
            values.put(PROPERTIES[0], String.valueOf(!sink)).put(PROPERTIES[1], String.valueOf(sink))
                .put(PROPERTIES[2], String.valueOf(!sink)).put(PROPERTIES[3], String.valueOf(sink));
        }
        writeProperties(values);
        focus(sink);
        restartCachedProcess();
        if (on) {
            powerUnchecked(true);
            waitForProfile(desired);
        }
    }

    private static void rollback(Snapshot before) throws Exception {
        stop();
        writeProperties(before.properties);
        restoreOverlays(before.overlays);
        LegacyResources.restoreComponents(before.components);
        if (!before.helperInstalled && !LegacyResources.installed().isEmpty())
            ModuleControl.execute(25, "/system/bin/pm", "uninstall", LegacyResources.PACKAGE);
        restartCachedProcess();
        select(before.plan);
        if (before.on) {
            powerUnchecked(true);
            waitForProfile(before.mode);
        }
        saveExact(before.config);
    }

    static void restore() throws Exception {
        backend();
        if (!standalone()) {
            requireBackend();
            ModuleControl.setDefault("source");
            ModuleControl.changeMode("source");
            return;
        }
        locked(() -> {
            Snapshot before = new Snapshot();
            JSONObject config = new JSONObject(before.config.toString());
            JSONObject original = config.optJSONObject("originalProperties");
            if (original != null) try {
                String originalMode = "true".equals(original.optString(PROPERTIES[1]))
                    || "true".equals(original.optString(PROPERTIES[4])) ? "sink" : "source";
                JSONObject overlays = config.optJSONObject("originalOverlays");
                JSONObject components = config.optJSONObject("originalComponents");
                if (!sameValues(before.properties, original) || !sameOverlays(before.overlays, overlays)
                        || !sameValues(before.components, components) || before.on && !profileReady(originalMode)) {
                    stop();
                    writeProperties(original);
                    restoreOverlays(overlays);
                    LegacyResources.restoreComponents(components);
                    restartCachedProcess();
                    if (before.on) {
                        powerUnchecked(true);
                        waitForProfile(originalMode);
                    }
                }
                config.put("lastMode", originalMode);
            } catch (Exception error) {
                try { rollback(before); } catch (Exception rollback) { error.addSuppressed(rollback); }
                throw error;
            }
            config.put("restoreOnBoot", false).put("profileVerified", false);
            save(config);
            return null;
        });
    }

    static void power(boolean on) throws Exception {
        JSONObject report = DeviceDiagnostics.root();
        requireReport(report);
        backend();
        if (!standalone()) {
            ModuleControl.execute(15, "/system/bin/svc", "bluetooth", on ? "enable" : "disable");
            return;
        }
        locked(() -> {
            if (on) applyCandidates(mode(), preference(), report, true, false, false);
            else {
                stop();
                save(settings().put("profileVerified", false));
            }
            return null;
        });
    }

    static void restart() throws Exception {
        if (!isOn()) return;
        JSONObject report = DeviceDiagnostics.root();
        requireReport(report);
        backend();
        if (!standalone()) {
            ModuleControl.execute(15, "/system/bin/svc", "bluetooth", "disable");
            Thread.sleep(1500);
            ModuleControl.execute(15, "/system/bin/svc", "bluetooth", "enable");
            return;
        }
        locked(() -> { applyCandidates(mode(), preference(), report, false, true, false); return null; });
    }

    private static JSONObject properties() {
        JSONObject values = new JSONObject();
        try { for (String name : PROPERTIES) values.put(name, property(name)); }
        catch (Exception ignored) {}
        return values;
    }

    private static void writeProperties(JSONObject values) throws Exception {
        String writer = DeviceDiagnostics.propertyWriter();
        if (writer.isEmpty()) throw new IllegalStateException("没有可用的属性工具");
        for (String name : PROPERTIES) if (values.has(name)) {
            String value = values.getString(name);
            if (value.equals(property(name))) continue;
            if (value.isEmpty()) ModuleControl.execute(8, writer, "--delete", name);
            else ModuleControl.execute(8, writer, "-n", name, value);
            if (!value.equals(property(name))) throw new IllegalStateException("属性未生效：" + name);
        }
    }

    private static JSONObject overlayStates() {
        JSONObject states = new JSONObject();
        String list = DeviceDiagnostics.command("/system/bin/cmd", "overlay", "list", "--user", "0", targetPackage);
        try {
            for (String resource : LegacyResources.RESOURCES) {
                String id = "com.android.shell:" + overlayName(resource);
                if (list.contains("[x] " + id)) states.put(id, true);
            }
            if (list.contains("[x] " + LegacyResources.PACKAGE)) states.put(LegacyResources.PACKAGE, true);
        } catch (Exception ignored) {}
        return states;
    }

    private static void restoreOverlays(JSONObject states) throws Exception {
        JSONObject enabled = overlayStates();
        java.util.Iterator<String> names = enabled.keys();
        while (names.hasNext()) {
            String id = names.next();
            if (states == null || !states.optBoolean(id))
                ModuleControl.execute(10, "/system/bin/cmd", "overlay", "disable", "--user", "0", id);
        }
        if (states != null) {
            names = states.keys();
            while (names.hasNext()) {
                String id = names.next();
                if (states.optBoolean(id) && !enabled.optBoolean(id))
                    ModuleControl.execute(10, "/system/bin/cmd", "overlay", "enable", "--user", "0", id);
            }
        }
    }

    private static boolean sameOverlays(JSONObject actual, JSONObject expected) {
        if (expected == null) return actual.length() == 0;
        return sameValues(actual, expected) && sameValues(expected, actual);
    }

    private static boolean resourcesReady(String desired) {
        if (!"sink".equals(desired)) return overlayStates().length() == 0;
        if ("system-default".equals(selectedResources)) return "true".equals(DeviceDiagnostics.command(
            "/system/bin/cmd", "overlay", "lookup", targetPackage, targetPackage + ":bool/" + DeviceDiagnostics.FOCUS));
        if (packagedResources()) return LegacyResources.ready();
        for (String resource : vendor() ? LegacyResources.RESOURCES : new String[]{DeviceDiagnostics.FOCUS}) {
            boolean value = !vendor() || !resource.equals("profile_supported_a2dp")
                && !resource.equals("profile_supported_avrcp_target");
            if (!String.valueOf(value).equals(DeviceDiagnostics.command("/system/bin/cmd", "overlay", "lookup",
                    targetPackage, targetPackage + ":bool/" + resource))) return false;
        }
        return true;
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
        if (!on) { restoreOverlays(null); return; }
        if (packagedResources()) { LegacyResources.enable(); return; }
        if ("system-default".equals(selectedResources)) {
            if (!resourcesReady("sink")) throw new IllegalStateException("系统自动音频焦点资源已变化");
            return;
        }
        String[] resources = vendor() ? LegacyResources.RESOURCES : new String[]{DeviceDiagnostics.FOCUS};
        for (int i = 0; i < resources.length; i++) {
            String resource = resources[i];
            String name = overlayName(resource);
            boolean value = !vendor() || i < 3;
            ModuleControl.execute(10, "/system/bin/cmd", "overlay", "fabricate", "--target", targetPackage,
                "--name", name, targetPackage + ":bool/" + resource, "0x12", value ? "0x1" : "0x0");
            ModuleControl.execute(10, "/system/bin/cmd", "overlay", "enable", "--user", "0", "com.android.shell:" + name);
        }
        if (!resourcesReady("sink")) throw new IllegalStateException("资源覆盖未生效");
        if (vendor()) LegacyResources.prepareComponents();
    }

    private static String overlayName(String resource) {
        return DeviceDiagnostics.FOCUS.equals(resource) ? "android_a2dp_sink_focus" : "android_a2dp_sink_" + resource;
    }
    static String property(String name) { return DeviceDiagnostics.command("/system/bin/getprop", name); }
    static boolean isOn() { return "1".equals(DeviceDiagnostics.command("/system/bin/settings", "get", "global", "bluetooth_on")); }

    private static void powerUnchecked(boolean on) throws Exception {
        if (vendor()) ModuleControl.execute(15, "/system/bin/svc", "bluetooth", on ? "enable" : "disable");
        else ModuleControl.execute(15, "/system/bin/cmd", "bluetooth_manager", on ? "enable" : "disable");
    }

    private static void stop() throws Exception {
        if (!isOn()) return;
        powerUnchecked(false);
        for (int i = 0; i < 24; i++) {
            String dump = DeviceDiagnostics.command("/system/bin/dumpsys", "bluetooth_manager");
            if (dump.contains("  state: OFF") || dump.contains("  state: BLE_ON")) return;
            Thread.sleep(400);
        }
        throw new IllegalStateException("蓝牙未能关闭");
    }

    private static boolean profileReady(String mode) {
        String dump = DeviceDiagnostics.command("/system/bin/dumpsys", "bluetooth_manager");
        String expected = "sink".equals(mode) ? "Profile: A2dpSinkService" : "Profile: A2dpService";
        String other = "sink".equals(mode) ? "Profile: A2dpService" : "Profile: A2dpSinkService";
        return dump.contains("  state: ON") && dump.contains(expected + "\n") && !dump.contains(other + "\n")
            && (!"sink".equals(mode) || dump.contains("Profile: AvrcpControllerService"));
    }

    private static void verifyController(String mode) throws Exception {
        BluetoothAdapter adapter = NativeBluetooth.adapter();
        ParcelUuid[] uuids = (ParcelUuid[]) BluetoothAdapter.class.getMethod("getUuids").invoke(adapter);
        boolean found = false;
        if (uuids != null) for (ParcelUuid uuid : uuids) found |= uuid.toString().startsWith(
            "sink".equals(mode) ? "0000110b-" : "0000110a-");
        if (!found) throw new IllegalStateException("本机音频服务 UUID 未就绪");
        try {
            Object profile = NativeBluetooth.profile(adapter, "sink".equals(mode), targetPackage);
            if (profile != null) NativeBluetooth.call(profile, "sink".equals(mode), "getConnectedDevices");
            else if (!"service-peek".equals(NativeBluetooth.access()))
                throw new IllegalStateException("音频服务控制接口未就绪");
        } finally { NativeBluetooth.close(); }
    }

    private static void waitForProfile(String mode) throws Exception {
        Exception last = new IllegalStateException("目标蓝牙音频服务未启动");
        for (int i = 0; i < 24; i++) {
            if (profileReady(mode)) try { verifyController(mode); return; }
            catch (Exception error) { last = error; }
            Thread.sleep(400);
        }
        throw last;
    }

    private static void restartCachedProcess() throws Exception {
        String ids = DeviceDiagnostics.command("/system/bin/pidof", targetPackage).trim();
        if (ids.isEmpty()) return;
        Thread.sleep(700);
        ids = DeviceDiagnostics.command("/system/bin/pidof", targetPackage).trim();
        if (ids.isEmpty()) return;
        for (String id : ids.split("\\s+"))
            if (id.matches("[0-9]+")) ModuleControl.execute(5, "/system/bin/kill", "-TERM", id);
        Thread.sleep(800);
    }
}
