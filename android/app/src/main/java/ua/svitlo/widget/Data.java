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
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор источников: Telegram-канал с тревогами и график ДТЭК. Без зависимостей от Android. */
final class Data {
    static final String DEFAULT_CHANNEL = "sirena_kryvyi_rih";
    static final String DTEK_URL = "https://www.dtek-dnem.com.ua/ua/shutdowns";
    static final ZoneId KYIV = ZoneId.of("Europe/Kiev");
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    // ------------------------------------------------------------------ сеть

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile");
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

    // --------------------------------------------------------------- тревога

    /** Последнее сообщение канала, которое объявляет тревогу или отбой. */
    static final class Post {
        long id;          // номер сообщения в канале
        boolean alert;    // true — тревога, false — отбой
        long timeMs;      // когда опубликовано
        String text = "";
    }

    static final class Channel {
        long lastId;      // самый свежий номер сообщения на странице (0 — постов нет)
        Post last;        // последнее сообщение о тревоге/отбое, если есть
        long lastAlertMs; // время последнего объявления тревоги на странице (0 — нет)
        long lastClearMs; // время последнего отбоя на странице (0 — нет)
    }

    private static final Pattern POST = Pattern.compile("data-post=\"[^\"/]+/(\\d+)\"");
    private static final Pattern TEXT = Pattern.compile(
            "tgme_widget_message_text js-message_text[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern TIME = Pattern.compile("<time datetime=\"([^\"]+)\" class=\"time\"");

    /** Адрес публичной страницы канала; after>0 — только сообщения новее этого номера. */
    static String channelUrl(String channel, long after) {
        return "https://t.me/s/" + channel + (after > 0 ? "?after=" + after : "");
    }

    /** Одно сообщение канала как есть. */
    static final class Raw {
        long id;
        long timeMs;
        String text = "";   // переносы строк сохранены
        String reply = "";  // начало сообщения, на которое это отвечает
    }

    private static final Pattern REPLY = Pattern.compile(
            "js-message_reply_text[^>]*>(.*?)</div>", Pattern.DOTALL);

    private static String plain(String html) {
        return html.replaceAll("<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")
                .replace("&#33;", "!").replace("&#39;", "'").replace("&#036;", "$")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("[ \\t]+", " ").trim();
    }

    /** Все сообщения со страницы канала, от старых к новым. */
    static List<Raw> posts(String html) {
        List<Raw> out = new ArrayList<>();
        String[] blocks = html.split("tgme_widget_message_wrap");
        for (int i = 1; i < blocks.length; i++) {
            String b = blocks[i];
            Matcher p = POST.matcher(b);
            if (!p.find()) continue;
            Raw r = new Raw();
            r.id = Long.parseLong(p.group(1));
            Matcher t = TEXT.matcher(b);
            if (t.find()) {
                r.text = plain(t.group(1));
            }
            Matcher rp = REPLY.matcher(b);
            if (rp.find()) r.reply = plain(rp.group(1));
            Matcher tm = TIME.matcher(b);
            if (tm.find()) {
                try {
                    r.timeMs = ZonedDateTime.parse(tm.group(1)).toInstant().toEpochMilli();
                } catch (Exception ignored) {
                }
            }
            out.add(r);
        }
        out.sort((x, y) -> Long.compare(x.id, y.id));
        return out;
    }

    static Channel parseChannel(String html) {
        Channel ch = new Channel();
        for (Raw r : posts(html)) {
            ch.lastId = Math.max(ch.lastId, r.id);
            Boolean kind = classify(r.text);
            if (kind == null) continue;
            Post post = new Post();
            post.id = r.id;
            post.alert = kind;
            post.text = r.text.replaceAll("\\s+", " ").trim();
            post.timeMs = r.timeMs;
            ch.last = post;
            if (kind) ch.lastAlertMs = r.timeMs;
            else ch.lastClearMs = r.timeMs;
        }
        return ch;
    }

    /** true — объявлена тревога, false — отбой, null — сообщение о другом. */
    static Boolean classify(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        if (s.contains("відбій") || s.contains("отбой")) return false;
        if (s.contains("тривог") || s.contains("тревог")) return true;
        return null;
    }

    // ----------------------------------------------------------------- свет

    /** Отрезки без света по графику группы, склеенные через полночь. null — группы нет в графике. */
    static List<ZonedDateTime[]> spans(JSONObject fact, String group) throws Exception {
        JSONObject data = fact.getJSONObject("data");
        String key = "GPV" + group;
        TreeMap<Long, JSONObject> days = new TreeMap<>();
        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String ts = it.next();
            days.put(Long.parseLong(ts), data.getJSONObject(ts));
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
        return found ? spans : null;
    }

