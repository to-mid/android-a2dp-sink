package local.bluetooth.speaker;

import android.app.job.JobParameters;
import android.app.job.JobService;
import org.json.JSONObject;

public final class BootRestoreService extends JobService {
    private Thread worker;
    private volatile JobParameters active;

    @Override public boolean onStartJob(JobParameters parameters) {
        active = parameters;
        worker = new Thread(() -> {
            String error = "";
            try {
                JSONObject response = RootClient.call(this, "apply-boot", null);
                error = response.optString("error");
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                error = failure.toString();
            }
            String outcome = error;
            getMainExecutor().execute(() -> {
                if (active != parameters) return;
                active = null;
                createDeviceProtectedStorageContext().getSharedPreferences("startup", MODE_PRIVATE)
                    .edit().putString("lastError", outcome).apply();
                jobFinished(parameters, !outcome.isEmpty());
            });
        }, "bluetooth-boot-restore");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        active = null;
        if (worker != null) worker.interrupt();
        return true;
    }
}
