package ua.svitlo.widget;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/** Загрузка статуса тревоги и графика отключений. */
final class Data {
    static final String ALERTS_URL = "https://ubilling.net.ua/aerialalerts/";
    static final String SCHEDULE_URL =
            "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json";
    static final ZoneId KYIV = ZoneId.of("Europe/Kiev");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    static final String[] REGIONS = {
            "Дніпропетровська область", "Вінницька область", "Волинська область",
            "Донецька область", "Житомирська область", "Закарпатська область",
            "Запорізька область", "Івано-Франківська область", "Київська область",
            "Кіровоградська область", "Луганська область", "Львівська область",
            "Миколаївська область", "Одеська область", "Полтавська область",
            "Рівненська область", "Сумська область", "Тернопільська область",
            "Харківська область", "Херсонська область", "Хмельницька область",
            "Черкаська область", "Чернівецька область", "Чернігівська область", "м. Київ"
    };

    /** Тревога: null — не удалось узнать. */
    static final class Alert {
        Boolean active;
        String since = "";
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestProperty("User-Agent", "svitlo-widget");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static Alert alert(String region) {
        Alert a = new Alert();
        try {
            JSONObject st = new JSONObject(get(ALERTS_URL)).getJSONObject("states").getJSONObject(region);
            a.active = st.getBoolean("alertnow");
        } catch (Exception e) {
            a.active = null;
        }
        return a;
    }

    /** Одна строка о свете для группы, например "Свет есть · отключение 13:00–20:00". */
    static String power(String group) {
        try {
            JSONObject fact = new JSONObject(get(SCHEDULE_URL)).getJSONObject("fact").getJSONObject("data");
            String key = "GPV" + group;
            ZonedDateTime now = ZonedDateTime.now(KYIV);

            // Получасовые отрезки без света, склеенные через полночь.
            TreeMap<Long, JSONObject> days = new TreeMap<>();
            for (Iterator<String> it = fact.keys(); it.hasNext(); ) {
                String ts = it.next();
                days.put(Long.parseLong(ts), fact.getJSONObject(ts));
            }
            List<ZonedDateTime[]> spans = new ArrayList<>();
            boolean found = false;
            for (Long ts : days.keySet()) {
                JSONObject g = days.get(ts).optJSONObject(key);
                if (g == null) continue;
                found = true;
                ZonedDateTime base = Instant.ofEpochSecond(ts).atZone(KYIV);
                for (int h = 1; h <= 24; h++) {
                    String v = g.optString(String.valueOf(h), "yes");
                    boolean first = v.equals("no") || v.equals("first");
                    boolean second = v.equals("no") || v.equals("second");
                    for (int half = 0; half < 2; half++) {
                        if (!(half == 0 ? first : second)) continue;
                        ZonedDateTime s = base.plusMinutes((h - 1) * 60L + half * 30L);
                        ZonedDateTime e = s.plusMinutes(30);
                        if (!spans.isEmpty() && spans.get(spans.size() - 1)[1].equals(s)) {
                            spans.get(spans.size() - 1)[1] = e;
                        } else {
                            spans.add(new ZonedDateTime[]{s, e});
                        }
                    }
                }
            }
            if (!found) return "💡 Нет графика для группы " + group;

            for (ZonedDateTime[] sp : spans) {
                if (!now.isBefore(sp[1])) continue;
                if (!now.isBefore(sp[0])) {
                    return "🔌 Света нет до " + when(sp[1], now);
                }
                return "💡 Свет есть · отключение " + when(sp[0], now) + "–" + sp[1].format(HM);
            }
            return "💡 Свет есть · отключений по графику нет";
        } catch (Exception e) {
            return "💡 График недоступен";
        }
    }

    private static String when(ZonedDateTime t, ZonedDateTime now) {
        String s = t.format(HM);
        return t.toLocalDate().isAfter(now.toLocalDate()) ? "завтра " + s : s;
    }

    static String nowHm() {
        return ZonedDateTime.now(ZoneId.systemDefault()).format(HM);
    }

    private Data() {
    }
}
