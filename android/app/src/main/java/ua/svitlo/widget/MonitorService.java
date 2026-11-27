package ua.svitlo.widget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Постоянный фоновый сервис: каждые 10 секунд проверяет канал с тревогами,
 * каждые 5 минут — график на сайте ДТЭК, обновляет виджет и шлёт уведомления.
 */
public class MonitorService extends Service {
    static final String ACTION_DTEK_NOW = "ua.svitlo.widget.DTEK_NOW";
    static volatile boolean running;

    private static final long ALERT_EVERY_MS = 10_000;
    private static final long THREAT_EVERY_MS = 15_000;
    /** При первом чтении канала показываем только свежее, без уведомлений. */
    private static final long THREAT_BACKLOG_MS = 30 * 60_000;
    /** Длинные посты — сводки и новости, а не оперативные предупреждения. */
    private static final int THREAT_MAX_LEN = 400;
    private static final long DTEK_EVERY_MS = 5 * 60_000;
    private static final long DTEK_RETRY_MS = 60_000;

    private static final String CH_SERVICE = "service";
    private static final String CH_ALERT = "alert";
    private static final String CH_POWER = "power";
    private static final String CH_THREAT = "threat";
    private static final String CH_LOCAL = "local";
    private static final int ID_SERVICE = 1, ID_ALERT = 2, ID_POWER = 3, ID_REMIND = 4, ID_THREAT = 100;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DtekFetcher dtek = new DtekFetcher();
    private Thread loop;
    private Thread threatLoop;
    private PowerManager.WakeLock wake;
    private long nextDtek;
    private String shownStatus = "";

    static void start(Context c) {
        c.startForegroundService(new Intent(c, MonitorService.class));
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Store.migrate(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, "Работа в фоне",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Воздушная тревога",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_THREAT, "Угрозы: ракеты и БпЛА",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_LOCAL, "Местные каналы: обстановка",
                NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_POWER, "Отключения света",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = serviceNotification(statusLine());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_SERVICE, n);
        }
        if (intent != null && ACTION_DTEK_NOW.equals(intent.getAction())) nextDtek = 0;
        if (loop == null) {
            running = true;
            // Без этого процессор засыпает при выключенном экране и проверки останавливаются.
            wake = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "svitlo:monitor");
            wake.acquire();
            loop = new Thread(this::run, "monitor");
            loop.start();
            threatLoop = new Thread(this::runThreats, "threats");
            threatLoop.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        loop = null;
        if (threatLoop != null) threatLoop.interrupt();
        threatLoop = null;
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ цикл

