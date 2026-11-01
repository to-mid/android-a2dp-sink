package local.bluetooth.speaker;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class AudioRuntime {
    static JSONObject bluetoothMedia() {
        JSONObject result = new JSONObject();
        try {
            result.put("observed", false).put("active", false);
            String ids = DeviceDiagnostics.command("/system/bin/pidof", "com.android.bluetooth");
            if (ids.isEmpty()) ids = DeviceDiagnostics.command("/system/bin/pidof", "com.google.android.bluetooth");
            Set<String> clients = new HashSet<>(Arrays.asList(ids.split("\\s+")));
            Process process = new ProcessBuilder("/system/bin/dumpsys", "media.audio_flinger").redirectErrorStream(true).start();
            Thread parser = new Thread(() -> {
                String device = "";
                boolean table = false;
                boolean pidUid = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Output thread ")) { device = ""; table = false; }
                        if (line.trim().startsWith("Output devices:")) device = line.trim().substring(15).trim();
                        if (line.trim().matches("[0-9]+ Tracks of which [0-9]+ are active.*")) { table = true; continue; }
                        if (line.contains("Effect Chains") || line.trim().startsWith("Local log:")) table = false;
                        if (!table) continue;
                        if (line.contains("Active Client")) {
                            pidUid = line.contains("Client(pid/uid)");
                            continue;
                        }
                        String[] fields = line.trim().split("\\s+");
                        for (int i = 1; i + 11 < fields.length; i++) {
                            if (!"yes".equals(fields[i - 1]) && !"no".equals(fields[i - 1])) continue;
                            String pid = fields[i];
                            if (pidUid) {
                                if (!pid.endsWith("/") || !"1002".equals(fields[i + 1])) continue;
                                pid = pid.substring(0, pid.length() - 1);
                            }
                            if (!clients.contains(pid)) continue;
                            int base = i + (pidUid ? 1 : 0);
                            if (!"3".equals(fields[base + 8]) || !"1".equals(fields[base + 9])) continue;
                            if (pidUid && !"2".equals(fields[base + 10])) continue;
                            result.put("observed", true);
                            if ("yes".equals(fields[i - 1])) {
                                result.put("active", true);
                                result.put("sampleRate", Integer.parseInt(fields[base + 7]));
                                result.put("format", fields[base + 5]);
                                result.put("channelMask", fields[base + 6]);
                                result.put("device", device);
                            }
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            });
            parser.start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                parser.join(1000);
                return new JSONObject().put("observed", false).put("active", false);
            }
            parser.join(1000);
            if (parser.isAlive() || process.exitValue() != 0) {
                return new JSONObject().put("observed", false).put("active", false);
            }
        } catch (Exception ignored) {}
        return result;
    }
}
