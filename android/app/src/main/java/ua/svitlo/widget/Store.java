package ua.svitlo.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.ZonedDateTime;
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
        return p(c).getString("group", "4.2");
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

    private Store() {
    }
}
