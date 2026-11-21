package local.bluetooth.speaker;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One boot attempt with bounded retries, independent of OEM broadcast restrictions. */
final class BootStartup {
    static final File HOOK = new File("/data/adb/service.d/local-bluetooth-speaker.sh");

    static void sync(boolean enabled) throws Exception {
        if (!enabled) { Files.deleteIfExists(HOOK.toPath()); return; }
        // Other root providers can still use the Android boot job.
        if (!new File("/data/adb").isDirectory()) return;
        if (!HOOK.getParentFile().isDirectory() && !HOOK.getParentFile().mkdirs())
            throw new IllegalStateException("无法创建开机恢复脚本目录");
        String application = System.getenv("CLASSPATH");
        if (application == null || application.contains(":")) throw new IllegalStateException("应用路径无效");
        File temporary = new File(HOOK.getPath() + ".new");
        try (ZipFile apk = new ZipFile(application)) {
            ZipEntry entry = apk.getEntry("assets/boot-restore.sh");
            if (entry == null) throw new IllegalStateException("缺少开机恢复脚本");
            try (InputStream input = apk.getInputStream(entry)) {
                Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            ModuleControl.execute(5, "/system/bin/chmod", "0700", temporary.getPath());
            Files.move(temporary.toPath(), HOOK.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
