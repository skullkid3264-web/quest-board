package app.questboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** スマホの再起動やアプリの更新で消えたお知らせの予約を、張り直す */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Reminders.rescheduleAll(c);
        }
    }
}