    private void run() {
        long lastMinute = -1;
        while (running) {
            try {
                boolean changed = checkAlert(this, true);

                long now = System.currentTimeMillis();
                if (now >= nextDtek) {
                    nextDtek = now + DTEK_RETRY_MS;
                    main.post(() -> dtek.fetch(this, this::onDtek));
                }
                long minute = now / 60_000;
                if (minute != lastMinute) {
                    lastMinute = minute;
                    remind();
                    changed = true; // раз в минуту обновляем время и строку о свете
                }
                if (changed) publish();
                Thread.sleep(ALERT_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(ALERT_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- угрозы

    private void runThreats() {
        while (running) {
            try {
                boolean any = false;
                for (String ch : Store.threatChannelList(this)) any |= checkThreats(ch);
                if (any) publish();
                Thread.sleep(THREAT_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(THREAT_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Читает новые сообщения канала; true, если появились сообщения про наши места. */
    private boolean checkThreats(String entry) {
        boolean local = entry.endsWith("*");
        String channel = entry.replace("*", "");
        SharedPreferences p = Store.p(this);
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
        String[] city = Threats.stems(Store.cityWords(this));
        String[] area = Threats.stems(Store.areaWords(this));
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
        Store.addThreats(this, found);
        if (lastId != 0) for (Threats.Item it : found) notifyThreat(it);
        return true;
    }

    private void notifyThreat(Threats.Item it) {
        Notification n = new Notification.Builder(this, it.local ? CH_LOCAL : CH_THREAT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(it.type + (it.city ? " · Кривой Рог" : " · рядом") + " · " + it.source)
                .setContentText(it.text)
                .setStyle(new Notification.BigTextStyle().bigText(it.text + "\n— " + it.source))
                .setWhen(it.timeMs > 0 ? it.timeMs : System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(open(this))
                .setAutoCancel(true)
                .build();
        // одно уведомление на канал: новое сообщение заменяет предыдущее из того же канала
        getSystemService(NotificationManager.class)
                .notify(ID_THREAT + Math.floorMod(it.source.hashCode(), 1000), n);
    }

    private void publish() {
        StatusWidget.render(this);
        String s = statusLine();
        if (!s.equals(shownStatus)) {
            shownStatus = s;
            getSystemService(NotificationManager.class).notify(ID_SERVICE, serviceNotification(s));
        }
    }

    private String statusLine() {
        int a = Store.alert(this);
        return (a == 1 ? "🚨 ТРЕВОГА" : a == 0 ? "✅ Тревоги нет" : "Тревога: нет данных")
                + " · " + Store.powerLine(this).replaceFirst("^\\S+ ", "");
    }

    // --------------------------------------------------------------- тревога

    /**
     * Проверяет канал. Возвращает true, если состояние изменилось.
     * Используется и сервисом, и виджетом (когда сервис не запущен).
     */
    static synchronized boolean checkAlert(Context c, boolean notify) {
        SharedPreferences p = Store.p(c);
        long lastId = p.getLong("lastPostId", 0);
        int before = p.getInt("alert", -1);
        try {
            // Пока состояние неизвестно, читаем всю страницу; потом — только новые сообщения.
            long after = before == -1 ? 0 : lastId;
            Data.Channel ch = Data.parseChannel(Data.get(Data.channelUrl(Store.channel(c), after)));
            SharedPreferences.Editor e = p.edit().putLong("alertChecked", System.currentTimeMillis());
            if (ch.lastId > lastId) e.putLong("lastPostId", ch.lastId);
            boolean changed = false;
            if (ch.last != null) {
                int state = ch.last.alert ? 1 : 0;
                changed = state != before;
                e.putInt("alert", state);
                if (changed || p.getLong("alertSince", 0) == 0) {
                    e.putLong("alertSince", ch.last.timeMs);
                }
                e.apply();
                // При первом запуске не шумим — только показываем состояние.
                if (changed && notify && before != -1) notifyAlert(c, ch.last);
            } else {
                e.apply();
            }
            return changed;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void notifyAlert(Context c, Data.Post post) {
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

    // ------------------------------------------------------------------ свет

    private void onDtek(JSONObject fact, String error) {
        SharedPreferences p = Store.p(this);
        if (fact == null) {
            p.edit().putString("dtekError", error).apply();
            return;
        }
        nextDtek = System.currentTimeMillis() + DTEK_EVERY_MS;
        String group = Store.group(this);
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
            notifyPower(ID_POWER, oldDays == null ? "💡 График отключений" : "🔄 График отключений обновился",
                    text + "\nГруппа " + group + " · ДТЭК, обновлено " + fact.optString("update"));
        }
        new Thread(this::publish).start();
    }

    /** Напоминания перед отключением и перед включением. */
    private void remind() {
        JSONObject fact = Store.fact(this);
        if (fact == null) return;
        int lead = Store.remindMinutes(this);
        if (lead <= 0) return;
        try {
            List<ZonedDateTime[]> spans = Data.spans(fact, Store.group(this));
            if (spans == null) return;
            SharedPreferences p = Store.p(this);
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
                        notifyPower(ID_REMIND, "⏰ Через " + Math.max(1, left) + " мин отключат свет",
                                sp[0].format(Data.HM) + "–" + sp[1].format(Data.HM)
                                        + " · группа " + Store.group(this));
                    } else {
                        notifyPower(ID_REMIND, "💡 Через " + Math.max(1, left) + " мин должны включить свет",
                                "в " + sp[1].format(Data.HM) + " · группа " + Store.group(this));
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

    private void notifyPower(int id, String title, String text) {
        Notification n = new Notification.Builder(this, CH_POWER)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(this))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(id, n);
    }

    // ------------------------------------------------------------ служебное

    private Notification serviceNotification(String text) {
        return new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(text)
                .setContentText("Слежу за тревогой и графиком света")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(this))
                .build();
    }

    private static PendingIntent open(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
