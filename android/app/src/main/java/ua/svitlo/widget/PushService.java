package ua.svitlo.widget;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Принимает сообщения сервера: новые посты каналов и «я жив». */
public class PushService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage m) {
        try {
            if (!Push.serverMode(this)) return;
            Map<String, String> d = m.getData();
            long now = System.currentTimeMillis();
            Store.p(this).edit().putLong("contact", now).putLong("alertChecked", now)
                    .putLong("pushAt", now).putBoolean("serverAlive", true).apply();
            if ("post".equals(d.get("t"))) {
                Data.Raw r = new Data.Raw();
                r.id = Long.parseLong(d.get("id"));
                r.timeMs = Long.parseLong(d.get("ts"));
                r.text = d.get("text") == null ? "" : d.get("text");
                r.reply = d.get("reply") == null ? "" : d.get("reply");
                String ch = d.get("ch");
                if (ch != null && ch.equalsIgnoreCase(Store.channel(this))) Work.sirenPost(this, r);
                if (ch != null) Work.threatPost(this, ch, r);
            }
            Work.afterChange(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNewToken(String token) {
        // рассылка идёт по теме, отдельные токены серверу не нужны; подписку обновляем
        try {
            if (Push.serverMode(this)) Push.apply(this);
        } catch (Exception ignored) {
        }
    }
}
