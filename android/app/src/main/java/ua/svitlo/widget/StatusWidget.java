package ua.svitlo.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;


/** Виджет: тревога + ближайшее отключение света. Нажатие открывает приложение. */
public class StatusWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "ua.svitlo.widget.REFRESH";
    private static final long EVERY_MS = 60 * 1000;
    /** Если проверка старше этого — данные считаются устаревшими. */
    private static final long STALE_MS = 90 * 1000;
    /** Сколько сообщение об угрозе держится на виджете. */
    static final long THREAT_SHOW_MS = 30 * 60 * 1000;

    private static PendingIntent refreshIntent(Context c) {
        Intent i = new Intent(c, StatusWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Запасное обновление раз в минуту — на случай, если система остановила сервис. */
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
        refresh(c);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refresh(c);
        } else {
            super.onReceive(c, intent);
        }
    }

    private void refresh(Context c) {
        final PendingResult pending = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                // Сервис сам всё проверяет; без него проверяем канал прямо здесь.
                if (!MonitorService.running) MonitorService.checkAlert(app, false);
                render(app);
            } finally {
                pending.finish();
            }
        }).start();
    }

    /** Перерисовывает все виджеты по последнему известному состоянию. */
    static void render(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, StatusWidget.class));
        if (ids.length == 0) return;

        int alert = Store.alert(c);
        long checked = Store.alertChecked(c);
        boolean stale = System.currentTimeMillis() - checked > STALE_MS;

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        if (alert == -1 || stale) {
            // Не показываем «тревоги нет», если не уверены, что это так сейчас.
            v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_unknown);
            v.setTextViewText(R.id.alert_title, alert == 1 ? "🚨 ТРЕВОГА (?)" : "Нет связи");
            v.setTextViewText(R.id.alert_sub, checked == 0 ? "данные ещё не получены"
                    : "последняя проверка в " + Data.hm(checked) + " · нажмите");
        } else if (alert == 1) {
            v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_alert);
            v.setTextViewText(R.id.alert_title, "🚨 ТРЕВОГА");
            v.setTextViewText(R.id.alert_sub, "Кривой Рог · с " + Data.hm(Store.alertSince(c)));
        } else {
            v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_clear);
            v.setTextViewText(R.id.alert_title, "✅ Тревоги нет");
            v.setTextViewText(R.id.alert_sub, "Кривой Рог · отбой в " + Data.hm(Store.alertSince(c)));
        }
        // Прогноз для города и последнее сообщение, на котором он основан
        Forecast.Result fc = Store.forecast(c);
        if (fc.level == Forecast.NONE) {
            v.setViewVisibility(R.id.threats, View.GONE);
        } else {
            StringBuilder sb = new StringBuilder(fc.title);
            if (!fc.eta.isEmpty()) sb.append(" · ").append(fc.eta);
            if (!fc.detail.isEmpty() && fc.level != Forecast.GONE) sb.append('\n').append(fc.detail);
            if (!fc.basis.isEmpty()) sb.append('\n').append(fc.basis);
            v.setTextViewText(R.id.threats, sb.toString());
            v.setViewVisibility(R.id.threats, View.VISIBLE);
        }
        String today = Store.todayLine(c);
        v.setTextViewText(R.id.power, Store.powerLine(c) + (today.isEmpty() ? "" : "\n" + today));
        long fetched = Store.factFetched(c);
        v.setTextViewText(R.id.updated, "тревога: " + (checked == 0 ? "—" : Data.hm(checked))
                + " · график ДТЭК: " + (fetched == 0 ? "—" : Data.hm(fetched)));
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 1,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(ids, v);
    }
}
