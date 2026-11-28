package ua.svitlo.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * После перезагрузки телефона и после обновления приложения включает слежение;
 * по будильнику — присылает напоминание о свете.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_REMIND = "ua.svitlo.widget.REMIND";

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (ACTION_REMIND.equals(i.getAction())) {
                final PendingResult pending = goAsync();
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    try {
                        Work.remind(app);
                        Work.scheduleReminder(app);
                        StatusWidget.render(app);
                    } finally {
                        pending.finish();
                    }
                }).start();
            } else {
                Store.migrate(c);
                Push.apply(c);
            }
        } catch (Exception ignored) {
        }
    }
}
