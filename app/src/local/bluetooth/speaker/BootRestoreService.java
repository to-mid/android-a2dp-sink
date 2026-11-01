package local.bluetooth.speaker;

import android.app.job.JobParameters;
import android.app.job.JobService;
import org.json.JSONObject;

public final class BootRestoreService extends JobService {
    private Thread worker;

    @Override public boolean onStartJob(JobParameters parameters) {
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
            createDeviceProtectedStorageContext().getSharedPreferences("startup", MODE_PRIVATE)
                .edit().putString("lastError", error).apply();
            jobFinished(parameters, false);
        }, "bluetooth-boot-restore");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
