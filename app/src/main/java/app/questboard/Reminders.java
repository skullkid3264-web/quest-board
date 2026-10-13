package app.questboard;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 依頼ごとの「お知らせ」の予約を管理する。
 * 予約の一覧は端末内（SharedPreferences）にも保存しておき、
 * 再起動やアプリの更新で予約が消えたときに張り直せるようにする。
 */
final class Reminders {

    static final String CHANNEL = "quest_reminders";
    private static final String PREFS = "reminders";
    private static final String KEY = "list";

    private Reminders() {
    }

    static JSONArray load(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, JSONArray list) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.toString()).apply();
    }

    private static PendingIntent alarmIntent(Context c, String id, String title) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction("app.questboard.REMIND." + id);   // 依頼ごとに別の予約として扱われるように
        i.putExtra("id", id);
        i.putExtra("title", title);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context c, String id, long at, String title) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = alarmIntent(c, id, title);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    private static void cancel(Context c, String id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(c, id, ""));
    }

    /** 画面から届いた一覧に合わせて、予約をまるごと張り直す */
    static void sync(Context c, JSONArray wanted) {
        JSONArray old = load(c);
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null) cancel(c, o.optString("id", ""));
        }
        save(c, scheduleFuture(c, wanted));
    }

    /** 再起動・更新のあとに、保存しておいた予約を張り直す */
    static void rescheduleAll(Context c) {
        save(c, scheduleFuture(c, load(c)));
    }

    private static JSONArray scheduleFuture(Context c, JSONArray list) {
        JSONArray keep = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            long at = o.optLong("at", 0);
            if (id.isEmpty() || at <= now) continue;
            schedule(c, id, at, o.optString("title", ""));
            keep.put(o);
        }
        return keep;
    }

    /** 通知を出したら、その予約は一覧から外す */
    static void remove(Context c, String id) {
        JSONArray old = load(c);
        JSONArray keep = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id", ""))) keep.put(o);
        }
        save(c, keep);
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "依頼のお知らせ", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("依頼掲示板で決めた時間に届くお知らせ");
        nm.createNotificationChannel(ch);
    }
}
