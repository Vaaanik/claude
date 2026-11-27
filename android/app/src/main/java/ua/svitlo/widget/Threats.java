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
    /** Каналы по умолчанию (через запятую). */
    static final String DEFAULT_CHANNELS = "kpszsu";

    /** Основы слов, по которым сообщение считается относящимся к городу. */
    static final String DEFAULT_CITY = "кривий ріг, кривого рогу, кривому розі, кривим рогом, "
            + "криворіж, кривбас, кривий рог, кривого рога";

    /** Основы слов для района и ближайших подступов. */
    static final String DEFAULT_AREA = "апостолов, зеленодольськ, софіївк, широк, інгулец, "
            + "п'ятихат, жовті води, жовтих вод, нікополь, нікопол, марганец, покров";

    static final class Item {
        String source;   // имя канала
        long id;         // номер сообщения
        long timeMs;
        boolean city;    // упомянут сам город (иначе — район/подступы)
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
            if (s.length() >= 3) out.add(s);
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
        if (s.contains("балісти")) return "🚀 Баллистика";
        if (s.contains("ракет") || s.contains("крилат")) return "🚀 Ракета";
        if (KAB.matcher(s).find() || s.contains("авіабомб") || s.contains("керованих бомб")) return "💣 КАБ";
        if (s.contains("бпла") || s.contains("шахед") || s.contains("shahed") || s.contains("ударн")
                || s.contains("дрон") || s.contains("мопед") || s.contains("безпілотник")) return "🛵 БпЛА";
        if (s.contains("міг-31") || s.contains("миг-31")) return "✈️ МиГ-31К";
        if (s.contains("вибух")) return "💥 Взрывы";
        return "⚠️ Угроза";
    }

    private static boolean hasAny(String normText, String[] stems) {
        for (String st : stems) if (normText.contains(st)) return true;
        return false;
    }

    /**
     * Возвращает суть сообщения, если оно про наши места, иначе null.
     * Из длинного сообщения берутся только строки/фразы с нашими названиями.
     */
    static Item extract(String source, Data.Raw post, String[] city, String[] area, int maxLen) {
        String text = post.text;
        if (text == null || text.isEmpty() || text.length() > maxLen) return null;
        String all = norm(text);
        boolean isCity = hasAny(all, city);
        if (!isCity && !hasAny(all, area)) return null;

        StringBuilder sb = new StringBuilder();
        for (String part : text.split("\\n+|(?<=[.!?])\\s+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String n = norm(p);
            if (hasAny(n, city) || hasAny(n, area)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p);
            }
        }
        String essence = sb.toString().replaceAll("\\s+", " ").trim();
        // убираем украшения в начале: эмодзи, восклицательные знаки
        essence = essence.replaceFirst("^[^\\p{L}\\p{N}]+", "");
        if (essence.length() > 180) essence = essence.substring(0, 177) + "…";

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

    private Threats() {
    }
}
