package ua.svitlo.widget;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Оценка «летит ли к Кривому Рогу, к моему району и когда будет» по цепочке
 * сообщений всех каналов. Расстояния и время считаются до точки «дом».
 * Это грубая оценка по названиям мест в сообщениях, а не данные радаров.
 */
final class Forecast {
    static final int NONE = 0, GONE = 1, NEAR = 2, TOWARD = 3, OVER = 4, MINE = 5;

    /** Виды уведомлений: только то, что опасно именно здесь. */
    static final int N_NONE = 0, N_MISSILE = 1, N_TOWARD_MINE = 2, N_OVER_MINE = 3;

    /** Дальше этого от города «рядом» не считается. */
    private static final double NEAR_KM = 70;
    /** Место ближе этого к дому считается «моим районом», даже если район не назван. */
    private static final double HOME_KM = 2.5;

    /** Точка «дом» и слова «мой район». */
    static final class Home {
        final double lat, lon;
        final Pattern mine;

        Home(double lat, double lon, Pattern mine) {
            this.lat = lat;
            this.lon = lon;
            this.mine = mine;
        }

        double km(Geo.Place p) {
            return Geo.distance(lat, lon, p.lat, p.lon);
        }
    }

    /** Что понято из одного сообщения. */
    static final class Obs {
        int level = NONE;
        Geo.Place at;         // где цель сейчас: ближайшее к дому из названных мест (может быть null)
        boolean ballistic, missile;
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
        String detail = "";   // место, расстояние до дома, тенденция
        String basis = "";    // сообщение, на котором основан вывод
        long timeMs;
        int notify = N_NONE;  // нужно ли уведомление и какое
        String key = "";      // какое сообщение дало вывод (чтобы не уведомлять дважды)
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

    /** Слова «мой район» (через запятую) -> шаблон; короткие «цм», «цг» ищутся как отдельные слова. */
    static Pattern minePattern(String csv) {
        StringBuilder sb = new StringBuilder();
        for (String w : Threats.norm(csv).split("[,\\n]")) {
            w = w.trim();
            if (w.length() < 2) continue;
            // «центрально-міськ» должно ловить и «центрально міськ», и «центрально - міськ»
            StringBuilder q = new StringBuilder();
            for (String part : w.split("[\\s-]+")) {
                if (part.isEmpty()) continue;
                if (q.length() > 0) q.append("\\s*-?\\s*");
                q.append(Pattern.quote(part));
            }
            if (q.length() == 0) continue;
            if (sb.length() > 0) sb.append('|');
            // короткое сокращение — только отдельным словом («цг», но не «цгзк»);
            // длинное — как начало слова с любым окончанием
            sb.append(w.length() <= 3 ? q + "(?![а-яіїєґa-z0-9])" : q.toString());
        }
        if (sb.length() == 0) return null;
        try {
            return Pattern.compile("(?<![а-яіїєґa-z0-9'])(?:" + sb + ")");
        } catch (Exception e) {
            return null; // странный ввод в настройках не должен ронять приложение
        }
    }

