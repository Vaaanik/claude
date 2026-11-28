package ua.svitlo.widget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Автообновление: приложение само проверяет свежую сборку в релизах репозитория,
 * скачивает APK и ставит его поверх себя. Подпись проверяет сама система —
 * чужой APK поверх не установится.
 */
public class Updater extends BroadcastReceiver {
    static final String BASE = "https://github.com/Vaaanik/claude/releases/download/widget/";
    static final String ACTION_STATUS = "ua.svitlo.widget.UPDATE_STATUS";
    private static final long CHECK_EVERY_MS = 15 * 60_000;
    private static final long RETRY_SAME_MS = 3 * 3600_000L;
    private static final String CH = "update2";

    /** Окно подтверждения от системы, которое ждёт пользователя (если тихо поставить не удалось). */
    static volatile Intent pendingConfirm;

    private static void state(Context c, String s) {
        Store.p(c).edit().putString("updState", s).apply();
    }
    private static final int ID = 50;

    static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Вызывается из фонового потока сервиса; сама решает, пора ли проверять. */
    static void maybeCheck(Context c, boolean force) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("updChecked", 0) < CHECK_EVERY_MS) return;
        p.edit().putLong("updChecked", now).apply();
        try {
            JSONObject v = new JSONObject(Data.get(BASE + "version.json"));
            long code = v.getLong("versionCode");
            p.edit().putLong("updLatest", code).putString("updLatestName", v.optString("versionName"))
                    .putString("updError", "").apply();
            if (code <= installedCode(c)) {
                state(c, "");
                return;
            }
            // одну и ту же сборку не качаем по кругу, если установка не прошла
            if (!force && code == p.getLong("updTried", 0)
                    && now - p.getLong("updTriedAt", 0) < RETRY_SAME_MS) return;
            p.edit().putLong("updTried", code).putLong("updTriedAt", now).apply();

            if (!c.getPackageManager().canRequestPackageInstalls()) {
                // нужно один раз разрешить приложению ставить обновления
                notify(c, "Доступно обновление " + v.optString("versionName"),
                        "Нажмите и разрешите установку — дальше обновления пойдут сами.",
                        PendingIntent.getActivity(c, 2,
                                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + c.getPackageName())),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                p.edit().putLong("updTriedAt", 0).apply(); // попробуем снова при следующей проверке
                state(c, "нужно разрешение на установку");
                return;
            }
            File apk = new File(c.getCacheDir(), "update.apk");
            state(c, "скачиваю");
            download(BASE + "svitlo-widget.apk", apk);
            state(c, "скачано " + apk.length() / 1024 + " КБ, передаю системе");
            install(c, apk);
            state(c, "передано системе, жду ответа");
        } catch (Exception e) {
            p.edit().putString("updError", e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
            state(c, "ошибка");
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            con.disconnect();
        }
    }

    private static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // обновление самого себя система может поставить без вопроса
            sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(c, Updater.class).setAction(ACTION_STATUS);
            PendingIntent done = PendingIntent.getBroadcast(c, 3, cb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(done.getIntentSender());
        }
    }

    /** Ответ системы на попытку установки. */
    @Override
    public void onReceive(Context c, Intent i) {
        if (!ACTION_STATUS.equals(i.getAction())) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Система хочет подтверждения. Если приложение открыто — показываем её окно сразу,
            // иначе — уведомление и кнопка «Обновить» в приложении.
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                state(c, "система просит подтверждение, но не дала окно");
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pendingConfirm = confirm;
            state(c, "ждёт вашего подтверждения — нажмите «Обновить»");
            if (MainActivity.visible) {
                try {
                    c.startActivity(confirm);
                    pendingConfirm = null;
                    return;
                } catch (Exception ignored) {
                }
            }
            notify(c, "Обновление готово", "Нажмите, чтобы установить новую версию.",
                    PendingIntent.getActivity(c, 4, confirm,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = "установка не прошла (код " + status + "): "
                    + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Store.p(c).edit().putString("updError", msg).putLong("updTriedAt", 0).apply();
            state(c, "ошибка");
        } else {
            state(c, "");
        }
        // при успехе система сама перезапустит приложение (см. BootReceiver)
    }

    /** Есть ли сборка новее установленной (по последней проверке). */
    static boolean available(Context c) {
        return Store.p(c).getLong("updLatest", 0) > installedCode(c);
    }

    private static void notify(Context c, String title, String text, PendingIntent tap) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Обновления приложения",
                NotificationManager.IMPORTANCE_HIGH));
        nm.notify(ID, new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build());
    }

    /** Строка для экрана «Источники». */
    static String statusLine(Context c) {
        SharedPreferences p = Store.p(c);
        long latest = p.getLong("updLatest", 0);
        long mine = installedCode(c);
        String s = "Версия " + installedName(c) + " (сборка " + mine + ")";
        if (latest > mine) {
            String st = p.getString("updState", "");
            s += " · есть новая " + p.getString("updLatestName", "") + (st.isEmpty() ? "" : ": " + st);
        }
        else if (latest > 0) s += " · последняя";
        long checked = p.getLong("updChecked", 0);
        if (checked > 0) s += " · проверено в " + Data.hm(checked);
        String err = p.getString("updError", "");
        if (!err.isEmpty()) s += "\n⚠️ Обновление: " + err;
        return s;
    }
}
