package nz.karthik.dmonly;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Resistance: Instagram DMs, Instagram Stories and Messenger, with the feeds blocked.
 * (Internal names still say "dmonly" - the app's original name - so updates keep working.)
 *
 * How the blocking works:
 *  - Every page change (full loads AND Instagram's in-page URL changes) is checked.
 *  - Chats, login screens and stories are allowed.
 *  - A reel/post/video is allowed only if you opened it from a chat. If the URL then
 *    changes to a DIFFERENT reel (i.e. you swiped to the next one), you're sent back
 *    to the chat you came from.
 *  - Everything else (home feed, Explore, Reels tab, profiles, Watch, Marketplace...)
 *    is blocked. Links to other websites open in your normal browser.
 */
public class MainActivity extends Activity {

    enum Kind { IG_DMS, IG_STORIES, MESSENGER }

    enum Verdict { ALLOW, BOUNCE, EXTERNAL, IGNORE }

    private static final String IG_DMS_URL = "https://www.instagram.com/direct/inbox/";
    // Stories tab = two stacked pages: a strip of Instagram's home page cropped to the story
    // circles (IG_TRAY_URL), and below it the "Following" feed (only accounts you follow).
    private static final String IG_STORIES_URL = "https://www.instagram.com/?variant=following";
    private static final String IG_TRAY_URL = "https://www.instagram.com/";
    private static final String MESSENGER_URL = "https://www.facebook.com/messages/";

    // Public GitHub repo: code, version history and the APK downloads for updates.
    private static final String REPO = "UmbraMortis343/DMOnly";
    private static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    private static final int FB_FIT = 0;     // desktop site laid out at phone width (default)
    private static final int FB_WIDE = 1;    // desktop site at full width, zoomed out
    private static final int FB_MOBILE = 2;  // Facebook's mobile site

    private static final int REQ_FILE = 11;
    private static final int REQ_PERMS = 12;
    private static final int REQ_IG_PICK = 14;

    /** Set on the Intent from a relayed notification: which tab to open (a Kind name). */
    static final String EXTRA_OPEN_TAB = "open_tab";

    // Login / account / consent screens must always work.
    private static final Pattern AUTH = Pattern.compile(
            "^/(accounts|challenge|two_factor|login|logout|checkpoint|two_step_verification|recover"
                    + "|auth_platform|dialog|cookie|privacy|consent|confirmemail|device-based|oauth|x/oauth"
                    + "|r\\.php|reg|help|legal|terms|policies|session|nux)([/.?]|$)",
            Pattern.CASE_INSENSITIVE);

    // instagram.com/reel/ID, /reels/ID, /p/ID, /tv/ID, /username/reel/ID
    private static final Pattern IG_MEDIA = Pattern.compile(
            "^/(?:[A-Za-z0-9._]+/)?(reel|reels|p|tv)/([A-Za-z0-9_-]+)");

    // facebook.com/share/r/ID, /share/v/ID, /share/ID
    private static final Pattern FB_SHARE = Pattern.compile("^/share/(?:[a-z]/)?([A-Za-z0-9_-]+)");
    // facebook.com/reel/123, /username/videos/123, /videos/vb.1/123
    private static final Pattern FB_VIDEO = Pattern.compile(
            "^/(?:[^/]+/)?(?:reel|reels|videos)/(?:[^/]+/)?(\\d+)");
    // facebook.com/username/posts/ID
    private static final Pattern FB_POST = Pattern.compile("^/[^/]+/posts/([A-Za-z0-9]+)");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Tab> tabs = new ArrayList<>();
    private FrameLayout rootLayout;
    private FrameLayout content;
    private View tabBar, tabDivider;   // hidden while the keyboard is up
    private Tab current;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private long lastToast;
    private String mobileUA;
    private String desktopUA;
    private Tab feedTab;          // Stories tab: the Following feed
    private Tab trayTab;          // Stories tab: the cropped story-circles strip
    private int trayHeightPx;
    private View storiesToolbar;  // Stories tab: Search / + Post / + Story
    private long feedFixAt;       // last time the Stories feed was put back on the Following feed

    // Settings tab + feed break (temporarily unblocks normal Instagram, max 1 hour)
    private TextView settingsButton;
    private View settingsView;
    private long unlockUntil;
    private boolean wasUnlocked;
    private Button unlockButton;
    private TextView unlockStatus;
    private TextView unlockMinutesLabel;
    private SeekBar unlockSeek;
    private TextView addonStatus;
    private Button addonButton;
    private Button addonUpdate;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (wasUnlocked && !unlocked()) endUnlock(false);
            updateUnlockUi();
            main.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dmonly", MODE_PRIVATE);

        rootLayout = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View divider = new View(this);
        tabDivider = divider;
        divider.setBackgroundColor(0x33888888);
        column.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout bar = new LinearLayout(this);
        tabBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(getColor(R.color.bar_bg));
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        rootLayout.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);
        // Typing: hide the tab bar so it doesn't sit on top of the keyboard.
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(this::onLayoutChanged);

        // Look like normal Chrome so Meta serves its regular mobile site.
        String def = WebSettings.getDefaultUserAgent(this);
        mobileUA = def.replace("; wv)", ")").replace("Version/4.0 ", "");
        Matcher m = Pattern.compile("Chrome/[\\d.]+").matcher(def);
        String chromeVer = m.find() ? m.group() : "Chrome/130.0.0.0";
        desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) " + chromeVer + " Safari/537.36";

        CookieManager.getInstance().setAcceptCookie(true);

        tabs.add(new Tab(Kind.IG_DMS, "DMs", IG_DMS_URL, false));
        feedTab = new Tab(Kind.IG_STORIES, "Stories", IG_STORIES_URL, false);
        tabs.add(feedTab);
        tabs.add(new Tab(Kind.MESSENGER, "Messenger", MESSENGER_URL, false));

        // Stories tab layout: [story-circles strip] above [Following feed].
        trayTab = new Tab(Kind.IG_STORIES, "Stories", IG_TRAY_URL, true);
        trayTab.web.setVerticalScrollBarEnabled(false);
        trayHeightPx = dp(112);
        LinearLayout storiesBox = new LinearLayout(this);
        storiesBox.setOrientation(LinearLayout.VERTICAL);
        storiesToolbar = buildStoriesToolbar();
        storiesBox.addView(storiesToolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        storiesBox.addView(trayTab.web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, trayHeightPx));
        storiesBox.addView(feedTab.web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        feedTab.root = storiesBox;

        for (Tab t : tabs) {
            bar.addView(t.button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            content.addView(t.root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        settingsButton = new TextView(this);
        settingsButton.setText("Settings");
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setTextSize(15);
        settingsButton.setOnClickListener(v -> showSettings());
        bar.addView(settingsButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        settingsView = buildSettingsView();
        settingsView.setVisibility(View.GONE);
        content.addView(settingsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        unlockUntil = prefs.getLong("unlock_until", 0);
        if (unlocked()) {
            wasUnlocked = true;
            feedTab.startUrl = IG_TRAY_URL;
            feedTab.lastSafeUrl = IG_TRAY_URL;
        }
        for (Tab t : tabs) t.button.setVisibility(tabEnabled(t) ? View.VISIBLE : View.GONE);

        int last = prefs.getInt("tab", 0);
        Tab start = (last >= 0 && last < tabs.size()) ? tabs.get(last) : null;
        if (start == null || !tabEnabled(start)) start = last == -1 ? null : firstEnabledTab();
        if (start == null) showSettings();
        else select(start);
        handleOpenIntent(getIntent());
        main.post(ticker);

        // Quietly check for a newer version at most twice a day.
        if (System.currentTimeMillis() - prefs.getLong("update_checked", 0) > 12 * 3600_000L) {
            checkForUpdates(false);
        }
    }

    private void onLayoutChanged() {
        boolean kb;
        android.view.WindowInsets wi = rootLayout.getRootWindowInsets();
        if (android.os.Build.VERSION.SDK_INT >= 30 && wi != null) {
            kb = wi.isVisible(android.view.WindowInsets.Type.ime());
        } else {
            android.graphics.Rect r = new android.graphics.Rect();
            rootLayout.getWindowVisibleDisplayFrame(r);
            kb = rootLayout.getRootView().getHeight() - r.bottom > dp(150);
        }
        int vis = kb ? View.GONE : View.VISIBLE;
        if (tabBar.getVisibility() != vis) {
            tabBar.setVisibility(vis);
            tabDivider.setVisibility(vis);
        }
    }

    // ------------------------------------------------------------------ notification relay

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    /** A relayed notification was tapped: open its tab. */
    private void handleOpenIntent(Intent intent) {
        if (intent == null) return;
        String kind = intent.getStringExtra(EXTRA_OPEN_TAB);
        if (kind == null) return;
        intent.removeExtra(EXTRA_OPEN_TAB);
        for (Tab t : tabs) {
            if (t.kind.name().equals(kind) && tabEnabled(t)) {
                select(t);
                return;
            }
        }
    }

    // Notifications live in a separate add-on app (Resistance Notifications), so this app
    // never needs notification access.
    static final String ADDON_PKG = "nz.karthik.dmonly.notifs";
    static final String ADDON_APK = "ResistanceNotifications.apk";
    static final String TAG_IG = "ig:";
    static final String TAG_FB = "fb:";
    private int latestRelease;   // newest release number seen by the update check (0 = unknown)

    /** Add-on version number, or -1 if it isn't installed. */
    @SuppressWarnings("deprecation")
    private int addonVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(ADDON_PKG, 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** Tell the add-on to remove its notifications for the app you just opened (they've been seen). */
    private void clearRelayed(String tagPrefix) {
        try {
            if (addonVersion() < 0) return;
            sendBroadcast(new Intent(ADDON_PKG + ".CLEAR").setPackage(ADDON_PKG).putExtra("prefix", tagPrefix));
        } catch (Exception ignored) {
        }
    }

    private void downloadAddon() {
        openExternal(Uri.parse("https://github.com/" + REPO + "/releases/latest/download/" + ADDON_APK));
    }

    private void refreshRelayUi() {
        if (addonStatus == null) return;
        int v = addonVersion();
        if (v < 0) {
            addonStatus.setText("Not installed.");
            addonButton.setText("Download the add-on");
            addonButton.setOnClickListener(x -> downloadAddon());
            addonUpdate.setVisibility(View.GONE);
        } else {
            addonStatus.setText("Installed (version 1." + v + ").");
            addonButton.setText("Open Resistance Notifications");
            addonButton.setOnClickListener(x -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(ADDON_PKG);
                if (i != null) startActivity(i);
            });
            boolean old = v < Math.max(latestRelease, currentVersion());
            addonUpdate.setVisibility(old ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ updates

    @SuppressWarnings("deprecation")
    private int currentVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Reads the newest GitHub release; if it's newer than this app, offers to download it. */
    private void checkForUpdates(boolean manual) {
        prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "TheResistance-updater");
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                JSONObject rel = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                int latest = Integer.parseInt(rel.getString("tag_name").replaceAll("[^0-9]", ""));
                String notes = rel.optString("body", "").trim();
                String apkUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").equals("TheResistance.apk")) apkUrl = a.optString("browser_download_url");
                }
                final String url = apkUrl;
                main.post(() -> {
                    latestRelease = latest;
                    refreshRelayUi();
                    if (latest > currentVersion() && url != null) {
                        new AlertDialog.Builder(this)
                                .setTitle("Update available: version 1." + latest)
                                .setMessage((notes.isEmpty() ? "" : notes + "\n\n")
                                        + "Tap Download, then open the file to install. "
                                        + "You'll stay logged in.")
                                .setPositiveButton("Download", (d, w) -> openExternal(Uri.parse(url)))
                                .setNeutralButton("What's new", (d, w) -> openExternal(Uri.parse(RELEASES_PAGE)))
                                .setNegativeButton("Later", null)
                                .show();
                    } else if (manual) {
                        Toast.makeText(this, "You're up to date (version 1." + currentVersion() + ").",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (manual) {
                    main.post(() -> Toast.makeText(this, "Couldn't check for updates. Try again later.",
                            Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------ settings tab

    private boolean tabEnabled(Tab t) {
        return prefs.getBoolean("on_" + t.kind.name(), true);
    }

    private Tab firstEnabledTab() {
        for (Tab t : tabs) if (tabEnabled(t)) return t;
        return null;
    }

    /** Show/hide tab buttons after a setting changes; move off a tab that was just turned off. */
    private void applyTabVisibility() {
        for (Tab t : tabs) t.button.setVisibility(tabEnabled(t) ? View.VISIBLE : View.GONE);
        if (current != null && !tabEnabled(current)) {
            Tab next = firstEnabledTab();
            if (next != null) select(next);
            else showSettings();
        }
    }

    private void showSettings() {
        current = null;
        for (Tab t : tabs) {
            t.root.setVisibility(View.GONE);
            t.button.setTextColor(getColor(R.color.bar_fg));
            t.button.setTypeface(null, Typeface.NORMAL);
        }
        settingsView.setVisibility(View.VISIBLE);
        settingsButton.setTextColor(getColor(R.color.bar_active));
        settingsButton.setTypeface(null, Typeface.BOLD);
        applyOrientation();
        updateUnlockUi();
        refreshRelayUi();
        prefs.edit().putInt("tab", -1).apply();
    }

    private View buildSettingsView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.bar_bg));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.bar_active));
        col.addView(title);

        addHeader(col, "Instagram");
        addTabSwitch(col, "DMs", tabs.get(0));
        addTabSwitch(col, "Stories & following feed", feedTab);

        addHeader(col, "Messenger");
        addTabSwitch(col, "Messenger", tabs.get(2));
        addNote(col, "Long-press the Messenger tab to change how Facebook's site is laid out.");

        addHeader(col, "Notifications");
        addNote(col, "Websites can't get notifications. The free add-on app Resistance Notifications re-shows "
                + "Instagram and Messenger notifications (with Reply buttons that work on your watch) and opens "
                + "this app when you tap one. Keep the official Instagram and Messenger apps installed with "
                + "notifications on; you can remove them from your home screen.");
        addonStatus = new TextView(this);
        addonStatus.setTextSize(15);
        addonStatus.setTextColor(getColor(R.color.accent));
        addonStatus.setPadding(0, dp(8), 0, 0);
        col.addView(addonStatus);
        addonButton = new Button(this);
        col.addView(addonButton, blp(dp(6)));
        addonUpdate = new Button(this);
        addonUpdate.setText("Update the add-on");
        addonUpdate.setOnClickListener(v -> downloadAddon());
        col.addView(addonUpdate, blp(dp(4)));

        addHeader(col, "Feed break");
        addNote(col, "Opens normal Instagram (feed, Explore, Reels) in the Stories tab for a set time. "
                + "When the timer runs out, everything is blocked again.");
        unlockMinutesLabel = new TextView(this);
        unlockMinutesLabel.setTextSize(16);
        unlockMinutesLabel.setTextColor(getColor(R.color.bar_active));
        unlockMinutesLabel.setPadding(0, dp(12), 0, 0);
        col.addView(unlockMinutesLabel);
        unlockSeek = new SeekBar(this);
        unlockSeek.setMax(11);                                       // 5, 10, ... 60 minutes
        unlockSeek.setProgress(Math.max(0, Math.min(11, prefs.getInt("unlock_minutes", 15) / 5 - 1)));
        unlockSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                prefs.edit().putInt("unlock_minutes", (p + 1) * 5).apply();
                updateUnlockUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        col.addView(unlockSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        unlockButton = new Button(this);
        unlockButton.setOnClickListener(v -> {
            if (unlocked()) endUnlock(true);
            else startUnlock((unlockSeek.getProgress() + 1) * 5);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        col.addView(unlockButton, blp);
        unlockStatus = new TextView(this);
        unlockStatus.setTextSize(14);
        unlockStatus.setTextColor(getColor(R.color.bar_fg));
        unlockStatus.setPadding(0, dp(8), 0, 0);
        col.addView(unlockStatus);

        addHeader(col, "About & updates");
        addNote(col, "The Resistance, version 1." + currentVersion()
                + ". Open source; not affiliated with Meta.");
        Button check = new Button(this);
        check.setText("Check for updates");
        check.setOnClickListener(v -> checkForUpdates(true));
        col.addView(check, blp(dp(8)));
        Button history = new Button(this);
        history.setText("Version history & code");
        history.setOnClickListener(v -> openExternal(Uri.parse(RELEASES_PAGE)));
        col.addView(history, blp(dp(4)));
        return scroll;
    }

    private LinearLayout.LayoutParams blp(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin;
        return p;
    }

    private void addHeader(LinearLayout col, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(13);
        h.setAllCaps(true);
        h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(getColor(R.color.accent));
        h.setPadding(0, dp(28), 0, dp(4));
        col.addView(h);
    }

    private void addNote(LinearLayout col, String text) {
        TextView n = new TextView(this);
        n.setText(text);
        n.setTextSize(14);
        n.setTextColor(getColor(R.color.bar_fg));
        n.setPadding(0, dp(4), 0, 0);
        col.addView(n);
    }

    private void addTabSwitch(LinearLayout col, String label, Tab tab) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(17);
        sw.setTextColor(getColor(R.color.bar_active));
        sw.setPadding(dp(16), dp(12), 0, dp(12));
        sw.setChecked(tabEnabled(tab));
        sw.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("on_" + tab.kind.name(), on).apply();
            applyTabVisibility();
        });
        col.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ feed break

    private boolean unlocked() {
        return System.currentTimeMillis() < unlockUntil;
    }

    private void startUnlock(int minutes) {
        minutes = Math.max(1, Math.min(60, minutes));
        unlockUntil = System.currentTimeMillis() + minutes * 60_000L;
        prefs.edit().putLong("unlock_until", unlockUntil).apply();
        wasUnlocked = true;
        stopCreate();
        feedTab.startUrl = IG_TRAY_URL;          // normal Instagram home feed
        feedTab.lastSafeUrl = IG_TRAY_URL;
        reloadInstagram();
        updateUnlockUi();
        if (tabEnabled(feedTab)) select(feedTab);
    }

    private void endUnlock(boolean early) {
        unlockUntil = 0;
        prefs.edit().putLong("unlock_until", 0).apply();
        wasUnlocked = false;
        feedTab.startUrl = IG_STORIES_URL;
        feedTab.lastSafeUrl = IG_STORIES_URL;
        reloadInstagram();
        updateUnlockUi();
        Toast.makeText(this, early ? "Feed break ended." : "Feed break's over. Feeds are blocked again.",
                Toast.LENGTH_LONG).show();
    }

    private void reloadInstagram() {
        for (Tab t : tabs) {
            if (t.kind != Kind.MESSENGER && t.loaded) {
                clearMedia(t);
                t.web.loadUrl(t.startUrl);
            }
        }
        if (trayTab.loaded) loadTray();
        updateStoriesLayout();
    }

    private void updateUnlockUi() {
        boolean on = unlocked();
        String left = "";
        if (on) {
            long s = Math.max(0, (unlockUntil - System.currentTimeMillis()) / 1000);
            left = String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }
        feedTab.button.setText(on ? "Feed " + left : "Stories");
        if (unlockButton == null) return;
        int mins = (unlockSeek.getProgress() + 1) * 5;
        unlockMinutesLabel.setText("Length: " + mins + " minutes");
        unlockSeek.setEnabled(!on);
        unlockButton.setText(on ? "End feed break now" : "Start " + mins + "-minute feed break");
        unlockStatus.setText(on ? "Feed is open. " + left + " left." : "Feeds are blocked.");
    }

    // ------------------------------------------------------------------ orientation

    /** Instagram tabs stay portrait (its site shows extra junk in landscape). */
    private void applyOrientation() {
        if (customView != null) return;
        boolean ig = current != null && current.kind != Kind.MESSENGER;
        setRequestedOrientation(ig ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void select(Tab tab) {
        current = tab;
        settingsView.setVisibility(View.GONE);
        settingsButton.setTextColor(getColor(R.color.bar_fg));
        settingsButton.setTypeface(null, Typeface.NORMAL);
        for (Tab t : tabs) {
            boolean on = t == tab;
            t.root.setVisibility(on ? View.VISIBLE : View.GONE);
            t.button.setTextColor(getColor(on ? R.color.bar_active : R.color.bar_fg));
            t.button.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (!tab.loaded) {          // tabs only load the first time you open them
            tab.loaded = true;
            tab.web.loadUrl(tab.startUrl);
        }
        if (tab == feedTab && !trayTab.loaded) loadTray();
        updateStoriesLayout();
        applyOrientation();
        clearRelayed(tab.kind == Kind.MESSENGER ? TAG_FB : TAG_IG);
        prefs.edit().putInt("tab", tabs.indexOf(tab)).apply();
    }

    private void loadTray() {
        trayTab.loaded = true;
        trayTab.loadedLoggedIn = isLoggedIn(true);
        trayTab.web.loadUrl(IG_TRAY_URL);
    }

    /**
     * Stories tab: show the story strip above the feed only on the feed page. Open a story
     * -> the strip goes full screen. Comments / posts / anything else -> strip hidden.
     */
    private void updateStoriesLayout() {
        if (trayTab == null || feedTab == null) return;
        boolean loggedIn = isLoggedIn(true);
        if (loggedIn && trayTab.loaded && !trayTab.loadedLoggedIn) loadTray();   // just signed in
        String tp = trayTab.lastPath;
        // a story (or a reply chat) is open in the strip
        boolean storyOpen = !tp.isEmpty() && !tp.equals("/");
        String fp = feedTab.lastPath;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) trayTab.web.getLayoutParams();
        if (!loggedIn || unlocked() || createMode) {   // feed break / making a post: plain Instagram
            trayTab.web.setVisibility(View.GONE);
            feedTab.web.setVisibility(View.VISIBLE);
        } else if (storyOpen) {
            lp.height = 0;
            lp.weight = 1f;
            trayTab.web.setVisibility(View.VISIBLE);
            feedTab.web.setVisibility(View.GONE);
        } else {
            lp.height = trayHeightPx;
            lp.weight = 0f;
            feedTab.web.setVisibility(View.VISIBLE);
            boolean onFeed = fp.isEmpty() || fp.equals("/");
            trayTab.web.setVisibility(onFeed ? View.VISIBLE : View.GONE);
        }
        trayTab.web.setLayoutParams(lp);
        // Search / + Post / + Story bar: on the feed page only (not during a story or feed break).
        if (storiesToolbar != null) {
            boolean onFeed = fp.isEmpty() || fp.equals("/");
            storiesToolbar.setVisibility(loggedIn && !unlocked() && !createMode && !storyOpen && onFeed
                    ? View.VISIBLE : View.GONE);
        }
    }

    private View buildStoriesToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(getColor(R.color.bar_bg));
        String[] labels = {"Search", "Profile", "+ Post", "+ Story"};
        for (int i = 0; i < labels.length; i++) {
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(13);
            b.setTypeface(null, Typeface.BOLD);
            b.setTextColor(getColor(R.color.bar_active));
            final int which = i;
            b.setOnClickListener(v -> onStoriesToolbar(which));
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return row;
    }

    private void onStoriesToolbar(int which) {
        switch (which) {
            case 0:
                feedTab.web.loadUrl("https://www.instagram.com/explore/search/");
                break;
            case 1:
                openOwnProfile();
                break;
            default:
                chooseCreate(which == 2 ? CREATE_POST : CREATE_STORY);
                break;
        }
    }

    /** Here (website: photo / video only) or the Instagram app (music, stickers, filters...). */
    private void chooseCreate(int which) {
        String what = which == CREATE_POST ? "post" : "story";
        new AlertDialog.Builder(this)
                .setTitle(which == CREATE_POST ? "New post" : "New story")
                .setItems(new String[]{
                        "Here (photo or video)",
                        "Instagram app (music, stickers, filters)"}, (d, i) -> {
                    if (i == 0) startCreate(which);
                    else pickForInstagramApp(what);
                })
                .show();
    }

    // Instagram app: pick a photo / video here, then hand it to Instagram, which asks where to
    // share it (Story, Feed, Reels...). That opens straight in its editor, not its feed.
    private String igPickWhat = "story";

    private void pickForInstagramApp(String what) {
        igPickWhat = what;
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(pick, REQ_IG_PICK);
        } catch (ActivityNotFoundException e) {
            toast("No photo picker found.");
        }
    }

    private ComponentName instagramShareTarget(Intent send, boolean story) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName feed = null, fallback = null;
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(send, 0)) {
                if (ri.activityInfo == null) continue;
                String name = ri.activityInfo.name.toLowerCase(Locale.ROOT);
                CharSequence l = ri.loadLabel(pm);
                String label = l == null ? "" : l.toString().toLowerCase(Locale.ROOT);
                ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                boolean isStory = name.contains("story") || name.contains("stories") || label.contains("stor");
                boolean isOther = name.contains("direct") || name.contains("clips") || name.contains("reel")
                        || name.contains("thread") || label.contains("chat") || label.contains("direct")
                        || label.contains("message") || label.contains("reel") || label.contains("thread");
                if (story) {
                    if (isStory) return cn;
                } else if (!isStory && !isOther) {
                    if (label.contains("feed") || label.contains("post")) feed = cn;
                    else if (fallback == null) fallback = cn;
                }
            }
            return feed != null ? feed : fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void sendToInstagramApp(Uri media) {
        String type = getContentResolver().getType(media);
        if (type == null) type = "image/*";
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(type)
                .putExtra(Intent.EXTRA_STREAM, media)
                .setPackage("com.instagram.android")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri("", media));
        // Instagram has a separate share target for Feed, Stories, Chats and Reels. Go straight
        // to the right one (otherwise it can pick Stories for a post).
        ComponentName target = instagramShareTarget(send, igPickWhat.equals("story"));
        if (target != null) send.setComponent(target);
        try {
            startActivity(send);
            Toast.makeText(this, "In Instagram, choose " + (igPickWhat.equals("post") ? "Feed" : "Story")
                    + ". Come back here when you're done.", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            toast("The Instagram app isn't installed. Use \"Here\" instead.");
        }
    }

    // ---- Your own profile. Instagram's own Profile button is hidden (it sits in the bar with
    // Reels / Explore), so read your username from it and open https://www.instagram.com/<you>/.

    private static final String JS_MY_USERNAME = "(function(){"
            + "function u(a){var m=(a.getAttribute('href')||'').match(/^\\/([A-Za-z0-9._]{1,30})\\/?$/);return m?m[1]:'';}"
            + "var l=[].slice.call(document.querySelectorAll('a[href^=\"/\"]'));"
            + "for(var i=0;i<l.length;i++){var g=l[i].querySelector('img[alt]');"
            + " if(g&&/profile picture/i.test(g.alt)&&l[i].closest('nav,[role=navigation],.dmonly-nav,.dmonly-hide')&&u(l[i]))return u(l[i]);}"
            + "for(var j=0;j<l.length;j++){var a=l[j];"
            + " if((/^profile$/i.test((a.textContent||'').trim())||a.querySelector('svg[aria-label=\"Profile\"]'))&&u(a))return u(a);}"
            + "return '';})()";

    private static String igUserId() {
        String c = CookieManager.getInstance().getCookie("https://www.instagram.com/");
        if (c == null) return "";
        Matcher m = Pattern.compile("(?:^|;\\s*)ds_user_id=(\\d+)").matcher(c);
        return m.find() ? m.group(1) : "";
    }

    private void openOwnProfile() {
        String id = igUserId();
        String cached = prefs.getString("ig_user", "");
        if (!cached.isEmpty() && id.equals(prefs.getString("ig_user_id", ""))) {
            feedTab.web.loadUrl("https://www.instagram.com/" + cached + "/");
            return;
        }
        findUsername(feedTab.web, name -> {
            if (name.isEmpty()) {
                findUsername(trayTab.web, n2 -> gotUsername(n2, id));
            } else {
                gotUsername(name, id);
            }
        });
    }

    private interface StrCallback {
        void on(String s);
    }

    private void findUsername(WebView w, StrCallback cb) {
        w.evaluateJavascript(JS_MY_USERNAME, r -> cb.on(jsString(r)));
    }

    private void gotUsername(String name, String id) {
        if (name.isEmpty()) {
            toast("Couldn't find your profile yet. Try again in a few seconds.");
            return;
        }
        prefs.edit().putString("ig_user", name).putString("ig_user_id", id).apply();
        feedTab.web.loadUrl("https://www.instagram.com/" + name + "/");
    }

    /** evaluateJavascript hands back JSON ("\"text\"" or "null"). */
    private static String jsString(String r) {
        if (r == null || r.equals("null")) return "";
        try {
            Object v = new org.json.JSONTokener(r).nextValue();
            return v instanceof String ? (String) v : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---- + Post / + Story ("create mode").
    // There's no page address for these (instagram.com/create is someone's profile!). So the
    // feed area briefly shows Instagram's normal home page (posts hidden) and the app taps
    // Instagram's own + / "Your story" with a real screen tap, so the photo picker is allowed
    // to open. If that misses, Instagram's buttons are right there to tap yourself. When the
    // post / story is shared or cancelled (or you press back) you're back on the Following feed.

    private static final int CREATE_POST = 1, CREATE_STORY = 2;
    private boolean createMode;
    private int createWhich;
    private int createStage;      // see createDriver
    private int createTries;
    private boolean createDone;   // stopped auto-tapping
    private boolean createBusy;   // Instagram's create screen / menu / story viewer is open
    private boolean createSawBusy;

    private void startCreate(int which) {
        if (!isLoggedIn(true)) return;
        createMode = true;
        createWhich = which;
        createStage = 0;
        createTries = 0;
        createDone = false;
        createBusy = false;
        createSawBusy = false;
        main.removeCallbacks(createDriver);
        main.removeCallbacks(createCheck);
        feedTab.web.loadUrl(IG_TRAY_URL);
        updateStoriesLayout();
        toast(which == CREATE_POST ? "Opening New post... (back cancels)" : "Opening Your story... (back cancels)");
    }

    /** Leave create mode and go back to the Following feed. */
    private void endCreate() {
        if (!createMode) return;
        stopCreate();
        feedFixAt = SystemClock.elapsedRealtime();
        feedTab.web.loadUrl(IG_STORIES_URL);
        updateStoriesLayout();
    }

    private void stopCreate() {
        createMode = false;
        createDone = true;
        main.removeCallbacks(createDriver);
        main.removeCallbacks(createCheck);
    }

    private static final String JS_FIND = "window.__dmFind=function(q){"
            + "function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth?r:null;}"
            + "function out(e){var r=vis(e);return r?JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,iw:innerWidth}):'';}"
            + "function leaf(re,sc){var l=[].slice.call((sc||document).querySelectorAll('span,div,a,button,h1,h2'));"
            + " for(var i=0;i<l.length;i++){var e=l[i];if(e.childElementCount===0&&re.test((e.textContent||'').trim())&&vis(e))return e;}return null;}"
            + "if(q==='plus'){var s=[].slice.call(document.querySelectorAll("
            + "'svg[aria-label=\"New post\"],svg[aria-label=\"New Post\"],svg[aria-label=\"Create\"],svg[aria-label=\"New\"]'))"
            + ".filter(function(e){return vis(e)&&!e.closest('article');})[0];return s?out(s):'';}"
            + "if(q==='your'){var t=leaf(/^your story$/i);if(!t)return '';"
            + " var c=t.closest('[role=button],button,a,[role=link],li');"
            + " if(c&&c.getBoundingClientRect().height<200&&c.getBoundingClientRect().width<200)return out(c);return out(t);}"
            + "var d=document.querySelector('[role=dialog]')||document;"
            + "if(q==='menuPost'){var p=leaf(/^post$/i,d);return p?out(p):'';}"
            + "if(q==='menuStory'){var m=leaf(/^(story|add to story|add to your story)$/i,d);return m?out(m):'';}"
            + "return '';};";

    /** The page script for create mode: hide the feed posts, report what's open. */
    private void injectCreate(Tab tab) {
        String css = "body.dmonly-home article,body.dmonly-home [role=\"progressbar\"],"
                + "a[href=\"/explore/\"],a[href^=\"/reels\"]{display:none!important}";
        String js = "(function(){"
                + "if(window.__dmonly)return;window.__dmonly=true;"
                + JS_FIND
                + "var css=" + JSONObject.quote(css) + ",lastBusy=null,last='';"
                + "function tick(){"
                + " var s=document.getElementById('dmonly-css');"
                + " if(!s){s=document.createElement('style');s.id='dmonly-css';s.textContent=css;"
                + "  (document.head||document.documentElement).appendChild(s);}"
                + " var p=location.pathname;"
                + " if(document.body)document.body.classList.toggle('dmonly-home',p==='/');"
                + " var dlg=document.querySelector('[role=dialog]');"
                // Instagram's "Turn on notifications" / "Add to home screen" pop-ups: Not now.
                + " if(dlg&&!window.__dmTapped){[].slice.call(dlg.querySelectorAll('button,[role=button]')).forEach(function(b){"
                + "  if(/^not now$/i.test((b.textContent||'').trim()))b.click();});}"
                // Busy = the create screen, a story, or a menu that our tap opened.
                + " var busy=(!!dlg&&!!window.__dmTapped)||p.indexOf('/create')===0||p.indexOf('/stories')===0;"
                + " if(busy!==lastBusy){lastBusy=busy;try{DMOnly.creatingState(busy);}catch(e){}}"
                + " if(location.href!==last){last=location.href;try{DMOnly.onUrl(last);}catch(e){}}"
                + "}"
                + "setInterval(tick,400);tick();"
                + "})();";
        tab.web.evaluateJavascript(js, null);
        if (!createDone) {
            main.removeCallbacks(createDriver);
            main.postDelayed(createDriver, 700);
        }
    }

    /**
     * Finds and taps Instagram's buttons, retrying while the page loads.
     * Post:  stage 0 tap +  -> stage 1 tap "Post" if a menu pops up.
     * Story: stage 0 tap "Your story" (stage 1 = done); if there isn't one, stage 2 tap +
     *        -> stage 3 tap "Story" in the menu.
     */
    private final Runnable createDriver = new Runnable() {
        @Override
        public void run() {
            if (!createMode || createDone) return;
            String p = feedTab.lastPath;
            if (fileCallback != null || p.startsWith("/create") || p.startsWith("/stories")) {
                createDone = true;            // the picker / create screen is up: job done
                return;
            }
            createTries++;
            final String q;
            final int limit;
            if (createWhich == CREATE_POST) {
                q = createStage == 0 ? "plus" : "menuPost";
                limit = createStage == 0 ? 25 : 6;
            } else if (createStage == 0) {
                q = "your";
                limit = 12;
            } else {
                q = createStage == 2 ? "plus" : "menuStory";
                limit = createStage == 2 ? 10 : 6;
            }
            feedTab.web.evaluateJavascript("window.__dmFind?__dmFind('" + q + "'):''", r -> {
                if (!createMode || createDone) return;
                String hit = jsString(r);
                if (!hit.isEmpty()) {
                    feedTab.web.evaluateJavascript("window.__dmTapped=true;", null);
                    tapAt(feedTab.web, hit);
                    createTries = 0;
                    if (createWhich == CREATE_POST) {
                        if (createStage == 0) createStage = 1;
                        else createDone = true;
                    } else if (createStage == 0 || createStage == 3) {
                        createDone = true;
                    } else {
                        createStage = 3;
                    }
                } else if (createTries >= limit) {
                    createTries = 0;
                    if (createWhich == CREATE_STORY && createStage == 0) {
                        createStage = 2;                       // no "Your story": try the + instead
                    } else if (createStage == 0 || createStage == 2) {
                        createDone = true;
                        toast(createWhich == CREATE_POST ? "Tap Instagram's + to post. Back cancels."
                                : "Tap \"Your story\" to add one. Back cancels.");
                        return;
                    } else {
                        createDone = true;                     // no menu: the picker opened directly
                        return;
                    }
                }
                if (!createDone) main.postDelayed(createDriver, 400);
            });
        }
    };

    /** A real screen tap (so Instagram may open the photo picker), at page coordinates. */
    private void tapAt(WebView w, String json) {
        try {
            JSONObject o = new JSONObject(json);
            double iw = o.getDouble("iw");
            if (iw <= 0 || w.getWidth() <= 0) return;
            float scale = (float) (w.getWidth() / iw);
            float x = (float) o.getDouble("x") * scale;
            float y = (float) o.getDouble("y") * scale;
            final long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            w.dispatchTouchEvent(down);
            down.recycle();
            main.postDelayed(() -> {
                MotionEvent up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                w.dispatchTouchEvent(up);
                up.recycle();
            }, 60);
        } catch (Exception ignored) {
        }
    }

    /** Back on Instagram's home page with nothing open (shared, or cancelled): go back to the feed. */
    private final Runnable createCheck = new Runnable() {
        @Override
        public void run() {
            if (!createMode || !createSawBusy) return;
            String p = feedTab.lastPath;
            boolean home = p.isEmpty() || p.equals("/");
            if (!createBusy && fileCallback == null && home) endCreate();
        }
    };

    private void scheduleCreateCheck() {
        main.removeCallbacks(createCheck);
        main.postDelayed(createCheck, 1500);
    }

    private int fbMode() {
        int m = prefs.getInt("fb_mode", FB_FIT);
        return (m < FB_FIT || m > FB_MOBILE) ? FB_FIT : m;
    }

    private void onTabTap(Tab tab) {
        if (tab == current) {
            if (tab == feedTab) stopCreate();
            tab.web.loadUrl(tab.startUrl);   // tap the tab you're on = back to the inbox
            if (tab == feedTab) loadTray();
        } else {
            select(tab);
        }
    }

    private void showTabMenu(Tab tab) {
        List<String> items = new ArrayList<>();
        items.add("Reload");
        items.add("Back to start");
        int mode = fbMode();
        if (tab.kind == Kind.MESSENGER) {
            items.add((mode == FB_FIT ? "✓ " : "") + "Desktop site, fit to screen");
            items.add((mode == FB_WIDE ? "✓ " : "") + "Desktop site, zoomed out");
            items.add((mode == FB_MOBILE ? "✓ " : "") + "Mobile site");
        }
        new AlertDialog.Builder(this)
                .setTitle(tab.label)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    select(tab);
                    if (which == 0) {
                        tab.web.reload();
                        if (tab == feedTab) loadTray();
                    } else if (which == 1) {
                        tab.web.loadUrl(tab.startUrl);
                        if (tab == feedTab) loadTray();
                    } else {
                        prefs.edit().putInt("fb_mode", which - 2).apply();
                        tab.applyMode();
                        tab.web.loadUrl(tab.startUrl);
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ tabs

    final class Tab {
        final Kind kind;
        final String label;
        String startUrl;
        final WebView web;
        final TextView button;
        final boolean isTray;   // the cropped story-circles strip in the Stories tab
        View root;              // what the tab shows (the WebView, or the Stories stack)

        boolean loaded;
        boolean loadedLoggedIn;
        String lastSafeUrl;     // last chat/inbox URL: where we send you back to
        String mediaKey;        // the one reel/post you're allowed to be watching
        boolean mediaFromShare; // share links redirect once to the real reel URL
        boolean swipeBounce;    // the pending bounce is a reel swipe
        String lastSeenUrl;
        String lastPath = "";
        int bounceCount;
        long bounceWindowStart;

        @SuppressLint("SetJavaScriptEnabled")
        Tab(Kind kind, String label, String startUrl, boolean isTray) {
            this.kind = kind;
            this.label = label;
            this.startUrl = startUrl;
            this.lastSafeUrl = startUrl;
            this.isTray = isTray;

            button = new TextView(MainActivity.this);
            button.setText(label);
            button.setGravity(Gravity.CENTER);
            button.setTextSize(15);
            button.setOnClickListener(v -> onTabTap(Tab.this));
            button.setOnLongClickListener(v -> {
                showTabMenu(Tab.this);
                return true;
            });

            web = new WebView(MainActivity.this);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setSupportMultipleWindows(false);          // "new tab" links stay in this view
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            applyMode();

            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.addJavascriptInterface(new Bridge(this), "DMOnly");
            web.setWebChromeClient(chrome);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    return handleNavigation(Tab.this, request.getUrl());
                }

                @Override
                public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                    onUrlChanged(Tab.this, url);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    injectHelpers(Tab.this);
                    onUrlChanged(Tab.this, url);
                }
            });
            root = web;
        }

        void applyMode() {
            WebSettings s = web.getSettings();
            int mode = kind == Kind.MESSENGER ? fbMode() : FB_MOBILE;
            // Facebook's mobile site no longer lets you message, so Messenger uses the desktop site.
            s.setUserAgentString(mode == FB_MOBILE ? mobileUA : desktopUA);
            s.setUseWideViewPort(mode == FB_WIDE);
            s.setLoadWithOverviewMode(mode == FB_WIDE);
        }
    }

    /** Lets the injected script report Instagram's in-page URL changes. */
    final class Bridge {
        private final Tab tab;

        Bridge(Tab tab) {
            this.tab = tab;
        }

        @JavascriptInterface
        public void onUrl(String url) {
            main.post(() -> onUrlChanged(tab, url));
        }

        /** Create mode: Instagram's create screen / menu opened (true) or closed (false). */
        @JavascriptInterface
        public void creatingState(boolean busy) {
            if (tab != feedTab) return;
            main.post(() -> {
                if (!createMode) return;
                createBusy = busy;
                if (busy) createSawBusy = true;
                else scheduleCreateCheck();          // shared, or cancelled
            });
        }

        /** Swipe-to-reply: a real screen tap at page coordinates (see tapAt). */
        @JavascriptInterface
        public void tap(String json) {
            main.post(() -> tapAt(tab.web, json));
        }

        /** Story strip: height (CSS px) of the story-circles row, so the strip fits it exactly. */
        @JavascriptInterface
        public void trayHeight(int cssPx) {
            if (!tab.isTray) return;
            main.post(() -> {
                float d = getResources().getDisplayMetrics().density;
                int px = Math.max(dp(60), Math.min(dp(240), Math.round(cssPx * d) + dp(4)));
                if (Math.abs(px - trayHeightPx) > 2) {
                    trayHeightPx = px;
                    updateStoriesLayout();
                }
            });
        }
    }

    // ------------------------------------------------------------------ the filter

    /** Called BEFORE a full page load. Returns true to cancel it. */
    private boolean handleNavigation(Tab tab, Uri uri) {
        switch (evaluate(tab, uri)) {
            case ALLOW:
                return false;
            case EXTERNAL:
                openExternal(uri);
                return true;
            case BOUNCE:
                notifyBlocked();
                return true;
            case IGNORE:
            default:
                return true;
        }
    }

    /** Called AFTER the URL changed (catches swipes inside Instagram's reel viewer). */
    private void onUrlChanged(Tab tab, String url) {
        if (url == null || url.equals(tab.lastSeenUrl)) return;
        tab.lastSeenUrl = url;
        Uri uri = Uri.parse(url);
        String scheme = lower(uri.getScheme());
        if (!scheme.equals("https") && !scheme.equals("http")) return;

        // Messenger: leaving a chat back to the inbox -> fresh reload, so the chat list
        // always shows (Facebook often fails to redraw it at phone width).
        String path = uri.getPath() == null ? "" : uri.getPath();
        boolean wasThread = tab.lastPath.matches("^/messages/.*t/.+");
        String prevPath = tab.lastPath;
        tab.lastPath = path;
        if (tab.kind == Kind.IG_STORIES) updateStoriesLayout();
        if (tab.kind == Kind.MESSENGER && fbMode() == FB_FIT && isFbHost(lower(uri.getHost()))
                && wasThread && path.matches("^/messages/?$")) {
            tab.web.loadUrl(MESSENGER_URL);
            return;
        }

        // Stories feed: going back from a profile / search can land on Instagram's normal home
        // feed (with its own second story row) instead of the Following feed. Put it back.
        if (tab == feedTab && createMode && !path.startsWith("/create") && !path.startsWith("/stories")
                && !path.equals("/") && !path.isEmpty() && isIgHost(lower(uri.getHost()))) {
            endCreate();                          // wandered off (e.g. a profile): normal rules again
            return;
        }
        if (tab == feedTab && !unlocked() && !createMode && isIgHost(lower(uri.getHost())) && isLoggedIn(true)) {
            long now = SystemClock.elapsedRealtime();
            boolean cameBack = !prevPath.isEmpty() && !prevPath.equals("/");
            if (path.equals("/") && cameBack && !"following".equals(uri.getQueryParameter("variant"))
                    && now - feedFixAt > 8000) {
                feedFixAt = now;
                tab.web.loadUrl(IG_STORIES_URL);
                return;
            }
            // Instagram's own back arrow from a search result can go to the Explore grid:
            // send it to the search page instead of showing "Feed's blocked".
            if (path.matches("^/explore/?$") && now - feedFixAt > 4000) {
                feedFixAt = now;
                tab.web.loadUrl("https://www.instagram.com/explore/search/");
                return;
            }
        }

        Verdict v = evaluate(tab, uri);
        if (v == Verdict.EXTERNAL) {
            openExternal(uri);
            bounce(tab);
        } else if (v == Verdict.BOUNCE) {
            bounce(tab);
        }
    }

    private Verdict evaluate(Tab tab, Uri uri) {
        String scheme = lower(uri.getScheme());
        if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data")
                || scheme.equals("javascript")) {
            return Verdict.ALLOW;
        }
        if (!scheme.equals("https") && !scheme.equals("http")) {
            if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms") || scheme.equals("geo")) {
                openExternal(uri);
            }
            // intent:, instagram:, fb:, market: ... = "open the real app" prompts. Ignore them.
            return Verdict.IGNORE;
        }

        String host = lower(uri.getHost());
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();

        // l.instagram.com/?u=... and l.facebook.com/l.php?u=... are link wrappers.
        if (host.equals("l.instagram.com") || host.equals("l.facebook.com")
                || host.equals("lm.facebook.com") || host.equals("l.messenger.com")) {
            String target = uri.getQueryParameter("u");
            if (target != null) {
                Uri t = Uri.parse(target);
                String th = lower(t.getHost());
                if (isIgHost(th) || isFbHost(th)) tab.web.loadUrl(target);
                else openExternal(t);
            }
            return Verdict.IGNORE;
        }

        boolean ig = isIgHost(host);
        boolean fb = isFbHost(host);
        if (!ig && !fb) return Verdict.EXTERNAL;

        // Feed break: normal Instagram, nothing blocked until the timer runs out.
        if (ig && unlocked()) return Verdict.ALLOW;

        // Your saved posts / collections (the "Save to collection" picker can open these).
        if (ig && path.matches("^/[^/]+/saved(/.*)?$")) return Verdict.ALLOW;

        // messenger.com just redirects to facebook.com/messages now.
        if (host.equals("messenger.com") || host.endsWith(".messenger.com")) return Verdict.ALLOW;

        if (isAuth(path)) return Verdict.ALLOW;

        // Not signed in yet: there's no feed to block, and the login form often lives
        // at the home page, so let everything through until you've signed in.
        if (!isLoggedIn(ig)) return Verdict.ALLOW;

        boolean tabIsIg = tab.kind != Kind.MESSENGER;

        // Chats
        if ((ig && path.startsWith("/direct")) || (fb && path.startsWith("/messages"))) {
            if (ig == tabIsIg) tab.lastSafeUrl = uri.toString();
            clearMedia(tab);
            return Verdict.ALLOW;
        }

        // Stories (auto-advancing through friends' stories is fine)
        if (path.startsWith("/stories")) {
            clearMedia(tab);
            return Verdict.ALLOW;
        }

        // Making a post or a story
        if (ig && path.matches("^/create(/.*)?$")) return Verdict.ALLOW;

        // Searching for people (the Explore grid itself stays blocked)
        if (ig && path.startsWith("/explore/search")) return Verdict.ALLOW;

        // Someone's profile (+ their reels / tagged / followers / following lists).
        // Reels you open from a profile still bounce back here if you swipe to the next one.
        if (ig && isIgProfile(path)) {
            if (tabIsIg) tab.lastSafeUrl = uri.toString();
            clearMedia(tab);
            return Verdict.ALLOW;
        }

        // A post (/p/...) you tapped - from a chat, a profile or "more posts". Posts don't
        // auto-play into the next one, so they're always fine; only reels get swipe-blocked.
        if (ig && path.matches("^/(?:[A-Za-z0-9._]+/)?p/[A-Za-z0-9_-]+.*")) return Verdict.ALLOW;

        // Instagram home: only in the Stories tab, where the feed is hidden and only
        // the stories row shows.
        if (ig && path.equals("/")) {
            if (tab.kind == Kind.IG_STORIES) {
                tab.lastSafeUrl = tab.startUrl;
                clearMedia(tab);
                return Verdict.ALLOW;
            }
            return Verdict.BOUNCE;
        }

        // A reel / post / video someone sent you
        String key = ig ? igMediaKey(path) : fbMediaKey(host, path, uri);
        if (key != null) {
            boolean share = key.startsWith("share:");
            if (tab.mediaKey == null || tab.mediaKey.equals(key) || (tab.mediaFromShare && !share)) {
                tab.mediaKey = key;
                tab.mediaFromShare = share;
                return Verdict.ALLOW;
            }
            tab.swipeBounce = true;  // you swiped to the next one: back to the chat / profile
            return Verdict.BOUNCE;
        }

        // Feed, Explore, Reels tab, profiles, Watch, Marketplace, ...
        return Verdict.BOUNCE;
    }

    private void bounce(Tab tab) {
        long now = SystemClock.elapsedRealtime();
        if (now - tab.bounceWindowStart > 4000) {
            tab.bounceWindowStart = now;
            tab.bounceCount = 0;
        }
        tab.bounceCount++;
        if (tab.bounceCount > 6) {   // something keeps redirecting: stop rather than loop forever
            toast("Couldn't get back to your chats. Tap the tab to reset.");
            return;
        }
        // Stories feed: blocked pages go back to the Following feed, except reel swipes, which
        // go back to the profile (or chat) you opened the reel from.
        if (tab == feedTab && createMode) {
            notifyBlocked();
            endCreate();
            return;
        }
        String target = tab.bounceCount > 3 ? tab.startUrl
                : (tab == feedTab && !tab.swipeBounce) ? tab.startUrl : tab.lastSafeUrl;
        tab.swipeBounce = false;
        clearMedia(tab);
        notifyBlocked();
        tab.web.loadUrl(target);
    }

    private static void clearMedia(Tab tab) {
        tab.mediaKey = null;
        tab.mediaFromShare = false;
    }

    private static boolean isLoggedIn(boolean ig) {
        String c = CookieManager.getInstance().getCookie(
                ig ? "https://www.instagram.com/" : "https://www.facebook.com/");
        if (c == null) return false;
        return ig ? (c.contains("sessionid=") || c.contains("ds_user_id=")) : c.contains("c_user=");
    }

    // First path segments that are Instagram pages, not usernames.
    private static final Pattern IG_RESERVED = Pattern.compile(
            "^(explore|reels?|p|tv|stories|direct|accounts|create|about|legal|developer|web|emails|"
                    + "challenge|session|oauth|privacy|terms|api|graphql|ajax|static|data|nametag|lite|"
                    + "push|topics|locations|tags|audio|music|your_activity|notifications|threads|"
                    + "qp|ar|fragment|invites|press|jobs|blog|help|download)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IG_PROFILE = Pattern.compile(
            "^/([A-Za-z0-9._]{1,30})(/(reels|tagged|followers|following|saved))?/?$");

    private static boolean isIgProfile(String path) {
        Matcher m = IG_PROFILE.matcher(path);
        return m.matches() && !IG_RESERVED.matcher(m.group(1)).matches();
    }

    private static boolean isAuth(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return AUTH.matcher(path).find() || p.contains("login") || p.contains("checkpoint");
    }

    private static String igMediaKey(String path) {
        Matcher m = IG_MEDIA.matcher(path);
        if (!m.find()) return null;
        String id = m.group(2);
        if (id.equals("audio")) return null;   // "reels using this sound" = a feed
        return id;
    }

    private static String fbMediaKey(String host, String path, Uri uri) {
        if (host.equals("fb.watch")) return "share:" + path;
        Matcher m = FB_SHARE.matcher(path);
        if (m.find()) return "share:" + m.group(1);
        m = FB_VIDEO.matcher(path);
        if (m.find()) return m.group(1);
        if (path.startsWith("/watch")) return uri.getQueryParameter("v");   // /watch with no v = feed
        if (path.startsWith("/photo") || path.startsWith("/story.php") || path.startsWith("/permalink.php")) {
            String id = uri.getQueryParameter("fbid");
            if (id == null) id = uri.getQueryParameter("story_fbid");
            return id;
        }
        m = FB_POST.matcher(path);
        if (m.find()) return m.group(1);
        return null;
    }

    private static boolean isIgHost(String h) {
        return h.equals("instagram.com") || h.endsWith(".instagram.com");
    }

    private static boolean isFbHost(String h) {
        return h.equals("facebook.com") || h.endsWith(".facebook.com")
                || h.equals("fb.com") || h.endsWith(".fb.com") || h.equals("fb.watch")
                || h.equals("messenger.com") || h.endsWith(".messenger.com");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ page helpers

    /** Hides feed links/buttons and watches for in-page URL changes. */
    private void injectHelpers(Tab tab) {
        if (tab.kind != Kind.MESSENGER && unlocked()) return;   // feed break: leave Instagram as-is
        if (tab == feedTab && createMode) {
            injectCreate(tab);
            return;
        }
        String css;
        switch (tab.kind) {
            case IG_DMS:
                css = "a[href=\"/explore/\"],a[href=\"/reels/\"],a[href^=\"/reels/?\"],a[href=\"/\"],"
                        + ".dmonly-nav{display:none!important}";
                break;
            case IG_STORIES:
                // On the home page: stories + posts from people you follow. Hide the nav bars,
                // the DM / notification / create buttons, and suggested / sponsored posts.
                css = "a[href=\"/explore/\"],a[href=\"/reels/\"],a[href^=\"/reels/?\"],.dmonly-nav{display:none!important}"
                        + "body.dmonly-home nav,"
                        + "body.dmonly-home [role=\"navigation\"],"
                        + "body.dmonly-home footer,"
                        + "body.dmonly-home a[href^=\"/direct\"],"
                        + "body.dmonly-home a[href*=\"/accounts/activity\"],"
                        + "body.dmonly-home a[href^=\"/notifications\"],"
                        + "body.dmonly-home a[href^=\"/create\"],"
                        + "body.dmonly-home a[href=\"/\"],"
                        + "body.dmonly-home [role=\"progressbar\"],"
                        + "body.dmonly-home svg[aria-label^=\"Loading\"],"
                        + "body.dmonly-home .dmonly-sib,"
                        + "body.dmonly-home .dmonly-hide{display:none!important}";
                break;
            case MESSENGER:
            default:
                // Hide the feed bits of Facebook's top bar but keep the bar itself (it has the
                // phone-layout controls). On the inbox page, force the chat list to show.
                css = "a[href=\"/\"],a[href^=\"/marketplace\"],a[href^=\"/notifications\"],"
                        + "a[href^=\"/watch\"],a[href^=\"/reel\"],a[href^=\"/groups\"],a[href^=\"/gaming\"],"
                        + "[aria-label=\"Facebook menu\"],[aria-label^=\"Notifications\"],"
                        + "input[aria-label=\"Search Facebook\"]{display:none!important}"
                        + "body.dmfb-list [role=\"main\"]{display:none!important}"
                        + "body.dmfb-list [role=\"navigation\"][aria-label=\"Thread list\"]{"
                        + "display:flex!important;visibility:visible!important;width:auto!important;"
                        + "max-width:none!important;flex:1 1 auto!important;transform:none!important}";
                break;
        }
        boolean storiesHome = tab.kind == Kind.IG_STORIES && isLoggedIn(true);
        boolean fbFit = tab.kind == Kind.MESSENGER && fbMode() == FB_FIT;
        boolean igTab = tab.kind != Kind.MESSENGER && isLoggedIn(true);
        String js = "(function(){"
                + "if(window.__dmonly)return;window.__dmonly=true;"
                + "var css=" + JSONObject.quote(css) + ";"
                + "var STORIES=" + storiesHome + ";"
                + "var FBFIT=" + fbFit + ";"
                + "var IGTAB=" + igTab + ";"
                + "var TRAY=" + tab.isTray + ";"
                // Instagram's bottom tab bar (Home / Search / Reels / Messages / Profile), on any
                // page. Found via its Explore/Reels links so comment and reply boxes are untouched.
                // Chats: swipe a message sideways to reply to it (like the Instagram app).
                // The message slides with your finger; let go past ~60px and the page's own
                // "Reply" button for that message is pressed for you.
                + "if(IGTAB){(function(){"
                + "var sx=0,sy=0,row=null,tgt=null,dx=0,on=false,dead=false;"
                + "function thread(){return location.pathname.indexOf('/direct/')===0;}"
                + "function vis(e){var b=e.getBoundingClientRect();return b.width>0&&b.height>0&&b.bottom>0&&b.top<innerHeight;}"
                + "function rowOf(e){var r=e.closest('[role=row]');if(r)return r;"
                + " for(var n=e;n&&n!==document.body;n=n.parentElement){var b=n.getBoundingClientRect();"
                + "  if(b.height>innerHeight*0.5)return null;"
                + "  if(b.width>=innerWidth*0.85)return n;}return null;}"
                + "function cands(sc){return [].slice.call(sc.querySelectorAll('[aria-label],button,[role=button],span,div')).filter(function(e){"
                + "  var a=e.getAttribute('aria-label')||'',t=e.childElementCount===0?(e.textContent||'').trim():'';"
                + "  return (/^reply\\b/i.test(a)||/^reply( to this message)?$/i.test(t))&&vis(e)&&!e.closest('[contenteditable=true],textarea');});}"
                + "function findReply(sc,near,old){"
                + " var c=cands(sc);"
                + " if(old)c=c.filter(function(e){return old.indexOf(e)<0;});"   // only what the tap just opened
                + " if(!c.length)return null;"
                + " if(near){var nt=near.getBoundingClientRect().top;c.sort(function(a,b){"
                + "  return Math.abs(a.getBoundingClientRect().top-nt)-Math.abs(b.getBoundingClientRect().top-nt);});}"
                + " var e=c[0];return e.closest('button,[role=button],a')||e;}"
                + "function tapEl(e,cx){var b=e.getBoundingClientRect();"
                + " var j=JSON.stringify({x:cx!=null?cx:b.left+b.width/2,y:b.top+b.height/2,iw:innerWidth});"
                + " try{DMOnly.tap(j);}catch(x){e.click();}}"
                + "function hover(t){['pointerover','pointerenter','mouseover','mouseenter','mousemove'].forEach(function(n){"
                + " try{t.dispatchEvent(new MouseEvent(n,{bubbles:true,view:window}));}catch(x){}});}"
                + "function reply(r,t){"
                + " var b=findReply(r);if(b){tapEl(b);return;}"
                + " hover(t);"
                // Then tap the message so its Reply button appears, and keep looking for it for a
                // while (it can take a moment, or the first tap only closes the keyboard).
                + " var before=cands(document),taps=0,tries=0;"
                + " function look(){var b=findReply(r)||findReply(document,r,before);"
                + "  if(b){tapEl(b);return;}"
                + "  tries++;"
                + "  if(tries===1||(tries===9&&taps<2)){"
                + "   if(t.closest('a,img,video')||!document.contains(t))return;"   // tapping a photo / link would open it
                + "   taps++;tapEl(t);}"
                + "  if(tries<20)setTimeout(look,150);}"
                + " setTimeout(look,120);}"
                + "document.addEventListener('touchstart',function(e){row=null;"
                + " if(!thread()||e.touches.length!==1)return;"
                + " var t=e.target;if(!t.closest||t.closest('textarea,input,[contenteditable=true],[role=textbox]'))return;"
                + " var r=rowOf(t);if(!r||r.querySelector('textarea,[contenteditable=true],[role=textbox]'))return;"
                + " row=r;tgt=t;sx=e.touches[0].clientX;sy=e.touches[0].clientY;dx=0;on=false;dead=false;"
                + "},{passive:true,capture:true});"
                + "document.addEventListener('touchmove',function(e){if(!row||dead)return;"
                + " var mx=e.touches[0].clientX-sx,my=e.touches[0].clientY-sy;"
                + " if(!on){if(Math.abs(my)>14&&Math.abs(my)>=Math.abs(mx)){dead=true;return;}"
                + "  if(Math.abs(mx)>14&&Math.abs(mx)>Math.abs(my)*1.5)on=true;else return;}"
                + " dx=mx;row.style.transition='none';"
                + " row.style.transform='translateX('+Math.max(-80,Math.min(80,dx))+'px)';"
                + "},{passive:true,capture:true});"
                + "function end(){if(!row)return;var r=row,t=tgt,go=on&&Math.abs(dx)>60;row=null;"
                + " r.style.transition='transform .18s';r.style.transform='';"
                + " if(go)setTimeout(function(){reply(r,t);},200);}"
                + "document.addEventListener('touchend',end,{passive:true,capture:true});"
                + "document.addEventListener('touchcancel',end,{passive:true,capture:true});"
                + "})();}"
                + "function hideNav(){"
                + " if(!IGTAB)return;"
                + " document.querySelectorAll('a[href=\"/explore/\"],a[href^=\"/reels\"]').forEach(function(a){"
                + "  for(var e=a;e&&e!==document.body;e=e.parentElement){"
                + "   var p=getComputedStyle(e).position;"
                + "   if((p==='fixed'||p==='sticky')&&e.getBoundingClientRect().height<140){e.classList.add('dmonly-nav');return;}"
                + "  }"
                + "  var n=a.closest('nav,[role=navigation]');if(n)n.classList.add('dmonly-nav');"
                + " });"
                + "}"
                // Facebook inbox at phone width: show the chat list instead of the empty chat panel.
                + "function fbList(){"
                + " if(!FBFIT||!document.body)return;"
                + " var inbox=/^\\/messages\\/?$/.test(location.pathname);"
                + " document.body.classList.toggle('dmfb-list',inbox);"
                + " if(!inbox){fbMiss=0;return;}"
                + " var l=document.querySelector('[role=navigation][aria-label=\"Thread list\"]');"
                + " for(var e=l&&l.parentElement;e&&e!==document.body;e=e.parentElement){"
                + "  if(getComputedStyle(e).display==='none'){e.style.setProperty('display','flex','important');e.dataset.dmfb='1';}"
                + " }"
                // After leaving a chat, Facebook sometimes doesn't bring the list back until the
                // screen size changes (e.g. rotating). Nudge it with a resize, then reload once.
                + " var ok=l&&l.getBoundingClientRect().width>50&&l.getBoundingClientRect().height>50;"
                + " if(ok){fbMiss=0;return;}"
                + " if(!document.querySelector('[role=main]'))return;"   // page still loading
                + " fbMiss++;"
                + " if(fbMiss===2||fbMiss===4)window.dispatchEvent(new Event('resize'));"
                + " if(fbMiss===8){var t=0;try{t=+sessionStorage.getItem('dmfbReload')||0;}catch(x){}"
                + "  if(Date.now()-t>5000){try{sessionStorage.setItem('dmfbReload',Date.now());}catch(x){}location.reload();}}"
                + "}"
                + "var fbMiss=0;"
                // Hide any bar pinned to the top/bottom edge (Instagram's tab bar + top bar).
                + "function hideBars(){"
                + " if(!STORIES||TRAY||location.pathname!=='/')return;"
                // Only the top bar's icons. (Pop-ups like "Save to collection" are left alone;
                // the bottom tab bar is handled by hideNav.)
                + " document.querySelectorAll('svg[aria-label]').forEach(function(s){"
                + "  if(s.closest('[role=dialog]')||s.getBoundingClientRect().top>80)return;"
                + "  var l=s.getAttribute('aria-label').toLowerCase();"
                + "  if(/messenger|direct|notification|activity|new post|home|search|explore|reels/.test(l)){"
                + "   var b=s.closest('a,button,[role=button]');if(b)b.classList.add('dmonly-hide');}"
                + " });"
                + "}"
                // Feed clean-up: hide sponsored / suggested posts, the "Suggested for you"
                // account carousels, and everything after a "Suggested posts" heading.
                + "function xp(q){var r=document.evaluate(q,document,null,7,null),a=[];"
                + " for(var i=0;i<r.snapshotLength;i++)a.push(r.snapshotItem(i));return a;}"
                // Instagram removes posts you've scrolled past, so climb only a few small levels
                // (never a big container) - otherwise this could hide the whole feed.
                + "function hideBlock(e){var n=e;"
                + " for(var k=0;k<6&&n.parentElement&&n.parentElement!==document.body;k++){"
                + "  var p=n.parentElement;"
                + "  if(p.querySelector('article,canvas')||p.getBoundingClientRect().height>innerHeight*0.6)break;"
                + "  n=p;}"
                + " n.classList.add('dmonly-hide');}"
                + "function cleanFeed(){"
                + " if(!STORIES||TRAY||location.pathname!=='/')return;"
                + " xp(\"//article[.//*[not(*) and (normalize-space(.)='Sponsored' or normalize-space(.)='Suggested for you')]]\")"
                + "  .forEach(function(a){a.classList.add('dmonly-hide');});"
                + " var m=xp(\"//*[not(*) and translate(normalize-space(.),'SP','sp')='suggested posts']\")[0];"
                + " if(m){document.querySelectorAll('article').forEach(function(a){"
                + "  if(m.compareDocumentPosition(a)&4)a.classList.add('dmonly-hide');});hideBlock(m);}"
                + " xp(\"//*[not(*) and (normalize-space(.)='Suggested for you' or translate(normalize-space(.),'SR','sr')='suggested reels')]\").forEach(function(e){"
                + "  if(!e.closest('article'))hideBlock(e);});"
                + "}"
                // Story strip: keep only the row of story circles (rings are <canvas>, links go
                // to /stories/), hide everything around it, and tell the app how tall it is.
                + "var trayH=0;"
                // Feed (below the strip): if Instagram shows its own story row there too, hide it.
                + "function feedNoTray(){"
                + " if(!STORIES||TRAY||location.pathname!=='/')return;"
                + " var c=[].slice.call(document.querySelectorAll('canvas,a[href^=\"/stories/\"]')).filter(function(e){"
                + "  return !e.closest('article')&&!e.closest('.dmonly-hide');});"
                + " if(c.length<2)return;"
                + " var t=c[0];"
                + " for(var i=1;i<c.length&&t;i++){while(t&&!t.contains(c[i]))t=t.parentElement;}"
                + " if(t&&t!==document.body&&t.getBoundingClientRect().height<260)t.classList.add('dmonly-hide');"
                + "}"
                + "function trayOnly(){"
                + " if(!STORIES||!TRAY||location.pathname!=='/')return;"
                + " var c=[].slice.call(document.querySelectorAll('canvas,a[href^=\"/stories/\"]')).filter(function(e){"
                + "  return !e.closest('article')&&!e.closest('.dmonly-sib')&&!e.closest('.dmonly-hide');});"
                + " if(c.length<2)return;"
                + " var t=c[0];"
                + " for(var i=1;i<c.length&&t;i++){while(t&&!t.contains(c[i]))t=t.parentElement;}"
                + " if(!t||t===document.body||t===document.documentElement)return;"
                + " var n=t;"
                + " while(n&&n.parentElement&&n!==document.body){"
                + "  var p=n.parentElement;"
                + "  for(var k=0;k<p.children.length;k++){var s=p.children[k];"
                + "   if(s===n||s.tagName==='SCRIPT'||s.tagName==='STYLE'||s.id==='dmonly-css')continue;"
                + "   if(s.getAttribute('role')==='dialog'||s.querySelector('[role=dialog]'))continue;"
                + "   s.classList.add('dmonly-sib');}"
                + "  n=p;}"
                + " if(window.scrollY)window.scrollTo(0,0);"
                + " var h=Math.ceil(t.getBoundingClientRect().bottom);"
                + " if(h>30&&Math.abs(h-trayH)>2){trayH=h;try{DMOnly.trayHeight(h);}catch(x){}}"
                + "}"
                + "function ensure(){"
                + " var s=document.getElementById('dmonly-css');"
                + " if(!s){s=document.createElement('style');s.id='dmonly-css';s.textContent=css;"
                + "  (document.head||document.documentElement).appendChild(s);}"
                + " if(document.body)document.body.classList.toggle('dmonly-home',"
                + "  STORIES&&location.pathname==='/');"
                + " try{feedNoTray();}catch(e){}"
                + " try{hideBars();}catch(e){}"
                + " try{cleanFeed();}catch(e){}"
                + " try{trayOnly();}catch(e){}"
                + " try{hideNav();}catch(e){}"
                + " try{fbList();}catch(e){}"
                + "}"
                + "var last='';"
                + "setInterval(function(){ensure();"
                + " if(location.href!==last){last=location.href;try{DMOnly.onUrl(last);}catch(e){}}"
                + "},400);"
                + "ensure();"
                + "})();";
        tab.web.evaluateJavascript(js, null);
    }

    private void notifyBlocked() {
        toast("Feed's blocked. Back to your chats.");
    }

    private void toast(String msg) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastToast < 2500) return;
        lastToast = now;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No app can open that link.");
        }
    }

    // ------------------------------------------------------------------ uploads, mic, fullscreen video

    private final WebChromeClient chrome = new WebChromeClient() {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            String host = lower(request.getOrigin().getHost());
            if (!isIgHost(host) && !isFbHost(host)) {
                request.deny();
                return;
            }
            List<String> need = new ArrayList<>();
            for (String r : request.getResources()) {
                String p = androidPermissionFor(r);
                if (p != null && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
            if (need.isEmpty()) {
                request.grant(request.getResources());
            } else {
                if (pendingPermission != null) pendingPermission.deny();
                pendingPermission = request;
                requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);   // full-screen video can rotate
            view.setBackgroundColor(Color.BLACK);
            rootLayout.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    };

    private static String androidPermissionFor(String resource) {
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) return Manifest.permission.RECORD_AUDIO;
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return Manifest.permission.CAMERA;
        return null;
    }

    private void hideCustomView() {
        if (customView == null) return;
        rootLayout.removeView(customView);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        applyOrientation();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_IG_PICK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) sendToInstagramApp(data.getData());
            return;
        }
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            if (createMode) {                    // picked nothing -> back to the feed
                createSawBusy = true;
                scheduleCreateCheck();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMS || pendingPermission == null) return;
        List<String> grant = new ArrayList<>();
        for (String r : pendingPermission.getResources()) {
            String p = androidPermissionFor(r);
            if (p == null || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) grant.add(r);
        }
        if (grant.isEmpty()) pendingPermission.deny();
        else pendingPermission.grant(grant.toArray(new String[0]));
        pendingPermission = null;
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        boolean storyOpen = current == feedTab && trayTab.web.getVisibility() == View.VISIBLE
                && feedTab.web.getVisibility() == View.GONE;
        if (customView != null) {
            hideCustomView();
        } else if (current == feedTab && createMode) {
            // Inside Instagram's editor, step back; otherwise cancel and return to the feed.
            if (feedTab.lastPath.startsWith("/create") && feedTab.web.canGoBack()) feedTab.web.goBack();
            else endCreate();
        } else if (storyOpen) {
            loadTray();                         // close the story, back to the feed
        } else if (current == feedTab && !unlocked() && feedTab.lastPath.startsWith("/explore")) {
            feedFixAt = SystemClock.elapsedRealtime();
            feedTab.web.loadUrl(IG_STORIES_URL);   // search: one press back to the feed
        } else if (current == feedTab && !unlocked()
                && (feedTab.lastPath.isEmpty() || feedTab.lastPath.equals("/"))) {
            moveTaskToBack(true);               // already on the feed
        } else if (current != null && current.web.canGoBack()) {
            current.web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();   // stay logged in
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wasUnlocked && !unlocked()) endUnlock(false);   // timer ran out while you were away
        refreshRelayUi();                                   // back from the notification-access screen
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        for (Tab t : tabs) {
            t.web.destroy();
        }
        if (trayTab != null) trayTab.web.destroy();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
