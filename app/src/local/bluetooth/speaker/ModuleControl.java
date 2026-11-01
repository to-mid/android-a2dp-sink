package local.bluetooth.speaker;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** Compatibility with configurations installed by 2.0. No module installation in 3.0. */
final class ModuleControl {
    static final File ACTIVE = new File("/data/adb/modules/bt_speaker_thyme");
    static final File STAGED = new File("/data/adb/modules_update/bt_speaker_thyme");

    static boolean validMode(String value, boolean allowLast) {
        return "source".equals(value) || "sink".equals(value) || allowLast && "last".equals(value);
    }

    static String read(File file, String fallback) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim(); }
        catch (Exception ignored) { return fallback; }
    }

    static String defaultMode() {
        String value = read(new File(ACTIVE, "state/default_mode"), "source");
        return validMode(value, true) ? value : "source";
    }

    static boolean enabled() {
        return ACTIVE.isDirectory() && !new File(ACTIVE, "disable").exists()
            && !new File(ACTIVE, "remove").exists();
    }

    static void setDefault(String value) throws Exception {
        if (!validMode(value, true) || !enabled()) throw new IllegalStateException("旧系统兼容配置不可用");
        write(ACTIVE, "default_mode", value);
        if (STAGED.isDirectory()) write(STAGED, "default_mode", value);
    }

    static void changeMode(String value) throws Exception {
        if (!validMode(value, false) || !enabled()) throw new IllegalStateException("旧系统兼容配置不可用");
        execute(85, "/system/bin/sh", new File(ACTIVE, "mode.sh").getPath(), value);
        if (STAGED.isDirectory()) write(STAGED, "current_mode", value);
    }

    private static void write(File directory, String name, String value) throws Exception {
        Path state = new File(directory, "state").toPath();
        Files.createDirectories(state);
        Path temporary = state.resolve(name + ".tmp");
        Files.write(temporary, (value + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, state.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    static void prepareComponents() throws Exception {
        Object manager = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        Class<?> type = Class.forName("android.content.pm.IPackageManager");
        java.lang.reflect.Method get = type.getMethod("getComponentEnabledSetting", ComponentName.class, int.class);
        java.lang.reflect.Method set = type.getMethod("setComponentEnabledSetting",
            ComponentName.class, int.class, int.class, int.class);
        for (String name : new String[]{"a2dpsink.A2dpSinkService", "avrcpcontroller.AvrcpControllerService",
                "avrcpcontroller.BluetoothMediaBrowserService"}) {
            ComponentName component = new ComponentName("com.android.bluetooth", "com.android.bluetooth." + name);
            if (!Integer.valueOf(PackageManager.COMPONENT_ENABLED_STATE_ENABLED).equals(get.invoke(manager, component, 0))) {
                set.invoke(manager, component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP, 0);
            }
        }
    }

    static String execute(int timeoutSeconds, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() < 131072) output.write(buffer, 0, count);
                }
            } catch (Exception ignored) {}
        });
        drain.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            drain.join(1000);
            throw new IllegalStateException("系统命令超时");
        }
        drain.join(1000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) throw new IllegalStateException(text.isEmpty() ? "系统命令失败" : text);
        return text;
    }
}
