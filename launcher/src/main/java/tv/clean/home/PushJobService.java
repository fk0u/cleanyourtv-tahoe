package tv.clean.home;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.List;
import tech.kousozo.tahoetv.core.ControlPlane;

/** Periodic background pull for push notifications when Tahoe TV is not open in the foreground. */
public class PushJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Net.bg.execute(() -> {
            try {
                ControlPlane.DeviceProfile p = PushHelper.profile(this);
                List<ControlPlane.PushNotification> list = ControlPlane.pollPending(getFilesDir(), p);
                for (ControlPlane.PushNotification n : list) {
                    PushHelper.showSystemNotification(this, n);
                    ControlPlane.ackNotification(p.getDeviceId(), n.getId(), "delivered");
                }
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
