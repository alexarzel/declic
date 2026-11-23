package fr.alexarzel.declic;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;

/**
 * Podomètre : lit le compteur de pas du téléphone (TYPE_STEP_COUNTER, cumulé depuis
 * le démarrage) et le transforme en pas par jour. Un réveil juste après minuit
 * enregistre le total de la veille, même si l'appli est fermée.
 */
public class Steps extends BroadcastReceiver {
    static final String ACTION_MIDNIGHT = "fr.alexarzel.declic.PAS_MINUIT";

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("declic_steps", Context.MODE_PRIVATE);
    }

    static Sensor sensor(Context ctx) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        return sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    }

    static boolean available(Context ctx) {
        return sensor(ctx) != null;
    }

    static boolean permitted(Context ctx) {
        return Build.VERSION.SDK_INT < 29
                || ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean("enabled", false);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().getTime());
    }

    /** Nouvelle valeur du compteur cumulé. */
    static synchronized void onTotal(Context ctx, float totalF) {
        long total = (long) totalF;
        SharedPreferences p = prefs(ctx);
        try {
            JSONObject days = new JSONObject(p.getString("days", "{}"));
            String day = today();
            String baseDay = p.getString("baseDay", "");
            long base = p.getLong("base", -1);
            long last = p.getLong("last", -1);

            if (base < 0) {
                // Première mesure : on compte à partir de maintenant.
                base = total;
                baseDay = day;
            } else if (total < last) {
                // Le téléphone a redémarré : le compteur repart de zéro.
                long already = days.optLong(baseDay, 0);
                base = total - already;
            }
            if (!day.equals(baseDay)) {
                // Changement de jour : les pas depuis la base vont au jour précédent.
                days.put(baseDay, Math.max(days.optLong(baseDay, 0), Math.max(0, total - base)));
                base = total;
                baseDay = day;
            }
            days.put(day, Math.max(0, total - base));

            // On garde 90 jours.
            Calendar limit = Calendar.getInstance();
            limit.add(Calendar.DAY_OF_YEAR, -90);
            String lim = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(limit.getTime());
            for (Iterator<String> it = days.keys(); it.hasNext(); ) {
                if (it.next().compareTo(lim) < 0) it.remove();
            }
            p.edit().putString("days", days.toString()).putString("baseDay", baseDay)
                    .putLong("base", base).putLong("last", total).apply();
        } catch (Exception ignored) {
        }
    }

    static String json(Context ctx) {
        return prefs(ctx).getString("days", "{}");
    }

    static void scheduleMidnight(Context ctx) {
        if (!enabled(ctx)) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, Steps.class).setAction(ACTION_MIDNIGHT);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 1);
        c.set(Calendar.SECOND, 0);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
    }

    /** Lit une seule valeur du capteur puis s'arrête (pour le réveil de minuit). */
    static void readOnce(Context ctx, Runnable done) {
        Sensor s = sensor(ctx);
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (s == null || sm == null || !permitted(ctx)) {
            done.run();
            return;
        }
        Handler h = new Handler(Looper.getMainLooper());
        SensorEventListener l = new SensorEventListener() {
            boolean finished = false;

            @Override
            public void onSensorChanged(SensorEvent e) {
                if (finished) return;
                finished = true;
                onTotal(ctx, e.values[0]);
                sm.unregisterListener(this);
                done.run();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL);
        h.postDelayed(() -> {
            sm.unregisterListener(l);
            done.run();
        }, 8000);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final boolean[] once = {false};
        readOnce(ctx.getApplicationContext(), () -> {
            if (once[0]) return;
            once[0] = true;
            scheduleMidnight(ctx);
            Widget.refresh(ctx);
            pr.finish();
        });
    }
}
