package app.questboard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** 予約した時間になったら、依頼のお知らせを出す */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("id");
        String title = intent.getStringExtra("title");
        if (id == null) return;
        Reminders.remove(c, id);

        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Reminders.ensureChannel(c);

        Intent open = new Intent(c, MainActivity.class);
        open.putExtra("questId", id);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = title == null || title.isEmpty() ? "依頼の時間です" : title;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, Reminders.CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("依頼のお知らせ")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(0xFFC0392B)
                .setContentIntent(tap)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id.hashCode(), b.build());
    }
}
