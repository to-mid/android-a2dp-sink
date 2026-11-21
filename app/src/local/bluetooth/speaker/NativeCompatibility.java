package local.bluetooth.speaker;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Fingerprinted workarounds for shipped MIUI 12.5 thyme Bluetooth defects. */
final class NativeCompatibility {
    private static final File LIBRARY = new File("/system_ext/lib64/libbluetooth_qti.so");
    private static final File DIRECTORY = new File("/data/adb/local-bluetooth-speaker");
    private static final File GUARDED = new File(DIRECTORY, "libbluetooth_qti.guard.so");
    private static final String ORIGINAL_HASH = "169d1823c3a0d6d07fd87da84632317fbc793f82e1dd956e573a057a5e652adb";
    private static final String LEGACY_GUARDED_HASH = "e87d3674fad2e115851ea09e3bd251578943c3be201ac26e9229fcabe69894fc";
    private static final String QUEUE_GUARDED_HASH = "ad43df523b943b0c14442cc169b77308b960a371cd8d8bd304524a73fb4331cf";
    private static final String GUARDED_HASH = "e45acdc768aff7f1ab1b70f723e8d46038aaf6ec8da31f765abfbf5b74ad17b9";
    private static final int OFFSET = 0x20c48c;
    private static final byte[] ORIGINAL = hex("ba0e00d048877339830e403961f2ffb0");
    // CHANGED response: preserve x26 logging base; guard list; continue original processing.
    // Replaces only debug logging. Valid lists follow the original processing.
    private static final byte[] PATCH = hex("ba0e00d068ba41f9883a00b420000014");
    private static final int CONNECT_OFFSET = 0x1c1f2c;
    private static final byte[] CONNECT_ORIGINAL = hex("5e8600f8fd7bbea9f44f01a9fd030091141100b08886733941f3ffd022f5ff90f30300aa1f11007121ec289142ec369160a0805263000054d702fd97020000144e03fd97e8110090e9110090089941f929cd41f9080109aac80100b4c81100f0089147f9680100b4081100d0031941b9e10313aaf44f41a9821000d04220199160218252fd7bc2a85e8e5ff85c00011488867339e1f3ffb022f5ff9021302b9142ec369120a080521f09007163000054b902fd97020000143003fd97f44f41a940008052fd7bc2a85e8e5ff8c0035fd6");
    // sink_connect_src: preserve readiness checks and the original enqueue ABI;
    // if this exact peer already has an opening/open/started/closing state,
    // return success before enqueuing the redundant request. This breaks the
    // OEM incoming-event -> connect -> queued retry feedback loop.
    private static final byte[] CONNECT_PATCH = hex("5e8600f8fd7bbea9f44f01a9fd030091f30300aae8110090099941f908cd41f9080109aa080300b4c81100f0089147f9a80200b4e00313aa6ebcff97081100d0081941b91f00086ba2000054a7f3ff9760000034e0031f2a0c000014081100d0031941b9e10313aaf44f41a9821000d04220199160218252fd7bc2a85e8e5ff85f00011440008052f44f41a9fd7bc2a85e8e5ff8c0035fd61f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5");

    private static final int SDP_OFFSET = 0x1139f0;
    private static final byte[] SDP_ORIGINAL = hex("68d65339c80200348842825249208052a8020079695609797fd61339088373391f0900712302005481f8ffd0e2f8ffd021082f914218359120a0805225bcff970a000014");
    // Reuse the shipped conservative AVDTP path only for local AudioSink.
    // This starts real transport negotiation; it does not report connected.
    private static final byte[] SDP_PATCH = hex("68d65339a800003568e64979692182521f01096b410200548842825249208052a8020079695609797fd613390f0000141f2003d51f2003d51f2003d51f2003d51f2003d5");

