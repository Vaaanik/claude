package ua.svitlo.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Настройки и последнее известное состояние (общие для сервиса, виджета и экрана настроек). */
final class Store {
    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // --- настройки
    static String channel(Context c) {
        return p(c).getString("channel", Data.DEFAULT_CHANNEL);
    }

    static String group(Context c) {
        return p(c).getString("group", "8.1");
    }

    static int remindMinutes(Context c) {
        return p(c).getInt("remind", 30);
    }

    // --- тревога
    /** 1 — тревога, 0 — тревоги нет, -1 — ещё неизвестно. */
    static int alert(Context c) {
        return p(c).getInt("alert", -1);
    }

    static long alertSince(Context c) {
        return p(c).getLong("alertSince", 0);
    }

    static long alertChecked(Context c) {
        return p(c).getLong("alertChecked", 0);
    }

    /** Начало последней тревоги (0 — неизвестно). */
    static long alertStart(Context c) {
        return p(c).getLong("alertStart", 0);
    }

    /** Отбой последней тревоги (0 — тревога ещё идёт или неизвестно). */
    static long alertEnd(Context c) {
        return p(c).getLong("alertEnd", 0);
    }

    /** Отключения на сегодня одной строкой для виджета; "" — если данных нет. */
    static String todayLine(Context c) {
        JSONObject fact = fact(c);
        if (fact == null) return "";
        String h = Data.halves(fact, group(c), java.time.LocalDate.now(Data.KYIV));
        if (h == null) return "";
        String off = Data.intervals(h, 'X');
        return off.isEmpty() ? "Сегодня без отключений" : "Сегодня нет света: " + off;
    }

    static long lastPostId(Context c) {
        return p(c).getLong("lastPostId", 0);
    }

    // --- свет
    static JSONObject fact(Context c) {
        try {
            String s = p(c).getString("fact", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static long factFetched(Context c) {
        return p(c).getLong("factFetched", 0);
    }

    /** Строка о свете для виджета и экрана настроек. */
    static String powerLine(Context c) {
        JSONObject fact = fact(c);
        if (fact == null) {
            String err = p(c).getString("dtekError", "");
            return "💡 График ДТЭК ещё не загружен" + (err.isEmpty() ? "" : " (" + err + ")");
        }
        try {
            List<ZonedDateTime[]> spans = Data.spans(fact, group(c));
            return Data.powerLine(spans, group(c), ZonedDateTime.now(Data.KYIV));
        } catch (Exception e) {
            return "💡 Не удалось разобрать график ДТЭК";
        }
    }

    /** Разовое добавление новых каналов по умолчанию к уже сохранённому списку. */
    static void migrate(Context c) {
        SharedPreferences sp = p(c);
        if (sp.getInt("defaultsVersion", 0) >= 5) return;
        SharedPreferences.Editor e = sp.edit().putInt("defaultsVersion", 5);
        // прежняя группа по умолчанию была 4.2 — заменяем на 8.1
        if ("4.2".equals(sp.getString("group", null))) e.putString("group", "8.1");
        String saved = sp.getString("threatChannels", null);
        if (saved != null) {
            StringBuilder sb = new StringBuilder(saved.trim());
            for (String d : Threats.DEFAULT_CHANNELS.split(",\\s*")) {
                if (!saved.toLowerCase().contains(d.replace("*", "").toLowerCase())) {
                    sb.append(sb.length() > 0 ? ", " : "").append(d);
                }
            }
            e.putString("threatChannels", sb.toString());
        }
        e.apply();
    }

    // --- угрозы
    static String threatChannels(Context c) {
        return p(c).getString("threatChannels", Threats.DEFAULT_CHANNELS);
    }

    static List<String> threatChannelList(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : threatChannels(c).split("[,\\s]+")) {
            s = cleanChannel(s);
            if (!s.replace("*", "").isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** "https://t.me/s/name?x" или "@name" -> "name". */
    static String cleanChannel(String s) {
        return s.trim().replaceFirst("^(https?://)?t\\.me/(s/)?", "").replaceFirst("^@", "")
                .replaceAll("[/?].*$", "");
    }

    static String cityWords(Context c) {
        return p(c).getString("cityWords", Threats.DEFAULT_CITY);
    }

    static String areaWords(Context c) {
        return p(c).getString("areaWords", Threats.DEFAULT_AREA);
    }

    /** Сообщения об угрозах не старше maxAgeMs, новые первыми. */
    static List<Threats.Item> threats(Context c, long maxAgeMs) {
        List<Threats.Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p(c).getString("threats", "[]"));
            long min = System.currentTimeMillis() - maxAgeMs;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.getLong("t") < min) continue;
                Threats.Item it = new Threats.Item();
                it.source = o.getString("s");
                it.id = o.getLong("i");
                it.timeMs = o.getLong("t");
                it.city = o.getBoolean("c");
                it.type = o.getString("y");
                it.text = o.getString("x");
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        out.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));
        return out;
    }

    /** Добавляет новые сообщения и выбрасывает старше 12 часов. */
    static synchronized void addThreats(Context c, List<Threats.Item> items) {
        List<Threats.Item> all = threats(c, 12 * 3600_000L);
        for (Threats.Item it : items) {
            boolean dup = false;
            for (Threats.Item old : all) if (old.key().equals(it.key())) dup = true;
            if (!dup) all.add(it);
        }
        all.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < all.size() && i < 400; i++) {
                Threats.Item it = all.get(i);
                arr.put(new JSONObject().put("s", it.source).put("i", it.id).put("t", it.timeMs)
                        .put("c", it.city).put("y", it.type).put("x", it.text));
            }
        } catch (Exception ignored) {
        }
        p(c).edit().putString("threats", arr.toString()).apply();
    }

    /** Строка для виджета/ленты: "23:41 🛵 БпЛА: текст · канал". */
    static String line(Threats.Item it) {
        return Data.hm(it.timeMs) + " " + it.type + ": " + it.text + " · " + it.source;
    }

    private Store() {
    }
}
