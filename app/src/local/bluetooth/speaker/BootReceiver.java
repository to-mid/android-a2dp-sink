package local.bluetooth.speaker;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        Context storage = context.createDeviceProtectedStorageContext();
        storage.getFilesDir();
        if (!storage.getSharedPreferences("startup", Context.MODE_PRIVATE).getBoolean("enabled", false)) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler.getPendingJob(3060) != null) return;
        scheduler.schedule(new JobInfo.Builder(3060, new ComponentName(context, BootRestoreService.class))
            .setOverrideDeadline(0).build());
    }
}
