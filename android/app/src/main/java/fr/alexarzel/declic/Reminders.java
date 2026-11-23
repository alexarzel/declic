package fr.alexarzel.declic;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Notification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/** Rappels quotidiens : programmation avec AlarmManager et affichage de la notification. */
public class Reminders extends BroadcastReceiver {
    static final String PREFS = "declic";
    static final String CHANNEL = "rappels";
    static final String ACTION_FIRE = "fr.alexarzel.declic.RAPPEL";

    /** Enregistre la config venue de la page (JSON) puis reprogramme. */
    static void save(Context ctx, String json) {
        prefs(ctx).edit().putString("reminder", json).apply();
        schedule(ctx);
    }

    static void markTrained(Context ctx, String day) {
        prefs(ctx).edit().putString("lastTrained", day).apply();
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, Reminders.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(ctx);
        am.cancel(pi);
        try {
            JSONObject r = new JSONObject(prefs(ctx).getString("reminder", "{}"));
            if (!r.optBoolean("on", false)) return;
            String[] hm = r.optString("time", "18:30").split(":");
            int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]);
            JSONArray days = r.optJSONArray("days");
            if (days == null || days.length() == 0) return;
            boolean[] on = new boolean[8];
            for (int i = 0; i < days.length(); i++) {
                int d = days.optInt(i);
                if (d >= 1 && d <= 7) on[d] = true;
            }
            Calendar now = Calendar.getInstance();
            for (int k = 0; k < 8; k++) {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.DAY_OF_YEAR, k);
                c.set(Calendar.HOUR_OF_DAY, h);
                c.set(Calendar.MINUTE, m);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                int iso = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1; // lundi = 1
                if (on[iso] && c.after(now)) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Rappels de séance", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Te rappelle l'heure de ta séance");
            nm.createNotificationChannel(ch);
        }
    }

    static void show(Context ctx, String text) {
        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String name = "";
        try {
            name = new JSONObject(prefs(ctx).getString("reminder", "{}")).optString("name", "");
        } catch (Exception ignored) {
        }
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(name.isEmpty() ? "C'est l'heure de ta séance" : name + ", c'est l'heure de ta séance")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            ctx.getSystemService(NotificationManager.class).notify(7, n);
        } catch (SecurityException ignored) {
        }
    }

    private static String randomMessage(Context ctx) {
        try {
            JSONArray msgs = new JSONObject(prefs(ctx).getString("reminder", "{}")).optJSONArray("msgs");
            if (msgs != null && msgs.length() > 0) return msgs.optString(new Random().nextInt(msgs.length()));
        } catch (Exception ignored) {
        }
        return "Ta séance t'attend. On y va ?";
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_FIRE.equals(intent.getAction())) {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().getTime());
            if (!today.equals(prefs(ctx).getString("lastTrained", ""))) show(ctx, randomMessage(ctx));
        }
        if (!ACTION_FIRE.equals(intent.getAction())) Steps.scheduleMidnight(ctx);
        schedule(ctx); // programme le suivant (et reprogramme après un redémarrage ou une mise à jour)
    }
}
