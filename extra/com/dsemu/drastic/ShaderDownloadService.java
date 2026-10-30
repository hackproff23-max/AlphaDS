package com.dsemu.drastic;

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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Mantem o download das shaders rodando mesmo com o app fora da tela. */
public class ShaderDownloadService extends Service {
    static final String CHANNEL = "alphads_shader_dl";
    static final int NOTIF_ID = 7421;
    private long lastNotify = 0;

    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context ctx, Uri tree) {
        start(ctx, tree, null);
    }

    /** only = pastas do repositorio a instalar (null = todas). */
    public static void start(Context ctx, Uri tree, String[] only) {
        Intent i = new Intent(ctx, ShaderDownloadService.class);
        i.putExtra("tree", tree.toString());
        if (only != null) i.putExtra("only", only);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    private Notification build(String text, int percent, boolean ongoing) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Download de shaders",
                    NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, HomeActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        b.setContentIntent(PendingIntent.getActivity(this, 0, open, fl));
        b.setContentTitle("AlphaDS - Shaders");
        b.setContentText(text);
        b.setOngoing(ongoing);
        b.setAutoCancel(!ongoing);
        b.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                : android.R.drawable.stat_sys_download_done);
        if (ongoing) b.setProgress(100, Math.max(0, percent), percent < 0);
        return b.build();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        final String t = intent == null ? null : intent.getStringExtra("tree");
        if (t == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String[] on = intent.getStringArrayExtra("only");
        final Set<String> only = on == null ? null : new HashSet<String>(Arrays.asList(on));
        startForeground(NOTIF_ID, build("Iniciando...", -1, true));
        if (ShaderTools.dlRunning) return START_NOT_STICKY;
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ShaderTools.startDownload(getApplicationContext(), Uri.parse(t), only,
                new ShaderTools.Progress() {
                    public void update(String label, int percent) {
                        long now = System.currentTimeMillis();
                        if (now - lastNotify < 500 && percent != 100) return;
                        lastNotify = now;
                        nm.notify(NOTIF_ID, build(label, percent, true));
                    }
                },
                new ShaderTools.Result() {
                    public void done(String summary) {
                        stopForeground(true);
                        String first = summary;
                        int nl = first.indexOf('\n');
                        if (nl > 0) first = first.substring(0, nl);
                        nm.notify(NOTIF_ID + 1, build(first, 100, false));
                        stopSelf();
                    }
                });
        return START_NOT_STICKY;
    }
}
