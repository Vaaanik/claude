package ua.svitlo.widget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Оценка «летит ли к Кривому Рогу и когда будет» по цепочке сообщений каналов.
 * Это грубая оценка по названиям мест в сообщениях, а не данные радаров.
 */
final class Forecast {
    static final int NONE = 0, GONE = 1, NEAR = 2, TOWARD = 3, OVER = 4, MINE = 5;

    /** Сообщение старше этого в прогнозе не участвует. */
    static final long FRESH_MS = 12 * 60_000;
    /** Дальше этого «рядом» не считается. */
    private static final double NEAR_KM = 70;

    /** Что понято из одного сообщения. */
    static final class Obs {
        int level = NONE;
        Geo.Place pos;        // где цель сейчас (если названо место вне города)
        boolean ballistic;
        double vMin, vMax;    // скорость, км/ч
        long timeMs;
        String source = "";
        String text = "";
        boolean mine;         // назван «мой район» — как место или как цель курса
        String districts = ""; // какие районы города названы
    }

    /** Итог для экрана и виджета. */
    static final class Result {
        int level = NONE;
        String title = "";
        String eta = "";      // «≈ 5–9 мин» или ""
        String detail = "";   // место, расстояние, тенденция
        String basis = "";    // сообщение, на котором основан вывод
        long timeMs;
    }

    private static final Pattern DEST = Pattern.compile(
            "курсом на|курс на|курс|вектор\\S* на|в бік|у бік|в напрямку|у напрямку|напрямок"
                    + "|далі|➡|->|→"
                    + "|(?:бпла|шахед\\S*|реактив\\S*|ракет\\S*|балісти\\S*|бандерол\\S*|ціл[ьі]\\S*"
                    + "|каб\\S*|мопед\\S*|дрон\\S*)\\s+на(?=\\s)"
                    + "|\\sна/повз"
                    // «відвернув на Широке», «на Апостолове» — но не «на півночі від», «на зв'язку»
                    + "|(?:^|\\s)на(?=\\s(?!півн|півд|сход|заход|зв|тот|терит|меж|разі))");
    private static final String[] AWAY = {"без загроз", "чисто", "вилетів", "пішов", "попер",
            "за межі", "відбій", "світлішаємо", "транзит", "покинув", "зник", "виліта", "вилет",
            "відверну", "пройшов"};
    /** Области и районы в стороне от города: «далі на Нікопольщину» значит «уходит». */
    private static final String[] REGIONS = {"нікопольщин", "нікопольськ", "херсонщин",
            "миколаївщин", "кіровоградщин", "запоріжж", "кам'янського район", "кам'янський район",
            "полтавщин", "черкащин"};
    private static final Pattern FROM_CITY = Pattern.compile(
            "(?:від|повз|біля|північніше|південніше|східніше|західніше|район[іу])\\s+$");

    /** Разбирает одно сообщение. */
    /** Слова «мой район» (через запятую) -> шаблон; короткие «цм», «цг» ищутся как отдельные слова. */
    static Pattern minePattern(String csv) {
        StringBuilder sb = new StringBuilder();
        for (String w : Threats.norm(csv).split("[,\\n]")) {
            w = w.trim();
            if (w.length() < 2) continue;
            if (sb.length() > 0) sb.append('|');
            String q = Pattern.quote(w).replace(" ", "\\E\\s*-?\\s*\\Q").replace("-", "\\E\\s*-?\\s*\\Q");
            // короткое сокращение — только отдельным словом («цг», но не «цгзк»);
            // длинное — как начало слова с любым окончанием
            sb.append(w.length() <= 3 ? q + "(?![а-яіїєґa-z0-9])" : q);
        }
        if (sb.length() == 0) return null;
        return Pattern.compile("(?<![а-яіїєґa-z0-9'])(?:" + sb + ")");
    }

