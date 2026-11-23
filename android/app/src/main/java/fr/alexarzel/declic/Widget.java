package fr.alexarzel.declic;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Widget d'écran d'accueil : série, pas du jour, eau et prochaine séance. */
public class Widget extends AppWidgetProvider {

    static void save(Context ctx, String json) {
        Reminders.prefs(ctx).edit().putString("widget", json).apply();
        refresh(ctx);
    }

    static void refresh(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, Widget.class));
        if (ids != null && ids.length > 0) update(ctx, m, ids);
    }

    private static void update(Context ctx, AppWidgetManager m, int[] ids) {
        JSONObject d;
        try {
            d = new JSONObject(Reminders.prefs(ctx).getString("widget", "{}"));
        } catch (Exception e) {
            d = new JSONObject();
        }
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        int streak = d.optInt("streak", 0);
        v.setTextViewText(R.id.w_streak, streak + (streak > 1 ? " jours" : " jour"));
        long steps = -1;
        if (Steps.enabled(ctx)) {
            try {
                steps = new JSONObject(Steps.json(ctx)).optLong(Steps.today(), 0);
            } catch (Exception ignored) {
            }
        }
        v.setTextViewText(R.id.w_steps, steps < 0 ? "—" : String.format(java.util.Locale.FRANCE, "%,d", steps).replace(' ', ' '));
        v.setTextViewText(R.id.w_water, Water.count(ctx) + " / " + d.optInt("waterGoal", 8));
        String next = d.optString("next", "");
        v.setTextViewText(R.id.w_next, next.isEmpty() ? "Ouvre Déclic pour commencer" : "Prochaine : " + next);
        v.setTextViewText(R.id.w_week, d.optString("week", ""));
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 6, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(ids, v);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        update(ctx, m, ids);
    }
}
