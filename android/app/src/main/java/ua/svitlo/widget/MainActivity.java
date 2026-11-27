package ua.svitlo.widget;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Arrays;

/** Настройки: область для тревоги и группа отключений. */
public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences p = StatusWidget.prefs(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setFitsSystemWindows(true);

        box.addView(label("Область (воздушная тревога)"));
        final Spinner region = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, Data.REGIONS);
        region.setAdapter(ad);
        region.setSelection(Math.max(0,
                Arrays.asList(Data.REGIONS).indexOf(p.getString("region", Data.REGIONS[0]))));
        box.addView(region);

        box.addView(label("\nГруппа отключений ДТЭК (Днепр и область), например 4.1"));
        final EditText group = new EditText(this);
        group.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        group.setText(p.getString("group", "4.2"));
        box.addView(group);

        Button save = new Button(this);
        save.setText("Сохранить и обновить");
        box.addView(save);

        status = label("");
        status.setTextSize(16);
        box.addView(status);

        box.addView(label("\nЧтобы добавить виджет: долгое нажатие на пустом месте "
                + "главного экрана → Виджеты → «Тревога и свет».\n"
                + "Виджет обновляется раз в пару минут, пока экран включён; "
                + "нажатие на виджет обновляет его сразу."));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        save.setOnClickListener(v -> {
            p.edit()
                    .putString("region", (String) region.getSelectedItem())
                    .putString("group", group.getText().toString().trim().replace(',', '.'))
                    .apply();
            load();
        });
        load();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        return t;
    }

    /** Показывает текущий статус в приложении и обновляет виджеты. */
    private void load() {
        status.setText("\nЗагрузка…");
        final SharedPreferences p = StatusWidget.prefs(this);
        new Thread(() -> {
            String region = p.getString("region", Data.REGIONS[0]);
            Data.Alert a = Data.alert(region);
            String power = Data.power(p.getString("group", "4.2"));
            final String text = "\n" + (a.active == null ? "Тревога: нет связи"
                    : a.active ? "🚨 ТРЕВОГА · " + region + (a.since.isEmpty() ? "" : " · с " + a.since)
                    : "✅ Тревоги нет · " + region)
                    + "\n" + power;
            StatusWidget.update(getApplicationContext());
            runOnUiThread(() -> status.setText(text));
        }).start();
    }
}
