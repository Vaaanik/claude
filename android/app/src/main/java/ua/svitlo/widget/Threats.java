package ua.svitlo.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Отбор сообщений об угрозах (ракеты, БпЛА, КАБы) для своего города и района
 * из лент Telegram-каналов и сжатие их до сути. Без зависимостей от Android.
 */
final class Threats {
    /**
     * Каналы по умолчанию (через запятую). Звёздочка после имени — местный канал:
     * в нём всё про наш город, поэтому название города в сообщении не требуется.
     */
    static final String DEFAULT_CHANNELS = "kpszsu, vilkul*, IMS_KR_TM*, oborona_kr*";

    /** Основы слов, по которым сообщение считается относящимся к городу. */
    static final String DEFAULT_CITY = "кривий ріг, кривого рогу, кривому розі, кривим рогом, "
            + "криворі, кривбас";

    /**
     * Основы слов для района и ближайших подступов. Знак «?» перед словом значит:
     * учитывать только если в сообщении названа Днепропетровщина (у слова есть
     * тёзки в других областях).
     */
    static final String DEFAULT_AREA = "апостолов, зеленодольськ, радушн, грушівк, карпівк, "
            + "девладів, глеюват, лозуват, ?софіївк, ?широк, п'ятихат, жовті води, жовтих вод, "
            + "божедарівк, долинськ, казанк, новий буг, нового бугу, петрове";

    /** Общая угроза без названия места — касается всех. */
    private static final String[] GENERAL = {"балістичного озброєння"};

    /** Слова, по которым короткий пост местного канала считается предупреждением. */
    private static final String[] LOCAL = {"атак", "удар", "вибух", "бпла", "шахед", "мопед",
            "реактив", "ракет", "баліст", "бандерол", "у повітрі", "загроз", "чисто", "курс",
            "уважно", "увага", "пішов", "попер", "вилетів", "розворот", "доворот", "відвернув",
            "світлішаємо", "червоніємо", "з півн", "з півд", "зі сход", "із заход", "з заход",
            "на схід", "на захід", "на північ", "на південь", "🛵", "🏍", "🚀",
            "тернівськ", "покровськ", "саксаганськ", "довгинцівськ", "металургійн",
            "центрально", "інгулец", "рокуват", "даманськ", "гзк", "нікопольщин",
            "нікопольськ", "херсонщин", "миколаївщин", "кіровоградщин"};

    /** Реклама, сборы, розыгрыши и дубли сирены в местных каналах. */
    private static final String[] LOCAL_NOISE = {"розіграш", "переможц", "підтрим", "реклам",
            "pinned", "графік", "whatsapp", "брелок", "повітряна тривога", "повітряної тривоги",
            "🔠"};

    /** С этих слов начинается хвост-подпись, его отрезаем. */
    private static final String[] FOOTER = {"✉️", "підписатись", "підписатися", "написати нам",
            "скинути фото", "повідомити", "📞"};
    private static final int LOCAL_MAX_LEN = 300;

    /** Слова «мой район» по умолчанию: Центрально-Городской район. */
    static final String DEFAULT_MINE = "цм, цг, центрально-міськ, центрально-город, центр міста, "
            + "гданцівк, карачун";

    /** Точка «дом» по умолчанию: центр Центрально-Городского района. */
    static final double HOME_LAT = 47.905, HOME_LON = 33.343;

    private static final String OBLAST = "дніпропетровщ";

    static final class Item {
        String source;   // имя канала
        long id;         // номер сообщения
        long timeMs;
        boolean city;    // упомянут сам город (иначе — район/подступы)
        boolean local;   // из местного канала
        String type;     // "🛵 БпЛА", "🚀 Ракета" …
        String text;     // суть: только фразы про наши места

        String key() {
            return source + "/" + id;
        }
    }

    static String[] stems(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.toLowerCase(Locale.ROOT).split("[,\\n]")) {
            s = norm(s).trim();
            if (s.replace("?", "").length() >= 3) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /** Нижний регистр и единый апостроф, чтобы основы совпадали с любым написанием. */
    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('’', '\'').replace('ʼ', '\'').replace('`', '\'');
    }

    private static final Pattern KAB = Pattern.compile("(?<![а-яіїєґ])каб(и|ів|ами|у|а)?(?![а-яіїєґ])");