    /** Разбирает одно сообщение. */
    static Obs observe(Threats.Item it, Home home) {
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
        o.missile = o.ballistic || type.contains("Ракета") || type.contains("КАБ");
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
        Geo.Place pos = null, dest = null, at = null;
        StringBuilder named = new StringBuilder();
        for (Geo.Hit h : hits) {
            boolean isDest = h.at > destAt;
            boolean hasPoint = !Double.isNaN(h.place.lat);
            if (h.place.inCity) {
                if (hasPoint && named.indexOf(h.place.name) < 0) {
                    named.append(named.length() > 0 ? ", " : "").append(h.place.name);
                }
                String before = n.substring(0, h.at);
                if (FROM_CITY.matcher(before).find()) cityNear = true;
                else if (isDest) cityDest = true;
                else cityHere = true;
            } else if (isDest) {
                if (dest == null) dest = h.place;
            } else if (pos == null || h.place.km < pos.km) {
                pos = h.place; // из нескольких мест вне города берём ближайшее к городу
            }
            // текущее место цели — ближайшее к дому из названных «здесь»
            if (!isDest && hasPoint && (at == null || home.km(h.place) < home.km(at))) at = h.place;
        }
        o.at = at;
        o.districts = named.toString();

        // «мой район»: назван словом или названо место вплотную к дому
        boolean mineHere = false, mineDest = false;
        if (home.mine != null) {
            Matcher mm = home.mine.matcher(n);
            if (mm.find()) {
                String before = n.substring(0, mm.start());
                if (FROM_CITY.matcher(before).find()) cityNear = true;
                else if (mm.start() > destAt) mineDest = true;
                else mineHere = true;
            }
        }
        for (Geo.Hit h : hits) {
            if (Double.isNaN(h.place.lat) || home.km(h.place) > HOME_KM) continue;
            if (h.at > destAt) mineDest = true;
            else mineHere = true;
        }
        if (mineHere) cityHere = true;
        if (mineDest) cityDest = true;
        o.mine = mineHere || mineDest;

        if (away && !cityDest) {
            // «чисто», «вилетів», «пішов на …» — цель уходит
            o.level = (cityHere || cityNear || pos != null || dest != null
                    || n.contains("чисто") || n.contains("без загроз")) ? GONE : NONE;
        } else if (mineHere) {
            o.level = MINE;
        } else if (cityHere) {
            o.level = OVER;
        } else if (cityDest) {
            o.level = TOWARD;
        } else if (cityNear) {
            o.level = NEAR;
        } else if (dest != null) {
            // курс на место вплотную к городу считаем курсом на город
            o.level = dest.km <= 20 ? TOWARD : (pos != null && pos.km <= NEAR_KM) || dest.km <= NEAR_KM
                    ? NEAR : NONE;
            if (o.at == null && o.level == NEAR) o.at = dest;
        } else if (pos != null) {
            o.level = pos.km <= NEAR_KM ? NEAR : NONE;
        } else if (o.ballistic && n.contains("загроза")) {
            o.level = NEAR; // общая угроза баллистики без места
        }
        return o;
    }

    /** Сколько минут наблюдение остаётся в силе, если его не сменило более свежее. */
    private static double holdMin(Obs o, Home home) {
        switch (o.level) {
            case MINE:
            case OVER: return 5;
            case TOWARD:
                if (o.ballistic) return 5;
                if (o.at == null) return 8;
                return Math.min(20, home.km(o.at) / o.vMin * 60 + 3);
            case NEAR: return 7;
            default: return 12;
        }
    }

