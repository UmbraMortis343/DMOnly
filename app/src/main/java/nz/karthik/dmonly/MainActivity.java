package nz.karthik.dmonly;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
import android.view.Gravity;
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

    // Settings tab + feed break (temporarily unblocks normal Instagram, max 1 hour)
    private TextView settingsButton;
    private View settingsView;
    private long unlockUntil;
    private boolean wasUnlocked;
    private Button unlockButton;
    private TextView unlockStatus;
    private TextView unlockMinutesLabel;
    private SeekBar unlockSeek;
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
        divider.setBackgroundColor(0x33888888);
        column.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(getColor(R.color.bar_bg));
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        rootLayout.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);

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
        main.post(ticker);

        // Quietly check for a newer version at most twice a day.
        if (System.currentTimeMillis() - prefs.getLong("update_checked", 0) > 12 * 3600_000L) {
            checkForUpdates(false);
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
                    if (a.optString("name").endsWith(".apk")) apkUrl = a.optString("browser_download_url");
                }
                final String url = apkUrl;
                main.post(() -> {
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
        boolean storyOpen = !tp.isEmpty() && !tp.equals("/");   // a story (or a reply chat) is open
        String fp = feedTab.lastPath;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) trayTab.web.getLayoutParams();
        if (!loggedIn || unlocked()) {        // feed break: plain Instagram, no strip
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
    }

    private int fbMode() {
        int m = prefs.getInt("fb_mode", FB_FIT);
        return (m < FB_FIT || m > FB_MOBILE) ? FB_FIT : m;
    }

    private void onTabTap(Tab tab) {
        if (tab == current) {
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
        tab.lastPath = path;
        if (tab.kind == Kind.IG_STORIES) updateStoriesLayout();
        if (tab.kind == Kind.MESSENGER && fbMode() == FB_FIT && isFbHost(lower(uri.getHost()))
                && wasThread && path.matches("^/messages/?$")) {
            tab.web.loadUrl(MESSENGER_URL);
            return;
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
            return Verdict.BOUNCE;   // you swiped to the next one
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
        String target = tab.bounceCount > 3 ? tab.startUrl : tab.lastSafeUrl;
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
                + " if(document.body)document.body.classList.toggle('dmonly-home',STORIES&&location.pathname==='/');"
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
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
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
        } else if (storyOpen) {
            trayTab.web.loadUrl(IG_TRAY_URL);   // close the story, back to the feed
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
