package dev.aarstad.shader.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * A foreground service, declared up front for the same reason as {@link
 * PluginScreen}: a push cannot add one, so it has to exist before a plugin
 * wants to keep working with the app off screen.
 */
public final class PluginService extends Service {

    private static final String CHANNEL_ID = "plugin";
    private static final int NOTIFICATION_ID = 1;
    private static final String EXTRA_LABEL = "label";

    public static void start(Context ctx, String label) {
        Intent i = new Intent(ctx, PluginService.class).putExtra(EXTRA_LABEL, label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, PluginService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent == null ? null : intent.getStringExtra(EXTRA_LABEL);
        if (label == null || label.isEmpty()) label = "running";

        enterForeground(NOTIFICATION_ID, notification(label));

        HostActivity host = HostActivity.current();
        if (host != null) host.plugins.event(Plugin.SERVICE_START, label);

        // Not sticky: a restart would arrive with a null intent and no plugin to tell, which is a service running for nobody.
        return START_NOT_STICKY;
    }

    private Notification notification(String label) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Plugin", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getPackageName())
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build();
    }

    /** Not named startForeground: Service declares that final. */
    private void enterForeground(int id, Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(id, n);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        HostActivity host = HostActivity.current();
        if (host != null) host.plugins.event(Plugin.SERVICE_STOP);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
