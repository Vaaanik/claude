package ua.svitlo.widget;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/** Главный экран: тревога, ход тревоги, свет на день, настройки. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0E1217, CARD = 0xFF1A2029, TEXT = 0xFFF2F4F7,
            DIM = 0xFF9AA4AF, RED = 0xFFE5484D, GREEN = 0xFF2E9E5B, GREY = 0xFF55606C,
            ORANGE = 0xFFF2913D, BLUE = 0xFF4C8DF6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private float d;

    private LinearLayout statusCard;
    private TextView statusTitle, statusSub;
    private LinearLayout fcCard;
    private TextView fcTitle, fcEta, fcDetail, fcBasis;
    private TextView historyTitle, historyEmpty;
    private LinearLayout historyRows;
    private String historyKey = "";
    private TextView powerNow, todayText, tomorrowLabel, tomorrowText, powerMeta;
    private DayBar todayBar, tomorrowBar;
    private TextView sources;
    private Button battery, updButton;
    private LinearLayout settings;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.migrate(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        root.setFitsSystemWindows(true);

        // ---- тревога
        statusCard = card(root, GREY);
        statusCard.setPadding(dp(20), dp(22), dp(20), dp(22));
        statusTitle = text(statusCard, "", 30, 0xFFFFFFFF, true);
        statusSub = text(statusCard, "", 15, 0xE6FFFFFF, false);

        // ---- прогноз для города
        fcCard = card(root, CARD);
        text(fcCard, "Прогноз для вас · Кривой Рог, ЦМ", 13, DIM, true);
        fcTitle = text(fcCard, "", 22, TEXT, true);
        fcCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        fcEta = text(fcCard, "", 19, TEXT, true);
        fcDetail = text(fcCard, "", 15, TEXT, false);
        fcBasis = text(fcCard, "", 13, DIM, false);
        text(fcCard, "Оценка по названиям мест в сообщениях всех каналов, не данные радаров. "
                + "Расстояния — до вашей точки, время подлёта приблизительное.", 11, DIM, false);

        // ---- ход тревоги
        LinearLayout hist = card(root, CARD);
        historyTitle = text(hist, "Ход тревоги", 18, TEXT, true);
        historyEmpty = text(hist, "", 14, DIM, false);
        historyRows = new LinearLayout(this);
        historyRows.setOrientation(LinearLayout.VERTICAL);
        hist.addView(historyRows);

        // ---- свет
        LinearLayout power = card(root, CARD);
        text(power, "Свет", 18, TEXT, true);
        powerNow = text(power, "", 16, TEXT, false);
        gap(power, 10);
        text(power, "Сегодня", 13, DIM, true);
        todayBar = new DayBar(this);
        power.addView(todayBar);
        todayText = text(power, "", 15, TEXT, false);
        gap(power, 12);
        tomorrowLabel = text(power, "Завтра", 13, DIM, true);
        tomorrowBar = new DayBar(this);
        power.addView(tomorrowBar);
        tomorrowText = text(power, "", 15, TEXT, false);
        gap(power, 8);
        legend(power);
        powerMeta = text(power, "", 12, DIM, false);

        // ---- источники
        LinearLayout src = card(root, CARD);
        text(src, "Источники", 18, TEXT, true);
        sources = text(src, "", 13, DIM, false);

        // ---- настройки (свёрнуты)
        final Button toggle = button(root, "Настройки", GREY);
        settings = card(root, CARD);
        settings.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> settings.setVisibility(
                settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        buildSettings();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        startMonitor(false);
    }

    // ------------------------------------------------------------ настройки

    private void buildSettings() {
        final SharedPreferences p = Store.p(this);
        final EditText channel = field("Канал сирены (имя после t.me/)", Store.channel(this), false);
        final EditText threatCh = field("Каналы об угрозах, через запятую\n"
                + "(* после имени — местный канал, где всё про ваш город)", Store.threatChannels(this), true);
        final EditText myW = field("Слова «мой район» (как его пишут в каналах)", Store.myWords(this), true);
        final EditText homeP = field("Точка дома: широта, долгота (в Google Картах — долгое нажатие "
                + "на своём доме, скопировать два числа)", Store.homeText(this), false);
        final android.widget.CheckBox server = new android.widget.CheckBox(this);
        server.setText("Работать через сервер (экономит батарею)");
        server.setTextColor(TEXT);
        server.setChecked(Push.serverMode(this));
        if (Push.configured()) settings.addView(server);
        final android.widget.CheckBox siren = new android.widget.CheckBox(this);
        siren.setText("Уведомлять о начале тревоги и отбое");
        siren.setTextColor(TEXT);
        siren.setChecked(Store.sirenNotify(this));
        settings.addView(siren);
        final EditText cityW = field("Слова «мой город», через запятую", Store.cityWords(this), true);
        final EditText areaW = field("Слова «рядом»: район и подступы", Store.areaWords(this), true);
        final EditText group = field("Группа (очередь) отключений ДТЭК", Store.group(this), false);
        group.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText remind = field("За сколько минут предупреждать об отключении (0 — не надо)",
                String.valueOf(Store.remindMinutes(this)), false);
        remind.setInputType(InputType.TYPE_CLASS_NUMBER);

        Button save = button(settings, "Сохранить и обновить", BLUE);
        battery = button(settings, "Разрешить работу в фоне без ограничений", GREY);
        Button upd = button(settings, "Разрешить автообновление", GREY);
        upd.setOnClickListener(v -> {
            if (getPackageManager().canRequestPackageInstalls()) {
                new Thread(() -> Updater.maybeCheck(getApplicationContext(), true)).start();
                upd.setText("Проверяю обновление…");
            } else {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        updButton = upd;
        text(settings, "\nВиджет: долгое нажатие на пустом месте главного экрана → Виджеты → "
                + "«Тревога и свет».\n\nЧтобы система не останавливала слежение, разрешите работу "
                + "в фоне и не смахивайте постоянное уведомление приложения.", 13, DIM, false);

        save.setOnClickListener(v -> {
            String ch = Store.cleanChannel(channel.getText().toString());
            int mins = 30;
            try {
                mins = Integer.parseInt(remind.getText().toString().trim());
            } catch (Exception ignored) {
            }
            boolean channelChanged = !ch.equals(Store.channel(this));
            SharedPreferences.Editor e = p.edit()
                    .putString("channel", ch.isEmpty() ? Data.DEFAULT_CHANNEL : ch)
                    .putString("group", group.getText().toString().trim().replace(',', '.'))
                    .putInt("remind", mins)
                    .putString("threatChannels", threatCh.getText().toString().trim())
                    .putString("cityWords", cityW.getText().toString().trim())
                    .putString("myWords", myW.getText().toString().trim())
                    .putBoolean("sirenNotify", siren.isChecked())
                    .putBoolean("serverMode", server.isChecked())
                    .putString("areaWords", areaW.getText().toString().trim());
            try {
                String[] ll = homeP.getText().toString().trim().split("[,;\\s]+");
                float la = Float.parseFloat(ll[0]), lo = Float.parseFloat(ll[1]);
                if (la > 47 && la < 49 && lo > 32 && lo < 35) e.putFloat("homeLat", la).putFloat("homeLon", lo);
            } catch (Exception ignored) {
            }
            if (channelChanged) {
                e.remove("alert").remove("alertSince").remove("lastPostId").remove("alertChecked")
                        .remove("alertStart").remove("alertEnd");
            }
            e.apply();
            channel.setText(Store.channel(this));
            startMonitor(true);
            settings.setVisibility(View.GONE);
            show();
        });

        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
    }

    /** Включает слежение в выбранном режиме; dtekNow — сразу перечитать график света. */
    private void startMonitor(boolean dtekNow) {
        try {
            if (Push.serverMode(this)) {
                Push.apply(this);
                // в режиме «через сервер» график читаем при открытии приложения
                if (dtekNow || System.currentTimeMillis() - Store.factFetched(this) > 5 * 60_000) {
                    DtekJob.fetchNow(this, null);
                }
            } else {
                Push.apply(this);
                Intent i = new Intent(this, MonitorService.class);
                if (dtekNow) i.setAction(MonitorService.ACTION_DTEK_NOW);
                startForegroundService(i);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------- обновление

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
        long now = System.currentTimeMillis();
        showStatus(now);
        showForecast();
        showHistory(now);
        showPower();
        showSources(now);

        boolean exempt = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());
        if (!updButton.getText().toString().startsWith("Проверяю")) {
            updButton.setText(getPackageManager().canRequestPackageInstalls()
                    ? "Автообновление включено ✓ · проверить сейчас" : "Разрешить автообновление");
        }
        battery.setEnabled(!exempt);
        battery.setText(exempt ? "Работа в фоне разрешена ✓"
                : "Разрешить работу в фоне без ограничений");
    }

    private void showStatus(long now) {
        int a = Store.alert(this);
        long checked = Store.alertChecked(this);
        boolean stale = checked == 0 || Push.stale(this, now);
        long since = Store.alertSince(this);
        if (a == -1 || stale) {
            tint(statusCard, GREY);
            statusTitle.setText(a == 1 ? "ТРЕВОГА (?)" : "Нет связи");
            statusSub.setText(checked == 0 ? "данные ещё не получены"
                    : "последняя проверка в " + Data.hm(checked));
        } else if (a == 1) {
            tint(statusCard, RED);
            statusTitle.setText("🚨 ТРЕВОГА");
            statusSub.setText("Кривой Рог · с " + Data.hm(since) + " · идёт " + dur(now - since));
        } else {
            tint(statusCard, GREEN);
            statusTitle.setText("Тревоги нет");
            statusSub.setText("Кривой Рог · отбой в " + Data.hm(since) + " · тихо " + dur(now - since));
        }
    }

    private void showForecast() {
        Forecast.Result r = Store.forecast(this);
        fcTitle.setText(r.title);
        fcTitle.setTextColor(r.level >= Forecast.OVER ? 0xFFFF6B6F : r.level == Forecast.TOWARD
                ? ORANGE : r.level == Forecast.NEAR ? 0xFFF2C94C : r.level == Forecast.GONE
                ? 0xFF5FD08A : TEXT);
        // когда речь о вашем районе — вся карточка красная
        tint(fcCard, r.level == Forecast.MINE ? 0xFF8E1B22
                : r.level == Forecast.TOWARD && r.title.contains("ВАШ") ? 0xFF7A4A12 : CARD);
        fcEta.setText(r.eta);
        fcEta.setVisibility(r.eta.isEmpty() ? View.GONE : View.VISIBLE);
        fcDetail.setText(r.detail);
        fcDetail.setVisibility(r.detail.isEmpty() ? View.GONE : View.VISIBLE);
        fcBasis.setText(r.basis.isEmpty() ? "" : "Основание: " + r.basis);
        fcBasis.setVisibility(r.basis.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Все сообщения об угрозах за одну тревогу — текущую или последнюю. */
    private void showHistory(long now) {
        boolean active = Store.alert(this) == 1;
        long start = Store.alertStart(this);
        long end = active ? 0 : Store.alertEnd(this);
        long pad = 10 * 60_000;
        long from, to;
        String title;
        if (start == 0) {
            from = now - 3 * 3600_000L;
            to = now;
            title = "Сообщения за 3 часа";
        } else if (active || end == 0) {
            from = start - pad;
            to = now + pad;
            title = "Ход тревоги · с " + Data.hm(start);
        } else {
            from = start - pad;
            to = end + pad;
            title = "Последняя тревога · " + Data.hm(start) + "–" + Data.hm(end);
        }
        List<Threats.Item> all = Store.threats(this, 12 * 3600_000L);
        StringBuilder key = new StringBuilder(title);
        int n = 0;
        for (Threats.Item it : all) {
            if (it.timeMs < from || it.timeMs > to) continue;
            key.append('|').append(it.key());
            n++;
        }
        historyTitle.setText(title + (n > 0 ? " · " + n : ""));
        if (key.toString().equals(historyKey)) return; // ничего нового — не перерисовываем
        historyKey = key.toString();

        historyRows.removeAllViews();
        historyEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        historyEmpty.setText("Сообщений о целях за эту тревогу не было.");
        int shown = 0;
        for (Threats.Item it : all) { // новые сверху
            if (it.timeMs < from || it.timeMs > to) continue;
            historyRows.addView(row(it, shown == 0));
            if (++shown >= 150) break;
        }
    }

    /** Строка истории: время | цветная полоска | что и куда | канал. */
    private View row(Threats.Item it, boolean latest) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(7), 0, dp(7));

        TextView time = new TextView(this);
        time.setText(Data.hm(it.timeMs));
        time.setTextSize(15);
        time.setTextColor(latest ? TEXT : DIM);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(time, new LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT));

        View bar = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(it.type));
        g.setCornerRadius(dp(2));
        bar.setBackground(g);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(4),
                LinearLayout.LayoutParams.MATCH_PARENT);
        bl.setMargins(0, dp(2), dp(10), dp(2));
        r.addView(bar, bl);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView body = new TextView(this);
        body.setText(it.text);
        body.setTextSize(latest ? 17 : 15);
        body.setTextColor(TEXT);
        if (latest) body.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(body);
        TextView meta = new TextView(this);
        meta.setText(it.type + " · " + it.source);
        meta.setTextSize(12);
        meta.setTextColor(DIM);
        col.addView(meta);
        r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private static int color(String type) {
        if (type.startsWith("✅")) return GREEN;
        if (type.startsWith("🚀") || type.startsWith("💣") || type.startsWith("💥")) return RED;
        if (type.startsWith("🏍") || type.startsWith("🛵")) return ORANGE;
        return GREY;
    }

    private void showPower() {
        String group = Store.group(this);
        JSONObject fact = Store.fact(this);
        powerNow.setText(Store.powerLine(this).replaceFirst("^\\S+ ", ""));
        ZonedDateTime now = ZonedDateTime.now(Data.KYIV);
        LocalDate today = now.toLocalDate();
        String h1 = fact == null ? null : Data.halves(fact, group, today);
        String h2 = fact == null ? null : Data.halves(fact, group, today.plusDays(1));
        todayBar.set(h1, now.getHour() + now.getMinute() / 60f);
        todayText.setText(dayText(h1));
        boolean hasTomorrow = h2 != null;
        tomorrowLabel.setVisibility(hasTomorrow ? View.VISIBLE : View.GONE);
        tomorrowBar.setVisibility(hasTomorrow ? View.VISIBLE : View.GONE);
        tomorrowText.setVisibility(hasTomorrow ? View.VISIBLE : View.GONE);
        if (hasTomorrow) {
            tomorrowBar.set(h2, -1);
            tomorrowText.setText(dayText(h2));
        }
        StringBuilder m = new StringBuilder("Группа " + group);
        if (fact != null) {
            m.append(" · ДТЭК обновил: ").append(fact.optString("update"))
                    .append(" · загружено в ").append(Data.hm(Store.factFetched(this)));
            if (h1 == null && h2 == null) {
                m.append("\nВ графике нет группы ").append(group).append(". Есть: ")
                        .append(Data.groups(fact));
            }
        }
        String err = Store.p(this).getString("dtekError", "");
        if (!err.isEmpty()) m.append("\n⚠️ ").append(err);
        powerMeta.setText(m);
    }

    private static String dayText(String h) {
        if (h == null) return "Графика пока нет";
        String off = Data.intervals(h, 'X');
        String maybe = Data.intervals(h, '?');
        StringBuilder s = new StringBuilder();
        if (off.isEmpty()) s.append("Отключений нет");
        else s.append("Нет света: ").append(off).append("  ·  всего ")
                .append(hoursText(Data.hours(h, 'X')));
        if (!maybe.isEmpty()) s.append("\nВозможно: ").append(maybe);
        return s.toString();
    }

    private static String hoursText(double h) {
        return (h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h).replace('.', ',')) + " ч";
    }

    private void showSources(long now) {
        StringBuilder s = new StringBuilder();
        long checked = Store.alertChecked(this);
        s.append("Сирена · ").append(Store.channel(this)).append(": ").append(ago(now, checked));
        for (String c : Store.threatChannelList(this)) {
            c = c.replace("*", "");
            s.append("\n").append(c).append(": ").append(ago(now, Store.p(this).getLong("ok_" + c, 0)));
        }
        long f = Store.factFetched(this);
        s.append("\nСайт ДТЭК: ").append(f == 0 ? "ещё не загружен" : "в " + Data.hm(f));
        if (Push.serverMode(this)) {
            SharedPreferences sp = Store.p(this);
            long tick = sp.getLong("serverTick", 0), pushAt = sp.getLong("pushAt", 0);
            s.append("\n\nРежим: через сервер")
                    .append("\nСервер проверял каналы: ").append(tick == 0 ? "ещё неизвестно" : ago(now, tick))
                    .append("\nПоследнее push-сообщение: ").append(pushAt == 0 ? "ещё не было" : ago(now, pushAt));
            if (!sp.getBoolean("serverPush", true)) s.append("\n⚠️ На сервере не настроена отправка push");
            if (!sp.getBoolean("pushSubscribed", false)) s.append("\n⚠️ Подписка на push ещё не подтверждена");
            String pe = sp.getString("pushError", ""), se = sp.getString("serverError", "");
            if (!pe.isEmpty()) s.append("\n⚠️ Push: ").append(pe);
            if (!se.isEmpty()) s.append("\n⚠️ ").append(se);
        } else {
            s.append("\n\nРежим: телефон сам опрашивает каналы");
            if (!MonitorService.running) s.append("\n⚠️ Слежение не запущено");
        }
        s.append("\n\n").append(Updater.statusLine(this));
        if (!getPackageManager().canRequestPackageInstalls()) {
            s.append("\n⚠️ Автообновление выключено: нажмите «Разрешить автообновление» в настройках");
        }
        sources.setText(s);
    }

    private static String ago(long now, long t) {
        if (t == 0) return "ещё не читался";
        long sec = (now - t) / 1000;
        return sec < 60 ? sec + " с назад" : (sec / 60) + " мин назад";
    }

    private static String dur(long ms) {
        long m = Math.max(0, ms / 60_000);
        return m < 60 ? m + " мин" : (m / 60) + " ч " + (m % 60) + " мин";
    }

    // ------------------------------------------------------------ элементы

    private int dp(float v) {
        return (int) (v * d);
    }

    private LinearLayout card(LinearLayout parent, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        tint(c, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(c, lp);
        return c;
    }

    private void tint(View v, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(20));
        v.setBackground(g);
    }

    private TextView text(LinearLayout parent, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        parent.addView(t);
        return t;
    }

    private void gap(LinearLayout parent, int h) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
    }

    private Button button(LinearLayout parent, String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(6), 0, dp(10));
        parent.addView(b, lp);
        return b;
    }

    private EditText field(String label, String value, boolean multi) {
        text(settings, label, 13, DIM, false);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        settings.addView(e, lp);
        return e;
    }

    private void legend(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"свет есть", "нет света", "возможно"};
        int[] colors = {DayBar.ON, DayBar.OFF, DayBar.MAYBE};
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(colors[i]);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
            lp.setMargins(i == 0 ? 0 : dp(14), 0, dp(6), 0);
            row.addView(dot, lp);
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(12);
            t.setTextColor(DIM);
            row.addView(t);
        }
        parent.addView(row);
    }
}
