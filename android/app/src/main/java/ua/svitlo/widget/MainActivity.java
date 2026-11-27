package ua.svitlo.widget;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Экран настроек и текущего состояния. */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button battery;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences p = Store.p(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setFitsSystemWindows(true);

        status = label("");
        status.setTextSize(17);
        box.addView(status);

        box.addView(label("\nTelegram-канал с тревогами (имя после t.me/)"));
        final EditText channel = new EditText(this);
        channel.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        channel.setText(Store.channel(this));
        box.addView(channel);

        box.addView(label("\nГруппа (очередь) отключений ДТЭК, например 4.1"));
        final EditText group = new EditText(this);
        group.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        group.setText(Store.group(this));
        box.addView(group);

        box.addView(label("\nЗа сколько минут предупреждать об отключении (0 — не надо)"));
        final EditText remind = new EditText(this);
        remind.setInputType(InputType.TYPE_CLASS_NUMBER);
        remind.setText(String.valueOf(Store.remindMinutes(this)));
        box.addView(remind);

        Button save = new Button(this);
        save.setText("Сохранить и обновить");
        box.addView(save);

        battery = new Button(this);
        battery.setText("Разрешить работу в фоне без ограничений");
        box.addView(battery);

        box.addView(label("\nИсточники: тревога — Telegram-канал выше (проверка каждые 10 секунд); "
                + "свет — сайт dtek-dnem.com.ua (каждые 5 минут).\n\n"
                + "Виджет: долгое нажатие на пустом месте главного экрана → Виджеты → "
                + "«Тревога и свет».\n\n"
                + "Чтобы система не останавливала слежение, нажмите кнопку выше и не "
                + "смахивайте постоянное уведомление приложения."));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        save.setOnClickListener(v -> {
            String ch = channel.getText().toString().trim()
                    .replaceFirst("^(https?://)?t\\.me/(s/)?", "").replaceFirst("^@", "")
                    .replaceAll("[/?].*$", "");
            int mins = 30;
            try {
                mins = Integer.parseInt(remind.getText().toString().trim());
            } catch (Exception ignored) {
            }
            boolean channelChanged = !ch.equals(Store.channel(this));
            SharedPreferences.Editor e = p.edit()
                    .putString("channel", ch.isEmpty() ? Data.DEFAULT_CHANNEL : ch)
                    .putString("group", group.getText().toString().trim().replace(',', '.'))
                    .putInt("remind", mins);
            if (channelChanged) {
                e.remove("alert").remove("alertSince").remove("lastPostId").remove("alertChecked");
            }
            e.apply();
            channel.setText(Store.channel(this));
            startMonitor(true);
        });

        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        startMonitor(false);
    }

    private void startMonitor(boolean dtekNow) {
        try {
            Intent i = new Intent(this, MonitorService.class);
            if (dtekNow) i.setAction(MonitorService.ACTION_DTEK_NOW);
            startForegroundService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        tick.run();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
            main.postDelayed(this, 2000);
        }
    };

    private void show() {
        int a = Store.alert(this);
        long checked = Store.alertChecked(this);
        StringBuilder s = new StringBuilder();
        s.append(a == 1 ? "🚨 ТРЕВОГА с " + Data.hm(Store.alertSince(this))
                : a == 0 ? "✅ Тревоги нет (отбой в " + Data.hm(Store.alertSince(this)) + ")"
                : "Тревога: данных пока нет");
        if (checked > 0) {
            s.append("\nпроверено ").append((System.currentTimeMillis() - checked) / 1000)
                    .append(" с назад");
        }
        s.append("\n\n").append(Store.powerLine(this));
        JSONObject fact = Store.fact(this);
        if (fact != null) {
            s.append("\nДТЭК обновил график: ").append(fact.optString("update"))
                    .append("\nзагружено в ").append(Data.hm(Store.factFetched(this)))
                    .append("\nГруппы в графике: ").append(Data.groups(fact));
        }
        String err = Store.p(this).getString("dtekError", "");
        if (!err.isEmpty()) s.append("\n⚠️ ДТЭК: ").append(err);
        if (!MonitorService.running) s.append("\n⚠️ Слежение не запущено");
        status.setText(s);

        boolean exempt = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());
        battery.setEnabled(!exempt);
        battery.setText(exempt ? "Работа в фоне разрешена ✓"
                : "Разрешить работу в фоне без ограничений");
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        return t;
    }
}
