package ua.svitlo.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Берёт график прямо с официального сайта ДТЭК Днепровские электросети.
 *
 * Сайт отдаёт данные только настоящему браузеру (защита от ботов), поэтому
 * страница открывается в невидимом WebView, и из неё читается объект
 * DisconSchedule.fact — тот же, по которому сайт рисует таблицу графика.
 */
final class DtekFetcher {
    interface Callback {
        /** fact — график, либо null и текст ошибки. Вызывается в главном потоке. */
        void done(JSONObject fact, String error);
    }

    private static final String JS =
            "(function(){try{return (typeof DisconSchedule!=='undefined'&&DisconSchedule.fact)"
                    + "?JSON.stringify(DisconSchedule.fact):'';}catch(e){return '';}})()";
    private static final long TIMEOUT_MS = 45000;
    private static final long POLL_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private Callback cb;
    private long started;

    /** Вызывать из главного потока. */
    @SuppressLint("SetJavaScriptEnabled")
    void fetch(Context c, Callback callback) {
        if (web != null) return; // уже идёт
        cb = callback;
        started = System.currentTimeMillis();
        try {
            web = new WebView(c.getApplicationContext());
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setBlockNetworkImage(true);
            web.setWebViewClient(new WebViewClient());
            web.loadUrl(Data.DTEK_URL);
            main.postDelayed(this::poll, POLL_MS);
        } catch (Throwable t) {
            finish(null, "WebView недоступен");
        }
    }

    private void poll() {
        if (web == null) return;
        web.evaluateJavascript(JS, value -> {
            if (web == null) return;
            try {
                // evaluateJavascript возвращает строку в JSON-кавычках
                Object v = new JSONTokener(value).nextValue();
                if (v instanceof String && !((String) v).isEmpty()) {
                    JSONObject fact = new JSONObject((String) v);
                    if (fact.optJSONObject("data") != null) {
                        finish(fact, null);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
            if (System.currentTimeMillis() - started > TIMEOUT_MS) {
                finish(null, "сайт ДТЭК не ответил");
            } else {
                main.postDelayed(this::poll, POLL_MS);
            }
        });
    }

    private void finish(JSONObject fact, String error) {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        Callback c = cb;
        cb = null;
        if (c != null) c.done(fact, error);
    }
}
