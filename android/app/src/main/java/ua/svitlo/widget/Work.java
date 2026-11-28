package ua.svitlo.widget;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Вся обработка данных: тревога, сообщения об угрозах, прогноз, свет, уведомления.
 * Одинаково работает и когда данные приходят с сервера (push), и когда телефон
 * сам опрашивает каналы.
 */
final class Work {
    /** При первом чтении канала берём последние часы — без уведомлений, для истории. */
    private static final long THREAT_BACKLOG_MS = 3 * 3600_000;
    /** Длинные посты — сводки и новости, а не оперативные предупреждения. */
    private static final int THREAT_MAX_LEN = 400;

    static final String CH_SERVICE = "service";
    private static final String CH_ALERT = "alert";
    private static final String CH_POWER = "power";
    private static final String CH_THREAT = "threat";
    static final int ID_SERVICE = 1;
    private static final int ID_ALERT = 2, ID_POWER = 3, ID_REMIND = 4, ID_THREAT = 100;

    /** Категории уведомлений; вызывать перед любым уведомлением (повторно — безвредно). */
    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, "Работа в фоне",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Воздушная тревога",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_THREAT,
                "Опасно в моём районе, ракеты на город", NotificationManager.IMPORTANCE_HIGH));
        nm.deleteNotificationChannel("local"); // больше не используется
        nm.createNotificationChannel(new NotificationChannel(CH_POWER, "Отключения света",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    static PendingIntent open(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** После любых новых данных: уведомление по прогнозу (если нужно) и перерисовка виджета. */
    static void afterChange(Context c) {
        notifyForecast(c);
        StatusWidget.render(c);
    }

    // --------------------------------------------------------------- тревога

    /**
     * Читает страницу канала сирены. Возвращает true, если состояние изменилось.
     */
    static synchronized boolean checkAlert(Context c, boolean notify) {
        SharedPreferences p = Store.p(c);
        long lastId = p.getLong("lastPostId", 0);
        int before = p.getInt("alert", -1);
        try {
            // Пока состояние неизвестно, читаем всю страницу; потом — только новые сообщения.
            long after = before == -1 ? 0 : lastId;
            Data.Channel ch = Data.parseChannel(Data.get(Data.channelUrl(Store.channel(c), after)));
            long now = System.currentTimeMillis();
            SharedPreferences.Editor e = p.edit().putLong("alertChecked", now).putLong("contact", now);
            if (ch.lastId > lastId) e.putLong("lastPostId", ch.lastId);
            boolean changed = false;
            // границы последней тревоги — для истории «что летело за эту тревогу»
            long start = ch.lastAlertMs > 0 ? ch.lastAlertMs : p.getLong("alertStart", 0);
            if (ch.lastAlertMs > 0) e.putLong("alertStart", start);
            if (ch.last != null) {
                if (ch.last.alert) e.putLong("alertEnd", 0);
                else if (ch.lastClearMs >= start) e.putLong("alertEnd", ch.lastClearMs);
                int state = ch.last.alert ? 1 : 0;
                changed = state != before;
                e.putInt("alert", state);
                if (changed || p.getLong("alertSince", 0) == 0) {
                    e.putLong("alertSince", ch.last.timeMs);
                }
                e.apply();
                // При первом запуске не шумим — только показываем состояние.
                if (changed && notify && before != -1 && Store.sirenNotify(c)) notifyAlert(c, ch.last);
            } else {
                e.apply();
            }
            return changed;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Одно сообщение канала сирены, пришедшее с сервера. */
    static synchronized boolean sirenPost(Context c, Data.Raw r) {
        SharedPreferences p = Store.p(c);
        if (r.id <= p.getLong("lastPostId", 0)) return false; // уже учтено
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = p.edit().putLong("lastPostId", r.id)
                .putLong("alertChecked", now).putLong("contact", now);
        Boolean kind = Data.classify(r.text);
        if (kind == null) {
            e.apply();
            return false;
        }
        int before = p.getInt("alert", -1);
        int state = kind ? 1 : 0;
        boolean changed = state != before;
        e.putInt("alert", state);
        if (kind) e.putLong("alertStart", r.timeMs).putLong("alertEnd", 0);
        else if (r.timeMs >= p.getLong("alertStart", 0)) e.putLong("alertEnd", r.timeMs);
        if (changed || p.getLong("alertSince", 0) == 0) e.putLong("alertSince", r.timeMs);
        e.apply();
        if (changed && before != -1 && Store.sirenNotify(c)) {
            Data.Post post = new Data.Post();
            post.id = r.id;
            post.alert = kind;
            post.timeMs = r.timeMs;
            post.text = r.text.replaceAll("\\s+", " ").trim();
            notifyAlert(c, post);
        }
        return changed;
    }

    private static void notifyAlert(Context c, Data.Post post) {
        channels(c);
        Notification n = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(post.alert ? "🚨 ТРЕВОГА" : "✅ Отбой тревоги")
                .setContentText(post.text)
                .setStyle(new Notification.BigTextStyle().bigText(post.text))
                .setWhen(post.timeMs > 0 ? post.timeMs : System.currentTimeMillis())
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(ID_ALERT, n);
    }

    // ---------------------------------------------------------------- угрозы

    /** Сам читает новые сообщения канала; true, если появились сообщения про наши места. */
    static boolean pollThreats(Context c, String entry) {
        boolean local = entry.endsWith("*");
        String channel = entry.replace("*", "");
        SharedPreferences p = Store.p(c);
        String key = "last_" + channel;
        long lastId = p.getLong(key, 0);
        List<Data.Raw> posts;
        try {
            posts = Data.posts(Data.get(Data.channelUrl(channel, lastId)));
        } catch (Exception e) {
            p.edit().putString("err_" + channel, "нет связи").apply();
            return false;
        }
        SharedPreferences.Editor ed = p.edit().putLong("ok_" + channel, System.currentTimeMillis())
                .remove("err_" + channel);
        if (posts.isEmpty()) {
            ed.apply();
            return false;
        }
        String[] city = Threats.stems(Store.cityWords(c));
        String[] area = Threats.stems(Store.areaWords(c));
        long now = System.currentTimeMillis();
        List<Threats.Item> found = new ArrayList<>();
        long max = lastId;
        for (Data.Raw r : posts) {
            max = Math.max(max, r.id);
            if (r.id <= lastId) continue;
            if (lastId == 0 && now - r.timeMs > THREAT_BACKLOG_MS) continue;
            Threats.Item it = Threats.extract(channel, local, r, city, area, THREAT_MAX_LEN);
            if (it != null) found.add(it);
        }
        ed.putLong(key, max).apply();
        if (found.isEmpty()) return false;
        Store.addThreats(c, found);
        return true;
    }

    /** Одно сообщение канала об угрозах, пришедшее с сервера. */
    static boolean threatPost(Context c, String channel, Data.Raw r) {
        String entry = null;
        for (String e : Store.threatChannelList(c)) {
            if (e.replace("*", "").equalsIgnoreCase(channel)) entry = e;
        }
        if (entry == null) return false; // канал выключен в настройках
        String name = entry.replace("*", "");
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = p.edit().putLong("ok_" + name, now).putLong("contact", now);
        if (r.id > p.getLong("last_" + name, 0)) ed.putLong("last_" + name, r.id);
        ed.apply();
        Threats.Item it = Threats.extract(name, entry.endsWith("*"), r,
                Threats.stems(Store.cityWords(c)), Threats.stems(Store.areaWords(c)), THREAT_MAX_LEN);
        if (it == null) return false;
        List<Threats.Item> one = new ArrayList<>();
        one.add(it);
        Store.addThreats(c, one); // повторно пришедшее сообщение не задвоится
        return true;
    }

    /**
     * Уведомление приходит только в двух случаях: цель над вашим районом или
     * летит на него; ракета/баллистика/КАБ на Кривой Рог. Всё остальное —
     * только на карточке и виджете.
     */
    static synchronized void notifyForecast(Context c) {
        Forecast.Result r = Store.forecast(c);
        SharedPreferences p = Store.p(c);
        int lastKind = p.getInt("fcKind", Forecast.N_NONE);
        String lastKey = p.getString("fcKey", "");
        long lastAt = p.getLong("fcAt", 0);
        long now = System.currentTimeMillis();
        if (r.notify == Forecast.N_NONE) {
            if (lastKind != Forecast.N_NONE) p.edit().putInt("fcKind", Forecast.N_NONE).apply();
            return;
        }
        // новое уведомление — при появлении опасности, при её усилении
        // и на новое сообщение о ракете (но не чаще раза в 2 минуты)
        boolean fresh = lastKind == Forecast.N_NONE || r.notify > lastKind
                || (!r.key.equals(lastKey) && r.notify == Forecast.N_MISSILE && now - lastAt > 120_000)
                || (!r.key.equals(lastKey) && now - lastAt > 15 * 60_000);
        p.edit().putInt("fcKind", r.notify).apply();
        if (!fresh) return;
        p.edit().putString("fcKey", r.key).putLong("fcAt", now).apply();
        String body = (r.eta.isEmpty() ? "" : r.eta + "\n") + r.detail
                + (r.basis.isEmpty() ? "" : "\n" + r.basis);
        channels(c);
        Notification n = new Notification.Builder(c, CH_THREAT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(r.title)
                .setContentText(r.eta.isEmpty() ? r.detail : r.eta + " · " + r.detail)
                .setStyle(new Notification.BigTextStyle().bigText(body.trim()))
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(ID_THREAT, n);
    }

    // ------------------------------------------------------------------ свет

    /** Результат загрузки графика с сайта ДТЭК. true — график получен. */
    static boolean onDtek(Context c, JSONObject fact, String error) {
        SharedPreferences p = Store.p(c);
        if (fact == null) {
            p.edit().putString("dtekError", error).apply();
            return false;
        }
        String group = Store.group(c);
        ZonedDateTime now = ZonedDateTime.now(Data.KYIV);
        String oldDays = group.equals(p.getString("daysGroup", "")) ? p.getString("days", null) : null;
        List<ZonedDateTime[]> spans = null;
        JSONObject days = null;
        boolean ok = true;
        try {
            spans = Data.spans(fact, group);
            days = Data.dayKeys(fact, group, now);
        } catch (Exception e) {
            ok = false;
        }
        SharedPreferences.Editor ed = p.edit().putString("fact", fact.toString())
                .putLong("factFetched", System.currentTimeMillis())
                .putString("dtekError", "");
        if (ok) ed.putString("days", days == null ? "null" : days.toString()).putString("daysGroup", group);
        ed.apply();

        if (ok && Data.changed(oldDays, days)) {
            String text = spans == null
                    ? "В графике нет группы " + group + ". Есть: " + Data.groups(fact)
                    : Data.summary(spans, now);
            notifyPower(c, ID_POWER, oldDays == null ? "💡 График отключений" : "🔄 График отключений обновился",
                    text + "\nГруппа " + group + " · ДТЭК, обновлено " + fact.optString("update"));
        }
        scheduleReminder(c);
        return true;
    }

    /** Напоминания перед отключением и перед включением. */
    static synchronized void remind(Context c) {
        JSONObject fact = Store.fact(c);
        if (fact == null) return;
        int lead = Store.remindMinutes(c);
        if (lead <= 0) return;
        try {
            List<ZonedDateTime[]> spans = Data.spans(fact, Store.group(c));
            if (spans == null) return;
            SharedPreferences p = Store.p(c);
            Set<String> sent = new HashSet<>(p.getStringSet("reminded", new HashSet<>()));
            ZonedDateTime now = ZonedDateTime.now(Data.KYIV);
            boolean added = false;
            for (ZonedDateTime[] sp : spans) {
                for (int k = 0; k < 2; k++) {
                    long left = Duration.between(now, sp[k]).toMinutes();
                    String id = (k == 0 ? "off|" : "on|") + sp[k].toEpochSecond();
                    if (left < 0 || left > lead || Duration.between(now, sp[k]).isNegative()
                            || sent.contains(id)) continue;
                    sent.add(id);
                    added = true;
                    if (k == 0) {
                        notifyPower(c, ID_REMIND, "⏰ Через " + Math.max(1, left) + " мин отключат свет",
                                sp[0].format(Data.HM) + "–" + sp[1].format(Data.HM)
                                        + " · группа " + Store.group(c));
                    } else {
                        notifyPower(c, ID_REMIND, "💡 Через " + Math.max(1, left) + " мин должны включить свет",
                                "в " + sp[1].format(Data.HM) + " · группа " + Store.group(c));
                    }
                }
            }
            if (added) {
                long cutoff = now.minusDays(2).toEpochSecond();
                sent.removeIf(s -> Long.parseLong(s.substring(s.indexOf('|') + 1)) < cutoff);
                p.edit().putStringSet("reminded", sent).apply();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Ставит будильник на ближайшее напоминание о свете — чтобы оно пришло и без
     * постоянно работающей службы.
     */
    static void scheduleReminder(Context c) {
        try {
            JSONObject fact = Store.fact(c);
            int lead = Store.remindMinutes(c);
            if (fact == null || lead <= 0) return;
            List<ZonedDateTime[]> spans = Data.spans(fact, Store.group(c));
            if (spans == null) return;
            Set<String> sent = Store.p(c).getStringSet("reminded", new HashSet<>());
            long now = System.currentTimeMillis();
            long best = Long.MAX_VALUE;
            for (ZonedDateTime[] sp : spans) {
                for (int k = 0; k < 2; k++) {
                    long event = sp[k].toInstant().toEpochMilli();
                    if (event <= now) continue;
                    if (sent.contains((k == 0 ? "off|" : "on|") + sp[k].toEpochSecond())) continue;
                    best = Math.min(best, Math.max(now + 5_000, event - lead * 60_000L + 10_000));
                }
            }
            if (best == Long.MAX_VALUE) return;
            PendingIntent pi = PendingIntent.getBroadcast(c, 7,
                    new Intent(c, BootReceiver.class).setAction(BootReceiver.ACTION_REMIND),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            c.getSystemService(AlarmManager.class)
                    .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
        } catch (Exception ignored) {
        }
    }

    private static void notifyPower(Context c, int id, String title, String text) {
        channels(c);
        Notification n = new Notification.Builder(c, CH_POWER)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }

    private Work() {
    }
}
