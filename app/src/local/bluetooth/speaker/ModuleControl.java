package local.bluetooth.speaker;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModuleControl {
    static final File ACTIVE = new File("/data/adb/modules/bt_speaker_thyme");
    static final File STAGED = new File("/data/adb/modules_update/bt_speaker_thyme");
    private static final String OVERLAY_PATH = "system/product/overlay/BluetoothSpeaker/BluetoothSpeakerOverlay.apk";

    static boolean validMode(String value, boolean allowLast) {
        return "source".equals(value) || "sink".equals(value) || (allowLast && "last".equals(value));
    }

    static String read(File file, String fallback) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim(); }
        catch (Exception ignored) { return fallback; }
    }

    static String defaultMode() {
        String mode = read(new File(ACTIVE, "state/default_mode"), "source");
        return validMode(mode, true) ? mode : "source";
    }

    static boolean enabled() {
        return ACTIVE.isDirectory() && !new File(ACTIVE, "disable").exists()
            && !new File(ACTIVE, "remove").exists();
    }

    static int version() {
        try {
            Properties props = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(new File(ACTIVE, "module.prop").toPath(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return Integer.parseInt(props.getProperty("versionCode", "0"));
        } catch (Exception ignored) { return 0; }
    }

    static void setDefault(String mode) throws Exception {
        if (!validMode(mode, true)) throw new IllegalArgumentException("Invalid boot mode");
        if (!enabled()) throw new IllegalStateException("请先安装并启用内置模块");
        writeMode(ACTIVE, "default_mode", mode);
        if (STAGED.isDirectory()) writeMode(STAGED, "default_mode", mode);
    }

    static void changeMode(String mode) throws Exception {
        if (!validMode(mode, false)) throw new IllegalArgumentException("Invalid audio mode");
        if (!enabled() || !new File(ACTIVE, "mode.sh").isFile()) {
            throw new IllegalStateException("请先安装或升级内置模块");
        }
        execute(85, "/system/bin/sh", new File(ACTIVE, "mode.sh").getPath(), mode);
        if (STAGED.isDirectory()) writeMode(STAGED, "current_mode", mode);
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
                set.invoke(manager, component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP, 0);
            }
        }
    }

    static JSONObject install() throws Exception {
        String apkPath = System.getenv("CLASSPATH");
        if (apkPath == null || apkPath.contains(":")) throw new IllegalArgumentException("Invalid application path");
        Path temporary = Files.createTempDirectory(Paths.get("/data/local/tmp"), "speaker-module-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path archive = temporary.resolve("module.zip");
        try {
            try (ZipFile apk = new ZipFile(apkPath)) {
                ZipEntry bundled = apk.getEntry("assets/bluetooth-speaker-module.zip");
                if (bundled == null || bundled.getSize() < 1 || bundled.getSize() > 4194304) {
                    throw new IllegalStateException("内置模块包无效");
                }
                try (InputStream input = apk.getInputStream(bundled)) { Files.copy(input, archive); }
            }
            return installFile(archive.toFile());
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(temporary);
        }
    }

    private static JSONObject installFile(File zip) throws Exception {
        execute(90, "magisk", "--install-module", zip.getPath());
        if (!new File(STAGED, "module.prop").isFile()) throw new IllegalStateException("模块未进入安装队列");
        JSONObject result = new JSONObject();
        // Existing installations can update scripts immediately when mounted files are identical.
        File currentOverlay = new File(ACTIVE, OVERLAY_PATH);
        File stagedOverlay = new File(STAGED, OVERLAY_PATH);
        boolean hotUpgrade = currentOverlay.isFile() && stagedOverlay.isFile()
            && Arrays.equals(digest(currentOverlay), digest(stagedOverlay));
        if (hotUpgrade) {
            String[] files = {"service.sh", "post-fs-data.sh", "mode.sh",
                "uninstall.sh", "cleanup.sh", "action.sh", "module.prop"};
            for (String name : files) {
                if (!new File(STAGED, name).isFile()) throw new IllegalStateException("安装包缺少文件：" + name);
            }
            for (String name : files) {
                File from = new File(STAGED, name);
                File to = new File(ACTIVE, name);
                Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.setPosixFilePermissions(to.toPath(), PosixFilePermissions.fromString(
                    name.endsWith(".sh") ? "rwxr-xr-x" : "rw-r--r--"));
            }
            for (String name : new String[]{"default_mode", "current_mode"}) {
                File to = new File(ACTIVE, "state/" + name);
                if (!to.isFile()) {
                    String value = read(new File(STAGED, "state/" + name), "source");
                    writeMode(ACTIVE, name, value);
                }
            }
            Files.deleteIfExists(new File(ACTIVE, "system.prop").toPath());
            Files.deleteIfExists(new File(ACTIVE, "customize.sh").toPath());
            Files.deleteIfExists(new File(ACTIVE, "disable").toPath());
            Files.deleteIfExists(new File(ACTIVE, "remove").toPath());
            Files.deleteIfExists(new File(ACTIVE, "update").toPath());
            deleteStagedModule();
        }
        result.put("ok", true);
        result.put("rebootRequired", !hotUpgrade);
        result.put("hotUpgrade", hotUpgrade);
        return result;
    }

    private static void writeMode(File module, String name, String mode) throws Exception {
        Path state = new File(module, "state").toPath();
        Files.createDirectories(state);
        Path temporary = state.resolve(name + ".tmp");
        Files.write(temporary, (mode + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, state.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hash.digest();
    }

    private static void deleteStagedModule() throws Exception {
        // The fixed directory belongs to this module; traversal does not follow symlinks.
        Files.walkFileTree(STAGED.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, java.io.IOException error) throws java.io.IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
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
            throw new IllegalStateException("操作超时，请检查蓝牙状态");
        }
        drain.join(1000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) throw new IllegalStateException(text.isEmpty() ? "系统命令执行失败" : text);
        return text;
    }
}