    static Obs observe(Threats.Item it, Pattern mine) {
        Obs o = new Obs();
        o.timeMs = it.timeMs;
        o.source = it.source;
        o.text = it.text;
        // ответ вида «На місто. ← …в напрямку Кривого Рогу»: смысл в первой части
        String raw = it.text;
        int arrow = raw.indexOf(" ← ");
        String n = Threats.norm(arrow >= 0 ? raw.substring(0, arrow) : raw);
        String whole = Threats.norm(raw);

        String type = it.type;
        o.ballistic = type.contains("Баллистика");
        if (o.ballistic) {
            o.vMin = 3000;
            o.vMax = 6000;
        } else if (type.contains("Ракета")) {
            o.vMin = 650;
            o.vMax = 900;
        } else if (type.contains("Реактивный") || whole.contains("реактив")) {
            o.vMin = 300;
            o.vMax = 450;
        } else {
            o.vMin = 120;
            o.vMax = 190;
        }

        boolean away = false;
        for (String a : AWAY) if (n.contains(a)) away = true;

        Matcher dm = DEST.matcher(n);
        int destAt = dm.find() ? dm.start() : Integer.MAX_VALUE;

        if (destAt != Integer.MAX_VALUE) {
            String tail = n.substring(destAt);
            for (String rg : REGIONS) if (tail.contains(rg)) away = true;
        }

        List<Geo.Hit> hits = Geo.find(n);
        boolean cityHere = false, cityDest = false, cityNear = false;
        Geo.Place pos = null, dest = null;
        StringBuilder named = new StringBuilder();
        for (Geo.Hit h : hits) {
            boolean isDest = h.at > destAt;
            if (h.place.inCity && h.place != Geo.PLACES.get(0) && named.indexOf(h.place.name) < 0) {
                named.append(named.length() > 0 ? ", " : "").append(h.place.name);
            }
            if (h.place.inCity) {
                String before = n.substring(0, h.at);
                if (FROM_CITY.matcher(before).find()) cityNear = true;
                else if (isDest) cityDest = true;
                else cityHere = true;
            } else if (isDest) {
                if (dest == null) dest = h.place;
            } else if (pos == null || h.place.km < pos.km) {
                pos = h.place; // из нескольких названных мест берём ближайшее к городу
            }
        }
        o.pos = pos;
        o.districts = named.toString();
        if (mine != null) {
            Matcher mm = mine.matcher(n);
            if (mm.find()) {
                o.mine = true;
                // «мой район» — это тоже город, даже если его нет в справочнике
                String before = n.substring(0, mm.start());
                if (FROM_CITY.matcher(before).find()) cityNear = true;
                else if (mm.start() > destAt) cityDest = true;
                else cityHere = true;
            }
        }

        if (away && !cityDest) {
            // «чисто», «вилетів», «пішов на …» — цель уходит
            o.level = (cityHere || cityNear || pos != null || dest != null
                    || n.contains("чисто") || n.contains("без загроз")) ? GONE : NONE;
            // «Без загроз. Софіївка Широке» — место названо, но угрозы городу нет
        } else if (cityHere) {
            o.level = o.mine ? MINE : OVER;
        } else if (cityDest) {
            o.level = TOWARD;
        } else if (cityNear) {
            o.level = NEAR;
        } else if (dest != null) {
            // курс на место вплотную к городу считаем курсом на город
            o.level = dest.km <= 20 ? TOWARD : (pos != null && pos.km <= NEAR_KM) || dest.km <= NEAR_KM
                    ? NEAR : NONE;
            if (o.pos == null && o.level == NEAR) o.pos = dest;
        } else if (pos != null) {
            o.level = pos.km <= NEAR_KM ? NEAR : NONE;
        } else if (o.ballistic && n.contains("загроза")) {
            o.level = NEAR; // общая угроза баллистики без места
        }
        return o;
    }

    /** Сколько минут наблюдение остаётся в силе, если его не сменило более свежее. */
    private static double holdMin(Obs o) {
        switch (o.level) {
            case MINE:
            case OVER: return 5;
            case TOWARD:
                if (o.ballistic) return 5;
                if (o.pos == null) return 8;
                return Math.min(20, o.pos.km / o.vMin * 60 + 3);
            case NEAR: return 7;
            default: return 12;
        }
    }

