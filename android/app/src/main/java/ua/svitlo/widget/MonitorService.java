package ua.svitlo.widget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Режим «без сервера»: постоянная фоновая служба, телефон сам каждые 10–15 секунд
 * опрашивает каналы. Надёжно, но расходует батарею. В режиме «через сервер»
 * эта служба не запускается — данные приходят push-сообщениями (см. PushService).
 */
public class MonitorService extends Service {
    static final String ACTION_DTEK_NOW = "ua.svitlo.widget.DTEK_NOW";
    static volatile boolean running;

    private static final long ALERT_EVERY_MS = 10_000;
    private static final long THREAT_EVERY_MS = 15_000;
    private static final long DTEK_EVERY_MS = 5 * 60_000;
    private static final long DTEK_RETRY_MS = 60_000;

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
        Work.channels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = serviceNotification(statusLine());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Work.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Work.ID_SERVICE, n);
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

    private void run() {
        long lastMinute = -1;
        while (running) {
            try {
                boolean changed = Work.checkAlert(this, true);

                long now = System.currentTimeMillis();
                if (now >= nextDtek) {
                    nextDtek = now + DTEK_RETRY_MS;
                    main.post(() -> dtek.fetch(this, (fact, error) -> {
                        if (Work.onDtek(this, fact, error)) {
                            nextDtek = System.currentTimeMillis() + DTEK_EVERY_MS;
                        }
                        new Thread(this::publish).start();
                    }));
                }
                long minute = now / 60_000;
                if (minute != lastMinute) {
                    lastMinute = minute;
                    Work.remind(this);
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

    private void runThreats() {
        while (running) {
            try {
                boolean any = false;
                for (String ch : Store.threatChannelList(this)) any |= Work.pollThreats(this, ch);
                Work.notifyForecast(this); // и по новым сообщениям, и когда старые теряют силу
                Updater.maybeCheck(this, false);
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

    private void publish() {
        StatusWidget.render(this);
        String s = statusLine();
        if (!s.equals(shownStatus)) {
            shownStatus = s;
            getSystemService(NotificationManager.class).notify(Work.ID_SERVICE, serviceNotification(s));
        }
    }

    private String statusLine() {
        int a = Store.alert(this);
        return (a == 1 ? "🚨 ТРЕВОГА" : a == 0 ? "✅ Тревоги нет" : "Тревога: нет данных")
                + " · " + Store.powerLine(this).replaceFirst("^\\S+ ", "");
    }

    private Notification serviceNotification(String text) {
        return new Notification.Builder(this, Work.CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(text)
                .setContentText("Слежу за тревогой и графиком света")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(Work.open(this))
                .build();
    }
}