    /** Что летит — по словам в тексте. */
    static String type(String text) {
        String s = norm(text);
        if (s.contains("відбій")) return "✅ Отбой";
        if (s.contains("чисто") || s.contains("без загроз")) return "✅ Чисто";
        if (s.contains("балісти") || s.contains("швидкісн")) return "🚀 Баллистика";
        if (s.contains("ракет") || s.contains("крилат") || s.contains("бандерол")) return "🚀 Ракета";
        if (KAB.matcher(s).find() || s.contains("авіаційних бомб") || s.contains("авіабомб")
                || s.contains("засобів ураження")) return "💣 КАБ";
        if (s.contains("реактив")) return "🏍 Реактивный БпЛА";
        if (s.contains("бпла") || s.contains("шахед") || s.contains("shahed") || s.contains("ударн")
                || s.contains("дрон") || s.contains("мопед") || s.contains("безпілотник")
                || s.contains("🛵")) return "🛵 БпЛА";
        if (s.contains("тактичної авіації") || s.contains("міг-31")) return "✈️ Авиация";
        if (s.contains("вибух")) return "💥 Взрывы";
        return "⚠️ Обстановка";
    }

    /** Сводки, статистика и благодарности — не оперативные предупреждения. */
    static boolean noise(String text) {
        String s = norm(text);
        return text.startsWith("⚡") || text.startsWith("❗") || s.contains("збито/подавлено")
                || s.contains("противник атакував") || s.contains("дякуємо") || s.contains("➖➖") || s.contains("на ранок");
    }

    /** Есть ли в строке одно из слов; слова с «?» считаются только при contextOk. */
    private static boolean hasAny(String normText, String[] stems, boolean contextOk) {
        for (String st : stems) {
            if (st.startsWith("?")) {
                if (contextOk && normText.contains(st.substring(1))) return true;
            } else if (normText.contains(st)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Возвращает суть сообщения, если оно про наши места, иначе null.
     * Из сообщения берутся только строки/фразы с нашими названиями. Короткие
     * ответы вроде «На місто.» понимаются по сообщению, на которое отвечают.
     */
    static Item extract(String source, boolean local, Data.Raw post, String[] city, String[] area,
            int maxLen) {
        if (local) return extractLocal(source, post, city, area);
        String text = post.text;
        if (text == null || text.isEmpty() || text.length() > maxLen || noise(text)) return null;
        String all = norm(text);
        String parent = norm(post.reply == null ? "" : post.reply);
        boolean oblast = all.contains(OBLAST) || parent.contains(OBLAST);

        boolean isCity = hasAny(all, city, oblast);
        boolean own = isCity || hasAny(all, area, oblast) || hasAny(all, GENERAL, false);
        // ответ наследует место только от сообщения про сам город
        boolean viaParent = !own && hasAny(parent, city, oblast);
        if (!own && !viaParent) return null;

        String essence;
        if (own) {
            StringBuilder sb = new StringBuilder();
            for (String part : text.split("\\n+|(?<=[.!?])\\s+")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                String n = norm(p);
                if (hasAny(n, city, oblast) || hasAny(n, area, oblast) || hasAny(n, GENERAL, false)) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(p);
                }
            }
            essence = sb.toString();
        } else {
            // уточнение к предыдущему сообщению про наши места
            isCity = true;
            essence = clean(text) + " ← " + clean(post.reply);
        }
        essence = clean(essence);
        if (essence.length() > 200) essence = essence.substring(0, 197) + "…";

        Item it = new Item();
        it.source = source;
        it.id = post.id;
        it.timeMs = post.timeMs;
        it.city = isCity;
        String t = type(essence);
        it.type = t.startsWith("⚠️") ? type(text) : t;
        it.text = essence;
        return it;
    }

    /**
     * Местный канал: берём короткие посты об угрозе целиком, без хвостов-подписей,
     * номеров карт и рекламы.
     */
    private static Item extractLocal(String source, Data.Raw post, String[] city, String[] area) {
        if (post.text == null || post.text.isEmpty() || noise(post.text)) return null;
        String text = post.text;
        String low = norm(text);
        if (hasAny(low, LOCAL_NOISE, false)) return null;
        int cut = text.length();
        for (String f : FOOTER) {
            int i = low.indexOf(f);
            // norm() не меняет длину строки, поэтому индексы совпадают
            if (i >= 0 && i < cut) cut = i;
        }
        String essence = text.substring(0, cut)
                .replaceAll("\\d{16}", " ").replace("🏦", " ")
                .replaceAll("\\s*\\n+\\s*", ". ").replaceAll("\\s+", " ").trim();
        essence = essence.replaceFirst("^[.\\s]+", "").replaceFirst("[.\\s]+$", "");
        if (essence.length() < 4 || essence.length() > LOCAL_MAX_LEN) return null;
        String n = norm(essence);
        if (!hasAny(n, LOCAL, false) && !hasAny(n, city, true) && !hasAny(n, area, true)
                && Geo.find(n).isEmpty()) return null;
        Item it = new Item();
        it.source = source;
        it.id = post.id;
        it.timeMs = post.timeMs;
        it.city = true;
        it.local = true;
        it.type = type(essence);
        it.text = essence;
        return it;
    }

    /** Одна строка без украшений в начале (эмодзи, восклицательные знаки). */
    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").trim().replaceFirst("^[^\\p{L}\\p{N}]+", "");
    }

    private Threats() {
    }
}
