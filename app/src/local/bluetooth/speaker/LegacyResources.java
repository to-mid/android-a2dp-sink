package local.bluetooth.speaker;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class LegacyResources {
    static final String PACKAGE = "local.bluetooth.speaker.overlay";
    static final String[] RESOURCES = {
        "profile_supported_a2dp_sink", "profile_supported_avrcp_controller", DeviceDiagnostics.FOCUS,
        "profile_supported_a2dp", "profile_supported_avrcp_target"
    };
    private static final String[] COMPONENTS = {
        "a2dpsink.A2dpSinkService", "avrcpcontroller.AvrcpControllerService",
        "avrcpcontroller.BluetoothMediaBrowserService"
    };

    static boolean supported(String targetPackage) {
        return "com.android.bluetooth".equals(targetPackage) && new File("/system/bin/idmap2").canExecute();
    }

    static JSONObject components() throws Exception {
        JSONObject states = new JSONObject();
        Object manager = manager();
        Method get = Class.forName("android.content.pm.IPackageManager")
            .getMethod("getComponentEnabledSetting", ComponentName.class, int.class);
        for (String name : COMPONENTS) if (serviceExists(name)) states.put(name, get.invoke(manager, component(name), 0));
        return states;
    }

    static void restoreComponents(JSONObject states) throws Exception {
        if (states == null) return;
        Object manager = manager();
        for (String name : COMPONENTS) if (states.has(name)) {
            setComponent(manager, component(name), states.getInt(name));
        }
    }

    static void prepareComponents() throws Exception {
        Object manager = manager();
        for (String name : COMPONENTS) if (serviceExists(name))
            setComponent(manager, component(name), PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
    }

    private static boolean serviceExists(String name) throws Exception {
        Class<?> type = Class.forName("android.content.pm.IPackageManager");
        try {
            return type.getMethod("getServiceInfo", ComponentName.class, long.class, int.class)
                .invoke(manager(), component(name), (long) PackageManager.MATCH_DISABLED_COMPONENTS, 0) != null;
        } catch (NoSuchMethodException older) {
            return type.getMethod("getServiceInfo", ComponentName.class, int.class, int.class)
                .invoke(manager(), component(name), PackageManager.MATCH_DISABLED_COMPONENTS, 0) != null;
        }
    }

    private static void setComponent(Object manager, ComponentName component, int state) throws Exception {
        Class<?> type = Class.forName("android.content.pm.IPackageManager");
        try {
            type.getMethod("setComponentEnabledSetting", ComponentName.class, int.class, int.class, int.class)
                .invoke(manager, component, state, PackageManager.DONT_KILL_APP, 0);
        } catch (NoSuchMethodException newer) {
            type.getMethod("setComponentEnabledSetting", ComponentName.class, int.class, int.class, int.class, String.class)
                .invoke(manager, component, state, PackageManager.DONT_KILL_APP, 0, "android");
        }
    }

    private static Object manager() throws Exception {
        return Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
    }

    private static ComponentName component(String name) {
        return new ComponentName(RootController.targetPackage(), "com.android.bluetooth." + name);
    }

    private static String installed() {
        String path = DeviceDiagnostics.command("/system/bin/pm", "path", PACKAGE);
        for (String line : path.split("\n")) if (line.startsWith("package:")) return line.substring(8);
        return "";
    }

    private static String ensureInstalled() throws Exception {
        String application = System.getenv("CLASSPATH");
        if (application == null || application.contains(":")) throw new IllegalStateException("应用路径无效");
        String current = installed();
        try (ZipFile apk = new ZipFile(application)) {
            ZipEntry asset = apk.getEntry("assets/legacy-bluetooth-resources.apk");
            if (asset == null || asset.getSize() < 1 || asset.getSize() > 1048576) {
                throw new IllegalStateException("缺少旧系统资源覆盖");
            }
            if (!current.isEmpty()) {
                if (!current.startsWith("/data/app/")) throw new IllegalStateException("发现已有系统覆盖，请先清理旧兼容配置");
                try (InputStream bundled = apk.getInputStream(asset);
                        InputStream existing = Files.newInputStream(new File(current).toPath())) {
                    if (Arrays.equals(digest(bundled), digest(existing))) return current;
                }
            }
            java.nio.file.Path temporary = Files.createTempFile(new File("/data/local/tmp").toPath(), "a2dp-resources-", ".apk");
            try {
                try (InputStream input = apk.getInputStream(asset)) {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                ModuleControl.execute(25, "/system/bin/pm", "install", "-r", temporary.toString());
            } finally { Files.deleteIfExists(temporary); }
        }
        current = installed();
        if (!current.startsWith("/data/app/")) throw new IllegalStateException("资源覆盖未安装");
        return current;
    }

    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        return hash.digest();
    }

    static void enable() throws Exception {
        String overlay = ensureInstalled();
        String target = DeviceDiagnostics.command("/system/bin/pm", "path", "com.android.bluetooth");
        if (!target.startsWith("package:")) throw new IllegalStateException("找不到蓝牙系统包");
        String idmap = "/data/resource-cache/" + overlay.substring(1).replace('/', '@') + "@idmap";
        String temporary = idmap + ".new";
        // The Android 11 package remains on /data; root supplies its resource mapping.
        try {
            ModuleControl.execute(10, "/system/bin/idmap2", "create",
                "--target-apk-path", target.substring(8), "--overlay-apk-path", overlay,
                "--idmap-path", temporary, "--ignore-overlayable");
            ModuleControl.execute(5, "/system/bin/chown", "1000:1000", temporary);
            ModuleControl.execute(5, "/system/bin/chmod", "0644", temporary);
            ModuleControl.execute(5, "/system/bin/restorecon", temporary);
            Files.move(new File(temporary).toPath(), new File(idmap).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(new File(temporary).toPath()); }
        ModuleControl.execute(10, "/system/bin/cmd", "overlay", "enable", "--user", "0", PACKAGE);
        for (int i = 0; i < RESOURCES.length; i++) {
            String name = RESOURCES[i];
            String value = ModuleControl.execute(8, "/system/bin/cmd", "overlay", "lookup",
                "com.android.bluetooth", "com.android.bluetooth:bool/" + name).trim();
            if (!String.valueOf(i < 3).equals(value)) throw new IllegalStateException("资源覆盖未生效：" + name);
        }
        prepareComponents();
    }

    static boolean ready() {
        for (int i = 0; i < RESOURCES.length; i++) {
            if (!String.valueOf(i < 3).equals(DeviceDiagnostics.command("/system/bin/cmd", "overlay", "lookup",
                    "com.android.bluetooth", "com.android.bluetooth:bool/" + RESOURCES[i]))) return false;
        }
        return true;
    }

    static void disable() throws Exception {
        if (installed().isEmpty()) return;
        ModuleControl.execute(10, "/system/bin/cmd", "overlay", "disable", "--user", "0", PACKAGE);
    }
}
