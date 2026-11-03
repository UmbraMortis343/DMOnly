package nz.karthik.dmonly.notifs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification relay.
 *
 * The official Instagram / Messenger apps still receive the messages (websites can't get
 * push notifications). This service sees their notifications, re-posts each one as a
 * Resistance notification (same sender, text, picture and reply/action buttons, so it
 * still shows and works on a smartwatch), and removes the original. Tapping it opens
 * The Resistance on the right tab instead of the official app.
 *
 * It only ever looks at notifications from the apps listed in appFor(); everything else
 * is ignored. This app has no internet permission.
 */
public class NotifRelayService extends NotificationListenerService {

    static final String CHANNEL = "relayed_messages";
    static final String TAG_IG = "ig:";
    static final String TAG_FB = "fb:";
    static final String MAIN_PKG = "nz.karthik.dmonly";
    static final String MAIN_ACTIVITY = "nz.karthik.dmonly.MainActivity";

    /** "ig" / "fb" for the apps we relay, null for everything else. */
    private static String appFor(String pkg) {
        switch (pkg) {
            case "com.instagram.android":
            case "com.instagram.lite":
                return "ig";
            case "com.facebook.orca":      // Messenger
            case "com.facebook.mlite":     // Messenger Lite
                return "fb";
            default:
                return null;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("relay", MODE_PRIVATE);
    }

    static boolean relayOn(Context c) {
        return prefs(c).getBoolean("relay_on", true);
    }

    static boolean mainAppInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(MAIN_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (!relayOn(this)) return;
            String app = appFor(sbn.getPackageName());
            if (app == null) return;

            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;   // e.g. "uploading..."
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {          // we group our own
                cancelQuietly(sbn);
                return;
            }

            Bundle x = n.extras;
            CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = x.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) return;

            NotificationManager nm = getSystemService(NotificationManager.class);
            ensureChannel(nm);

            boolean ig = app.equals("ig");
            // Tap -> The Resistance on the right tab. (If it's not installed, the original tap action.)
            PendingIntent tap = n.contentIntent;
            if (mainAppInstalled(this)) {
                Intent open = new Intent()
                        .setComponent(new ComponentName(MAIN_PKG, MAIN_ACTIVITY))
                        .putExtra("open_tab", ig ? "IG_DMS" : "MESSENGER")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                tap = PendingIntent.getActivity(this, sbn.getKey().hashCode(), open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }

            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSubText(ig ? "Instagram" : "Messenger")
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setWhen(n.when)
                    .setShowWhen(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setGroup("relay_" + app);
            Icon large = n.getLargeIcon();
            if (large != null) b.setLargeIcon(large);
            // Keep the original buttons (Reply, Like, Mark as read...). They still go to the
            // official app behind the scenes, so replying from your watch works.
            if (n.actions != null) {
                for (Notification.Action a : n.actions) b.addAction(a);
            }
            b.extend(new Notification.WearableExtender(n));

            nm.notify((ig ? TAG_IG : TAG_FB) + sbn.getKey(), 1, b.build());
            cancelQuietly(sbn);
        } catch (Exception ignored) {
            // Never crash on an odd notification; the original just stays where it is.
        }
    }

    private void cancelQuietly(StatusBarNotification sbn) {
        try {
            cancelNotification(sbn.getKey());
        } catch (Exception ignored) {
        }
    }

    static void ensureChannel(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Messages",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Instagram and Messenger messages, shown through The Resistance");
        nm.createNotificationChannel(ch);
    }
}