    static String status() {
        try {
            if (!LIBRARY.isFile()) return "not-required";
            String hash = digest(LIBRARY);
            if (GUARDED_HASH.equals(hash)) return "miui-a2dp-reconnect";
            if (QUEUE_GUARDED_HASH.equals(hash)) return "miui-avrcp-and-connect-queue";
            if (LEGACY_GUARDED_HASH.equals(hash)) return "miui-avrcp-null-list";
            return ORIGINAL_HASH.equals(hash) ? "available" : "not-required";
        } catch (Exception error) { return "unknown"; }
    }

    static boolean ready(String mode) {
        String state = status();
        boolean old = "miui-avrcp-null-list".equals(state) || "miui-avrcp-and-connect-queue".equals(state);
        return "sink".equals(mode) ? !"available".equals(state) && !old
            : !old && !"miui-a2dp-reconnect".equals(state);
    }

    static void sync(String mode) throws Exception {
        String state = status();
        boolean legacy = "miui-avrcp-null-list".equals(state) || "miui-avrcp-and-connect-queue".equals(state);
        if (!"sink".equals(mode) || legacy) {
            if (legacy || "miui-a2dp-reconnect".equals(state)) {
                // The stopped service may still hold a mapping until its process exits.
                master("umount -l " + LIBRARY.getPath());
                if (!ORIGINAL_HASH.equals(digest(LIBRARY)))
                    throw new IllegalStateException("蓝牙兼容补丁卸载后校验失败");
            }
            if (!"sink".equals(mode)) return;
            state = status();
        }
        if (!"available".equals(state)) return;
        byte[] data = Files.readAllBytes(LIBRARY.toPath());
        if (!Arrays.equals(ORIGINAL, Arrays.copyOfRange(data, OFFSET, OFFSET + ORIGINAL.length)))
            throw new IllegalStateException("蓝牙兼容补丁指令校验失败");
        System.arraycopy(PATCH, 0, data, OFFSET, PATCH.length);
        if (!Arrays.equals(CONNECT_ORIGINAL,
                Arrays.copyOfRange(data, CONNECT_OFFSET, CONNECT_OFFSET + CONNECT_ORIGINAL.length)))
            throw new IllegalStateException("蓝牙连接兼容补丁指令校验失败");
        System.arraycopy(CONNECT_PATCH, 0, data, CONNECT_OFFSET, CONNECT_PATCH.length);
        if (!Arrays.equals(SDP_ORIGINAL,
                Arrays.copyOfRange(data, SDP_OFFSET, SDP_OFFSET + SDP_ORIGINAL.length)))
            throw new IllegalStateException("蓝牙服务发现兼容补丁指令校验失败");
        System.arraycopy(SDP_PATCH, 0, data, SDP_OFFSET, SDP_PATCH.length);
        if (!DIRECTORY.isDirectory() && !DIRECTORY.mkdirs())
            throw new IllegalStateException("无法创建蓝牙兼容补丁目录");
        ModuleControl.execute(5, "/system/bin/chmod", "0700", DIRECTORY.getPath());
        File temporary = new File(DIRECTORY, "libbluetooth_qti.guard.new");
        try {
            Files.write(temporary.toPath(), data);
            if (!GUARDED_HASH.equals(digest(temporary)))
                throw new IllegalStateException("蓝牙兼容补丁内容校验失败");
            ModuleControl.execute(5, "/system/bin/chmod", "0644", temporary.getPath());
            ModuleControl.execute(5, "/system/bin/chcon", "u:object_r:system_file:s0", temporary.getPath());
            Files.move(temporary.toPath(), GUARDED.toPath(), StandardCopyOption.REPLACE_EXISTING);
            master("mount --bind " + GUARDED.getPath() + " " + LIBRARY.getPath());
            if (!GUARDED_HASH.equals(digest(LIBRARY)))
                throw new IllegalStateException("蓝牙兼容补丁挂载校验失败");
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    private static void master(String command) throws Exception {
        ModuleControl.execute(10, "/system/bin/su", "-mm", "-c", command);
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[16384];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
