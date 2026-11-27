package ua.svitlo.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.RemoteViews;

/** Виджет: тревога в области + ближайшее отключение света. Нажатие — обновить. */
public class StatusWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "ua.svitlo.widget.REFRESH";
    static final String PREFS = "settings";
    private static final long EVERY_MS = 2 * 60 * 1000;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent refreshIntent(Context c) {
        Intent i = new Intent(c, StatusWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Обновлять раз в пару минут, пока телефон не спит (будить его не нужно). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + EVERY_MS, EVERY_MS, refreshIntent(c));
    }

    @Override
    public void onEnabled(Context c) {
        schedule(c);
    }

    @Override
    public void onDisabled(Context c) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(refreshIntent(c));
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        schedule(c);
        refresh(c, true);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refresh(c, true);
        } else {
            super.onReceive(c, intent);
        }
    }

    /** Загружает данные в фоне и перерисовывает все виджеты. */
    void refresh(final Context c, boolean async) {
        final PendingResult pending = async ? goAsync() : null;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                update(app);
            } finally {
                if (pending != null) pending.finish();
            }
        }).start();
    }

    static void update(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, StatusWidget.class));
        if (ids.length == 0) return;

        SharedPreferences p = prefs(c);
        String region = p.getString("region", Data.REGIONS[0]);
        String group = p.getString("group", "4.2");

        Data.Alert a = Data.alert(region);
        String power = Data.power(group);

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        if (a.active == null) {
            // Сеть недоступна: оставляем прошлый статус, но честно помечаем.
            v.setTextViewText(R.id.updated, "нет связи · " + Data.nowHm() + " · нажмите, чтобы обновить");
            v.setOnClickPendingIntent(R.id.root, refreshIntent(c));
            m.partiallyUpdateAppWidget(ids, v);
            return;
        }
        if (a.active) {
            v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_alert);
            v.setTextViewText(R.id.alert_title, "🚨 ТРЕВОГА");
            v.setTextViewText(R.id.alert_sub, region + (a.since.isEmpty() ? "" : " · с " + a.since));
        } else {
            v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_clear);
            v.setTextViewText(R.id.alert_title, "✅ Тревоги нет");
            v.setTextViewText(R.id.alert_sub, region);
        }
        v.setTextViewText(R.id.power, power);
        v.setTextViewText(R.id.updated, "обновлено " + Data.nowHm());
        v.setOnClickPendingIntent(R.id.root, refreshIntent(c));
        m.updateAppWidget(ids, v);
    }
}
