package ua.svitlo.widget;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Режим «через сервер»: сервер следит за каналами и присылает новые сообщения
 * push-уведомлениями Firebase, телефон только обрабатывает их и показывает.
 */
final class Push {
    /** Адрес сервера: GET отдаёт время последней проверки и номера последних сообщений. */
    static final String SERVER = "https://uukjizyksppxeakafsua.supabase.co/functions/v1/tryvoga-tick";
    static final String TOPIC = "posts";
    private static final long SYNC_EVERY_MS = 45_000;
    private static final int JOB_DTEK = 11;

    /** Собрано ли приложение с настройками Firebase (файл google-services.json). */
    static boolean configured() {
        return !BuildConfig.FCM_APP_ID.isEmpty();
    }

    /** Работает ли приложение через сервер (иначе — постоянная служба на телефоне). */
    static boolean serverMode(Context c) {
        return configured() && Store.p(c).getBoolean("serverMode", true);
    }

    /** Включает нужный режим. Вызывается при запуске приложения, после перезагрузки и обновления. */
    static void apply(Context c) {
        Work.channels(c);
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (serverMode(c)) {
            c.stopService(new Intent(c, MonitorService.class));
            subscribe(c);
            // график ДТЭК телефон читает сам, раз в полчаса
            js.schedule(new JobInfo.Builder(JOB_DTEK, new ComponentName(c, DtekJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(30 * 60_000L)
                    .setPersisted(true)
                    .build());
            Work.scheduleReminder(c);
            new Thread(() -> sync(c.getApplicationContext(), true)).start();
        } else {
            js.cancel(JOB_DTEK);
            if (configured()) {
                try {
                    FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC);
                } catch (Exception ignored) {
                }
            }
            try {
                MonitorService.start(c);
            } catch (Exception ignored) {
                // из фона служба не стартует — запустится при открытии приложения
            }
        }
    }

    private static void subscribe(Context c) {
        try {
            final SharedPreferences p = Store.p(c);
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC).addOnCompleteListener(t ->
                    p.edit().putString("pushError", t.isSuccessful() ? ""
                            : String.valueOf(t.getException() == null ? "ошибка"
                            : t.getException().getMessage()))
                            .putBoolean("pushSubscribed", t.isSuccessful()).apply());
        } catch (Exception e) {
            Store.p(c).edit().putString("pushError", String.valueOf(e.getMessage())).apply();
        }
    }

    /**
     * Сверка с сервером: жив ли он и не пропущено ли что-то. Пропущенное телефон
     * дочитывает из каналов сам. Вызывать из фонового потока.
     */
    static synchronized void sync(Context c, boolean force) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("syncAt", 0) < SYNC_EVERY_MS) return;
        p.edit().putLong("syncAt", now).apply();
        try {
            JSONObject j = new JSONObject(Data.get(SERVER));
            long tick = j.isNull("tick") ? 0
                    : java.time.Instant.parse(j.getString("tick")).toEpochMilli();
            boolean alive = now - tick < 150_000;
            SharedPreferences.Editor e = p.edit().putLong("serverTick", tick)
                    .putBoolean("serverAlive", alive)
                    .putBoolean("serverPush", j.optBoolean("push"))
                    .putString("serverError", alive ? "" : "сервер не проверял каналы с " + Data.hm(tick));
            if (alive) e.putLong("contact", now).putLong("alertChecked", now);
            e.apply();

            JSONObject ch = j.getJSONObject("channels");
            boolean changed = false;
            // канал сирены
            long srv = find(ch, Store.channel(c));
            if (srv < 0 || !alive || p.getInt("alert", -1) == -1 || srv > p.getLong("lastPostId", 0)) {
                changed |= Work.checkAlert(c, true);
            }
            // каналы об угрозах
            for (String entry : Store.threatChannelList(c)) {
                String name = entry.replace("*", "");
                long s = find(ch, name);
                long mine = p.getLong("last_" + name, 0);
                // канала нет на сервере или сервер молчит — читаем сами, но не чаще раза в 2 минуты
                boolean self = (s < 0 || !alive) && now - p.getLong("ok_" + name, 0) > 120_000;
                if (mine == 0 || s > mine || self) changed |= Work.pollThreats(c, entry);
                else if (alive) p.edit().putLong("ok_" + name, now).apply();
            }
            Work.afterChange(c);
        } catch (Exception ex) {
            p.edit().putBoolean("serverAlive", false)
                    .putString("serverError", "нет связи с сервером").apply();
        }
        Updater.maybeCheck(c, false);
    }

    private static long find(JSONObject channels, String name) {
        for (Iterator<String> it = channels.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.equalsIgnoreCase(name)) return channels.optLong(k, -1);
        }
        return -1;
    }

    /** Данные считаются устаревшими, если давно не было ни push, ни успешной сверки. */
    static boolean stale(Context c, long now) {
        long contact = Store.p(c).getLong("contact", 0);
        if (!serverMode(c)) return now - Store.alertChecked(c) > 90_000;
        boolean alive = Store.p(c).getBoolean("serverAlive", true);
        return contact == 0 || now - contact > 25 * 60_000 || (!alive && now - contact > 3 * 60_000);
    }

    private Push() {
    }
}
