package fr.alexarzel.declic;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;

/** Rappels d'hydratation : toutes les N heures entre deux horaires, sauf si l'objectif du jour est atteint. */
public class Water extends BroadcastReceiver {
    static final String ACTION = "fr.alexarzel.declic.EAU";
    static final String CHANNEL = "eau";

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("declic_water", Context.MODE_PRIVATE);
    }

    static void save(Context ctx, String json) {
        prefs(ctx).edit().putString("cfg", json).apply();
        schedule(ctx);
    }

    static void mark(Context ctx, String day, int count, int goal) {
        prefs(ctx).edit().putString("day", day).putInt("count", count).putInt("goal", goal).apply();
    }

    static int count(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return Steps.today().equals(p.getString("day", "")) ? p.getInt("count", 0) : 0;
    }

    private static PendingIntent pi(Context ctx) {
        Intent i = new Intent(ctx, Water.class).setAction(ACTION);
        return PendingIntent.getBroadcast(ctx, 4, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(ctx));
        try {
            JSONObject c = new JSONObject(prefs(ctx).getString("cfg", "{}"));
            if (!c.optBoolean("on", false)) return;
            int from = c.optInt("from", 9), to = c.optInt("to", 21), every = Math.max(1, c.optInt("every", 2));
            Calendar now = Calendar.getInstance();
            for (int d = 0; d < 2; d++) {
                for (int h = from; h <= to; h += every) {
                    Calendar t = Calendar.getInstance();
                    t.add(Calendar.DAY_OF_YEAR, d);
                    t.set(Calendar.HOUR_OF_DAY, h);
                    t.set(Calendar.MINUTE, 0);
                    t.set(Calendar.SECOND, 0);
                    t.set(Calendar.MILLISECOND, 0);
                    if (t.after(now)) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi(ctx));
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void notify(Context ctx, int count, int goal) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Hydratation", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Te rappelle de boire de l'eau");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 5, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Un verre d'eau ?")
                .setContentText("Tu en es à " + count + " / " + goal + " verres aujourd'hui.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(9, n);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION.equals(intent.getAction())) {
            int goal = prefs(ctx).getInt("goal", 8);
            int c = count(ctx);
            if (c < goal) notify(ctx, c, goal);
        }
        schedule(ctx);
    }
}