    /**
     * График группы по дням начиная с сегодняшнего: {начало дня: значения по часам}.
     * Нужен, чтобы понять, изменился ли график. null — группы нет в графике.
     */
    static JSONObject dayKeys(JSONObject fact, String group, ZonedDateTime now) throws Exception {
        JSONObject data = fact.getJSONObject("data");
        long today = now.toLocalDate().atStartOfDay(KYIV).toEpochSecond();
        JSONObject out = new JSONObject();
        boolean found = false;
        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String ts = it.next();
            JSONObject g = data.getJSONObject(ts).optJSONObject("GPV" + group);
            if (g == null) continue;
            found = true;
            if (Long.parseLong(ts) < today) continue;
            StringBuilder sb = new StringBuilder();
            for (int h = 1; h <= 24; h++) sb.append(g.optString(String.valueOf(h), "yes")).append(',');
            out.put(ts, sb.toString());
        }
        return found ? out : null;
    }

    /** true, если в новом графике есть день, которого не было или который отличается. */
    static boolean changed(String oldDays, JSONObject newDays) {
        try {
            if (oldDays == null) return true;
            if (newDays == null) return !oldDays.equals("null");
            if (oldDays.equals("null")) return true;
            JSONObject old = new JSONObject(oldDays);
            for (Iterator<String> it = newDays.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!newDays.getString(k).equals(old.optString(k, ""))) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * График группы на один день по получасам: 48 символов,
     * X — света нет, ? — возможно отключение, . — свет есть. null — данных на день нет.
     */
    static String halves(JSONObject fact, String group, java.time.LocalDate day) {
        try {
            long ts = day.atStartOfDay(KYIV).toEpochSecond();
            JSONObject d = fact.getJSONObject("data").optJSONObject(String.valueOf(ts));
            if (d == null) return null;
            JSONObject g = d.optJSONObject("GPV" + group);
            if (g == null) return null;
            StringBuilder sb = new StringBuilder();
            for (int h = 1; h <= 24; h++) {
                switch (g.optString(String.valueOf(h), "yes")) {
                    case "no": sb.append("XX"); break;
                    case "first": sb.append("X."); break;
                    case "second": sb.append(".X"); break;
                    case "maybe": sb.append("??"); break;
                    case "mfirst": sb.append("?."); break;
                    case "msecond": sb.append(".?"); break;
                    default: sb.append("..");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Отрезки с данным знаком одной строкой: "10:00–13:30, 19:00–22:30". */
    static String intervals(String halves, char mark) {
        StringBuilder sb = new StringBuilder();
        int start = -1;
        for (int i = 0; i <= halves.length(); i++) {
            boolean on = i < halves.length() && halves.charAt(i) == mark;
            if (on && start < 0) start = i;
            if (!on && start >= 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(half(start)).append("–").append(half(i));
                start = -1;
            }
        }
        return sb.toString();
    }

    /** Сколько часов в сутках с данным знаком. */
    static double hours(String halves, char mark) {
        int n = 0;
        for (int i = 0; i < halves.length(); i++) if (halves.charAt(i) == mark) n++;
        return n / 2.0;
    }

    private static String half(int i) {
        return String.format(Locale.ROOT, "%02d:%02d", i / 2, 30 * (i % 2));
    }

    /** Список групп, которые есть в графике, например "1.1, 2.1, …". */
    static String groups(JSONObject fact) {
        try {
            JSONObject data = fact.getJSONObject("data");
            JSONObject day = data.getJSONObject(data.keys().next());
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = day.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.startsWith("GPV")) names.add(k.substring(3));
            }
            names.sort((a, b) -> Double.compare(Double.parseDouble(a), Double.parseDouble(b)));
            return String.join(", ", names);
        } catch (Exception e) {
            return "";
        }
    }

    /** Строка о свете на данный момент, например "Свет есть · отключение 13:00–20:00". */
    static String powerLine(List<ZonedDateTime[]> spans, String group, ZonedDateTime now) {
        if (spans == null) return "💡 В графике ДТЭК нет группы " + group;
        for (ZonedDateTime[] sp : spans) {
            if (!now.isBefore(sp[1])) continue;
            if (!now.isBefore(sp[0])) return "🔌 Света нет до " + when(sp[1], now);
            return "💡 Свет есть · отключение " + when(sp[0], now) + "–" + sp[1].format(HM);
        }
        return "💡 Свет есть · отключений по графику нет";
    }

    /** Все отключения с этого момента одной строкой — для уведомления о новом графике. */
    static String summary(List<ZonedDateTime[]> spans, ZonedDateTime now) {
        StringBuilder sb = new StringBuilder();
        for (ZonedDateTime[] sp : spans) {
            if (!now.isBefore(sp[1])) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(when(sp[0], now)).append("–").append(sp[1].format(HM));
        }
        return sb.length() == 0 ? "Отключений по графику нет" : "Без света: " + sb;
    }

    static String when(ZonedDateTime t, ZonedDateTime now) {
        String s = t.format(HM);
        return t.toLocalDate().isAfter(now.toLocalDate()) ? "завтра " + s : s;
    }

    static String hm(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM);
    }

    private Data() {
    }
}