    /**
     * Сводит сообщения последних минут в один вывод. items — в любом порядке,
     * alertActive — идёт ли сейчас тревога.
     *
     * Правило: действует самое свежее осмысленное сообщение. Более тревожное
     * сообщение держится своё время (подлёта), и понизить его раньше срока
     * может только тот же канал, который его дал.
     */
    static Result evaluate(List<Threats.Item> items, long now, boolean alertActive, Pattern mine) {
        items.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        Obs cur = null;
        long curUntil = 0;
        String trend = "";
        Map<String, Obs> lastPos = new HashMap<>();
        for (Threats.Item it : items) {
            if (now - it.timeMs > 30 * 60_000 || it.timeMs > now + 60_000) continue;
            Obs o = observe(it, mine);
            if (o.level == NONE) continue;

            // тенденция: тот же канал до этого называл другое место
            String tr = "";
            Obs pv = lastPos.get(o.source);
            if (o.pos != null && pv != null && pv.pos != o.pos && o.timeMs - pv.timeMs < 10 * 60_000) {
                double diff = pv.pos.km - o.pos.km;
                if (diff >= 8) tr = "приближается";
                else if (diff <= -8) tr = "удаляется";
            }
            if (o.pos != null && o.level != GONE) lastPos.put(o.source, o);
            if (o.level == NEAR && tr.equals("приближается") && o.pos.km <= 35) o.level = TOWARD;

            boolean replace = cur == null || o.level >= cur.level || o.source.equals(cur.source)
                    || o.timeMs >= curUntil;
            if (replace) {
                cur = o;
                curUntil = o.timeMs + (long) (holdMin(o) * 60_000);
                trend = tr;
            }
        }

        Result r = new Result();
        if (cur == null || now > curUntil) {
            r.title = alertActive ? "Свежих сообщений о целях нет" : "Спокойно";
            if (cur != null) r.basis = Data.hm(cur.timeMs) + " · " + cur.source + ": " + cur.text;
            if (alertActive) r.detail = "Тревога не снята — оставайтесь в укрытии до отбоя.";
            return r;
        }
        Obs best = cur;
        int level = best.level;

        r.level = level;
        r.timeMs = best.timeMs;
        r.basis = Data.hm(best.timeMs) + " · " + best.source + ": " + best.text;
        long ageMin = Math.max(0, (now - best.timeMs) / 60_000);
        String where = best.pos == null ? "" : best.pos.name + " — " + Math.round(best.pos.km)
                + " км " + best.pos.side + " от города";

        switch (level) {
            case MINE:
                r.title = "🔴 НАД ВАШИМ РАЙОНОМ";
                r.detail = "Названо: " + (best.districts.isEmpty() ? "ваш район" : best.districts)
                        + (ageMin > 0 ? " (" + ageMin + " мин назад)" : "");
                break;
            case OVER:
                r.title = best.districts.isEmpty() ? "🔴 Над городом" : "🔴 Над городом · не ваш район";
                r.detail = (best.districts.isEmpty() ? "Цель в черте Кривого Рога, район не назван"
                        : "Названо: " + best.districts)
                        + (ageMin > 0 ? " (" + ageMin + " мин назад)" : "");
                break;
            case TOWARD:
                r.title = best.mine ? "🟠 Летит НА ВАШ РАЙОН" : "🟠 Летит к Кривому Рогу";
                if (best.ballistic) {
                    r.eta = "подлёт 1–3 мин";
                } else if (best.pos != null) {
                    double elapsed = (now - best.timeMs) / 60_000.0;
                    long lo = Math.round(best.pos.km / best.vMax * 60 - elapsed);
                    long hi = Math.round(best.pos.km / best.vMin * 60 - elapsed);
                    r.eta = hi <= 0 ? "уже может быть над городом"
                            : lo <= 0 ? "в ближайшие " + hi + " мин"
                            : "≈ через " + lo + "–" + hi + " мин";
                } else {
                    r.eta = "время подлёта неизвестно";
                }
                r.detail = where.isEmpty() ? "Где цель сейчас, каналы не назвали" : where;
                break;
            case NEAR:
                r.title = "🟡 Рядом, курс не на город";
                r.detail = (where.isEmpty() ? (best.ballistic ? "Общая угроза баллистики"
                        : "Проходит мимо города") : where)
                        + (trend.isEmpty() ? "" : " · " + trend);
                break;
            default:
                r.title = "🟢 По последним сообщениям ушло";
                r.detail = alertActive ? "Тревога не снята — оставайтесь в укрытии до отбоя." : "";
        }
        return r;
    }

    private Forecast() {
    }
}
