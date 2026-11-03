package nz.karthik.dmonly.notifs;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

/** The Resistance opened a tab: remove this add-on's notifications for that app (seen). */
public class ClearReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String prefix = intent.getStringExtra("prefix");
        if (!NotifRelayService.TAG_IG.equals(prefix) && !NotifRelayService.TAG_FB.equals(prefix)) return;
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getTag() != null && s.getTag().startsWith(prefix)) nm.cancel(s.getTag(), s.getId());
            }
        } catch (Exception ignored) {
        }
    }
}
