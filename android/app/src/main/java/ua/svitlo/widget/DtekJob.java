package ua.svitlo.widget;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

/** Раз в полчаса загружает график с сайта ДТЭК (в режиме «через сервер»). */
public class DtekJob extends JobService {
    private static DtekFetcher active;

    /** Загрузить график сейчас. Вызывать из главного потока. */
    static void fetchNow(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        if (active != null) return; // уже идёт
        active = new DtekFetcher();
        active.fetch(app, (fact, error) -> {
            active = null;
            new Thread(() -> {
                Work.onDtek(app, fact, error);
                Work.remind(app);
                StatusWidget.render(app);
                if (done != null) done.run();
            }).start();
        });
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (active != null) return false;
        fetchNow(this, () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