    /**
     * Сводит сообщения всех каналов за последние минуты в один вывод.
     *
     * Правило: действует самое свежее осмысленное сообщение. Более тревожное
     * сообщение держится своё время (подлёта), и понизить его раньше срока
     * может только тот же канал, который его дал.
     */
    static Result evaluate(List<Threats.Item> items, long now, boolean alertActive, Home home) {
        items.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        Obs cur = null;
        long curUntil = 0;
        String trend = "";
        Obs lastAt = null; // последнее названное место по всем каналам — для тенденции
        for (Threats.Item it : items) {
            if (now - it.timeMs > 30 * 60_000 || it.timeMs > now + 60_000) continue;
            Obs o = observe(it, home);
            if (o.level == NONE) continue;

            // тенденция относительно дома: предыдущее место было дальше или ближе
            String tr = "";
            if (o.at != null && o.level != GONE) {
                if (lastAt != null && lastAt.at != o.at && o.timeMs - lastAt.timeMs < 8 * 60_000) {
                    double was = home.km(lastAt.at), is = home.km(o.at);
                    double step = Math.max(2, was * 0.2); // в городе значим и шаг в пару км
                    if (was - is >= step) tr = "приближается к вам";
                    else if (is - was >= step) tr = "удаляется от вас";
                }
                lastAt = o;
            }
            if (o.level == NEAR && tr.startsWith("прибл") && home.km(o.at) <= 35) o.level = TOWARD;

            boolean replace = cur == null || o.level >= cur.level || o.source.equals(cur.source)
                    || o.timeMs >= curUntil;
            if (replace) {
                cur = o;
                curUntil = o.timeMs + (long) (holdMin(o, home) * 60_000);
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
        r.level = best.level;
        r.timeMs = best.timeMs;
        r.key = best.source + "/" + best.timeMs;
        r.basis = Data.hm(best.timeMs) + " · " + best.source + ": " + best.text;
        long ageMin = Math.max(0, (now - best.timeMs) / 60_000);
        String age = ageMin > 0 ? " (" + ageMin + " мин назад)" : "";
        double elapsed = (now - best.timeMs) / 60_000.0;

        // где цель и сколько до дома
        String where = "";
        String ifTurns = "";
        if (best.at != null) {
            double km = home.km(best.at);
            where = best.at.name + " — " + fmtKm(km) + " "
                    + Geo.side(home.lat, home.lon, best.at.lat, best.at.lon) + " от вас";
            ifTurns = eta(km, best, elapsed);
        }
        String tr = trend.isEmpty() ? "" : " · " + trend;

        switch (best.level) {
            case MINE:
                r.title = "🔴 НАД ВАШИМ РАЙОНОМ";
                r.detail = (where.isEmpty() ? "Назван ваш район" : where) + age;
                r.notify = N_OVER_MINE;
                break;
            case OVER:
                r.title = best.districts.isEmpty() ? "🔴 Над городом" : "🔴 Над городом · не ваш район";
                r.detail = (where.isEmpty() ? "Район не назван — где именно, неизвестно" : where)
                        + tr + age;
                if (!ifTurns.isEmpty()) r.eta = "если повернёт к вам: " + ifTurns;
                if (best.missile) r.notify = N_MISSILE;
                break;
            case TOWARD:
                r.title = best.mine ? "🟠 Летит НА ВАШ РАЙОН" : "🟠 Летит к Кривому Рогу";
                r.eta = best.ballistic ? "подлёт 1–3 мин"
                        : ifTurns.isEmpty() ? "время подлёта неизвестно" : "до вас " + ifTurns;
                r.detail = (where.isEmpty() ? "Где цель сейчас, каналы не назвали" : where) + tr;
                r.notify = best.missile ? N_MISSILE : best.mine ? N_TOWARD_MINE : N_NONE;
                break;
            case NEAR:
                r.title = "🟡 Рядом, курс не на город";
                r.detail = (where.isEmpty() ? (best.ballistic ? "Общая угроза баллистики"
                        : "Проходит мимо города") : where) + tr;
                break;
            default:
                r.title = "🟢 По последним сообщениям ушло";
                r.detail = alertActive ? "Тревога не снята — оставайтесь в укрытии до отбоя." : "";
        }
        return r;
    }

    /** Время подлёта до дома при расстоянии km, с учётом прошедшего времени. */
    private static String eta(double km, Obs o, double elapsedMin) {
        if (o.ballistic) return "1–3 мин";
        long lo = Math.round(km / o.vMax * 60 - elapsedMin);
        long hi = Math.round(km / o.vMin * 60 - elapsedMin);
        if (hi <= 0) return "уже может быть рядом";
        if (hi <= 1) return "меньше минуты";
        if (lo <= 0) return "в ближайшие " + hi + " мин";
        return lo == hi ? "≈ " + lo + " мин" : "≈ " + lo + "–" + hi + " мин";
    }

    private static String fmtKm(double km) {
        return km < 10 ? String.format(java.util.Locale.ROOT, "%.1f", km).replace('.', ',') + " км"
                : Math.round(km) + " км";
    }

    private Forecast() {
    }
}
