package com.amrakshay.movieserve.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;

/**
 * Keeps the server alive while a film is playing.
 *
 * <p>A plain background thread would be killed as soon as the screen went off or the app left the
 * foreground, which on a phone is most of the time a film is playing. A foreground service with an
 * ongoing notification is the supported way to say "this is still doing something the owner asked
 * for", and the notification doubles as the way to stop it.
 *
 * <p>A partial wake lock is held alongside it. The service survives the screen going off, but the
 * CPU can still be allowed to doze, which stalls a stream mid-film.
 */
public class ServerService extends Service {

    static final String ACTION_START = "com.amrakshay.movieserve.phone.START";
    static final String ACTION_STOP = "com.amrakshay.movieserve.phone.STOP";
    /** Broadcast so an open screen can refresh when the server starts, stops or fails. */
    static final String ACTION_STATE_CHANGED = "com.amrakshay.movieserve.phone.STATE";
    static final String EXTRA_ERROR = "error";

    private static final String TAG = "movieserve";
    private static final String CHANNEL_ID = "movieserve-server";
    private static final int NOTIFICATION_ID = 1;

    /** Read by the UI, so it can show the right button without binding to the service. */
    private static volatile boolean running = false;

    private HttpServer server;
    private PowerManager.WakeLock wakeLock;

    static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (running) {
            // Already up; refresh what it is serving in case the folder changed.
            if (server != null) {
                server.setLibrary(currentLibrary());
            }
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)));

        server = new HttpServer(this, Prefs.PORT, currentLibrary());
        try {
            server.start();
        } catch (IOException e) {
            Log.w(TAG, "could not bind port " + Prefs.PORT, e);
            announce(getString(R.string.error_port_busy, Prefs.PORT));
            shutdown();
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        running = true;
        String url = Net.serverUrl();
        updateNotification(url == null ? getString(R.string.no_network) : url);
        announce(null);
        return START_STICKY;
    }

    private Library currentLibrary() {
        Uri tree = Prefs.tree(this);
        String url = Prefs.videoUrl(this);
        return new Library(this, tree, Prefs.isPlayableUrl(url) ? url : null);
    }

    private void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        releaseWakeLock();
        running = false;
        announce(null);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;    // started, never bound
    }

    // ---- notification ------------------------------------------------------------------------

    private Notification buildNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null
                && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ServerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.stop), stop).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void announce(String error) {
        Intent intent = new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName());
        if (error != null) {
            intent.putExtra(EXTRA_ERROR, error);
        }
        sendBroadcast(intent);
    }

    // ---- wake lock ---------------------------------------------------------------------------

    private void acquireWakeLock() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) {
            return;
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "movieserve:server");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, ServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.startService(new Intent(context, ServerService.class).setAction(ACTION_STOP));
    }
}
