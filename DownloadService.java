package com.linkrouter.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads one link at a time in the background. Notification shows progress and disappears when done. */
public class DownloadService extends Service {
    private static final String CH = "dl";
    private static final int NID = 2;
    private static int resultId = 100;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private int pending = 0;

    public static void start(Context c, String url) { start(c, url, null); }

    public static void start(Context c, String url, String fmt) {
        c.startForegroundService(new Intent(c, DownloadService.class).putExtra("url", url).putExtra("fmt", fmt));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "CANCEL".equals(intent.getAction())) { Downloader.cancel(); return START_NOT_STICKY; }
        final String url = intent == null ? null : intent.getStringExtra("url");
        final String fmt = intent == null ? null : intent.getStringExtra("fmt");
        if (url == null) { stopSelf(); return START_NOT_STICKY; }
        getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW));
        Notification n = progress("Preparing download...", -1);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NID, n);
        pending++;
        ex.submit(() -> work(url, fmt));
        return START_NOT_STICKY;
    }

    private Notification progress(String text, int pct) {
        PendingIntent cancel = PendingIntent.getService(this, 0, new Intent(this, DownloadService.class).setAction("CANCEL"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        if (pct >= 0) b.setProgress(100, pct, false); else b.setProgress(0, 0, true);
        return b.build();
    }

    private void work(String url, String fmt) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            Downloader.Result res = Downloader.download(this, url, fmt, (pct, line) -> {
                String t = pct >= 0 ? pct + "%" : (line == null ? "Working..." : line);
                nm.notify(NID, progress(t, pct));
            });
            Uri out = res.uri;
            Downloader.addHistory(this, url, res.name, "done");
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(out, getContentResolver().getType(out))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, resultId, view, PendingIntent.FLAG_IMMUTABLE);
            nm.notify(resultId++, new Notification.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download complete")
                    .setContentText((res.count > 1 ? res.count + " files saved. " : "") + res.name)
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage()).trim();
            int cut = m.lastIndexOf("ERROR:");
            if (cut >= 0) m = m.substring(cut);
            if (m.length() > 200) m = m.substring(0, 200);
            Downloader.addHistory(this, url, Downloader.cancelled ? "Cancelled" : m, Downloader.cancelled ? "cancelled" : "failed");
            nm.notify(resultId++, new Notification.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(Downloader.cancelled ? "Download cancelled" : "Download failed")
                    .setContentText(Downloader.cancelled ? url : m)
                    .setStyle(new Notification.BigTextStyle().bigText(m))
                    .setAutoCancel(true).build());
        } finally {
            if (--pending <= 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        }
    }

    @Override public void onDestroy() { ex.shutdownNow(); super.onDestroy(); }
}
