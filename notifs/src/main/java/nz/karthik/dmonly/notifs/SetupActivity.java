package nz.karthik.dmonly.notifs;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** The add-on's only screen: turn the relay on/off and give it notification access. */
public class SetupActivity extends Activity {

    private Switch relaySwitch;
    private TextView status;
    private Button accessButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.bar_bg));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText("Resistance Notifications");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.bar_active));
        col.addView(title);

        note(col, "An add-on for The Resistance. Keep the official Instagram and Messenger apps installed "
                + "with notifications on (you can remove them from your home screen). This app re-shows their "
                + "notifications with the same sender, message, picture and Reply buttons (which also work on "
                + "your watch), removes the originals, and tapping one opens The Resistance instead of the "
                + "official app.\n\nIt only reads Instagram and Messenger notifications and has no internet access.");

        relaySwitch = new Switch(this);
        relaySwitch.setText("Show Instagram & Messenger notifications through The Resistance");
        relaySwitch.setTextSize(17);
        relaySwitch.setTextColor(getColor(R.color.bar_active));
        relaySwitch.setPadding(0, dp(20), 0, dp(12));
        col.addView(relaySwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(getColor(R.color.accent));
        col.addView(status);

        accessButton = new Button(this);
        accessButton.setText("Allow notification access");
        accessButton.setOnClickListener(v -> askForAccess());
        col.addView(accessButton, blp(dp(12)));

        note(col, "If Android says it's a \"restricted setting\": open Settings > Apps > Resistance "
                + "Notifications, tap the ⋮ menu (top right) > Allow restricted settings, then come back "
                + "and tap Allow notification access again.");

        Button open = new Button(this);
        open.setText("Open The Resistance");
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent().setComponent(new ComponentName(
                        NotifRelayService.MAIN_PKG, NotifRelayService.MAIN_ACTIVITY)));
            } catch (Exception e) {
                Toast.makeText(this, "The Resistance isn't installed.", Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(open, blp(dp(20)));

        note(col, "Version 1." + version() + ". Updates: The Resistance > Settings tells you when there's a "
                + "new version of this add-on.");

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();   // back from the notification-access screen
    }

    private void refresh() {
        boolean on = NotifRelayService.relayOn(this);
        boolean access = hasAccess();
        relaySwitch.setOnCheckedChangeListener(null);
        relaySwitch.setChecked(on);
        relaySwitch.setOnCheckedChangeListener((b, c) -> setRelay(c));
        accessButton.setVisibility(on && !access ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!on) status.setText("Off. Notifications come from the official apps as normal.");
        else if (!access) status.setText("Needs notification access (button below).");
        else if (!NotifRelayService.mainAppInstalled(this))
            status.setText("On, but The Resistance isn't installed, so taps open the official apps.");
        else status.setText("On. Instagram and Messenger notifications open The Resistance.");
    }

    private void setRelay(boolean on) {
        NotifRelayService.prefs(this).edit().putBoolean("relay_on", on).apply();
        if (on && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (on && !hasAccess()) askForAccess();
        refresh();
    }

    private void askForAccess() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        new AlertDialog.Builder(this)
                .setTitle("Allow notification access")
                .setMessage("On the next screen, turn on Resistance Notifications.")
                .setPositiveButton("Continue", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "Open Settings > Notifications > Device & app notifications.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean hasAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        String me = new ComponentName(this, NotifRelayService.class).flattenToString();
        return flat != null && flat.contains(me);
    }

    @SuppressWarnings("deprecation")
    private int version() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void note(LinearLayout col, String text) {
        TextView n = new TextView(this);
        n.setText(text);
        n.setTextSize(14);
        n.setTextColor(getColor(R.color.bar_fg));
        n.setPadding(0, dp(8), 0, 0);
        col.addView(n);
    }

    private LinearLayout.LayoutParams blp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
