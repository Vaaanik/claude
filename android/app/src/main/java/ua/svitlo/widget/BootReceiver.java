package ua.svitlo.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Запускает слежение после перезагрузки телефона и после обновления приложения. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            MonitorService.start(c);
        } catch (Exception ignored) {
            // Система не разрешила запуск из фона — сервис стартует при открытии приложения.
        }
    }
}
