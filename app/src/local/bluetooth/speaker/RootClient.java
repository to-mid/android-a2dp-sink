package local.bluetooth.speaker;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class RootClient {
    static JSONObject call(Context context, String command, String argument) throws Exception {
        String line = "CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
            + " /system/bin/app_process /system/bin local.bluetooth.speaker.RootCommand " + quote(command);
        if (argument != null) line += " " + quote(argument);
        Process process = new ProcessBuilder("su", "-c", line).redirectErrorStream(true).start();
        AtomicReference<JSONObject> response = new AtomicReference<>();
        StringBuilder diagnostics = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String value;
                while ((value = input.readLine()) != null) {
                    if (diagnostics.length() < 16384) diagnostics.append(value).append('\n');
                    if (value.startsWith("{")) {
                        try { response.set(new JSONObject(value)); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        });
        reader.start();
        try {
            if (!process.waitFor(105, TimeUnit.SECONDS)) {
                throw new IllegalStateException("操作超时，请检查 root 授权和蓝牙状态");
            }
            reader.join(1000);
            if (response.get() == null) throw new IllegalStateException(diagnostics.length() == 0
                ? "未获得 root 授权" : diagnostics.toString().trim());
            return response.get();
        } finally {
            process.destroyForcibly();
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
