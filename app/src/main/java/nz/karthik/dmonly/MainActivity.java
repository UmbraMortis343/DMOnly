package nz.karthik.dmonly;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DM Only: Instagram DMs, Instagram Stories and Messenger, with the feeds blocked.
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
    private static final String IG_STORIES_URL = "https://www.instagram.com/";
    private static final String MESSENGER_URL = "https://www.facebook.com/messages/";

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

        tabs.add(new Tab(Kind.IG_DMS, "DMs", IG_DMS_URL));
        tabs.add(new Tab(Kind.IG_STORIES, "Stories", IG_STORIES_URL));
        tabs.add(new Tab(Kind.MESSENGER, "Messenger", MESSENGER_URL));

        for (Tab t : tabs) {
            bar.addView(t.button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            content.addView(t.web, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        int last = prefs.getInt("tab", 0);
        if (last < 0 || last >= tabs.size()) last = 0;
        select(tabs.get(last));
    }

    private void select(Tab tab) {
        current = tab;
        for (Tab t : tabs) {
            boolean on = t == tab;
            t.web.setVisibility(on ? View.VISIBLE : View.GONE);
            t.button.setTextColor(getColor(on ? R.color.bar_active : R.color.bar_fg));
            t.button.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (!tab.loaded) {          // tabs only load the first time you open them
            tab.loaded = true;
            tab.web.loadUrl(tab.startUrl);
        }
        prefs.edit().putInt("tab", tabs.indexOf(tab)).apply();
    }

    private int fbMode() {
        int m = prefs.getInt("fb_mode", FB_FIT);
        return (m < FB_FIT || m > FB_MOBILE) ? FB_FIT : m;
    }

    private void onTabTap(Tab tab) {
        if (tab == current) {
            tab.web.loadUrl(tab.startUrl);   // tap the tab you're on = back to the inbox
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
                    } else if (which == 1) {
                        tab.web.loadUrl(tab.startUrl);
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
        final String startUrl;
        final WebView web;
        final TextView button;

        boolean loaded;
        String lastSafeUrl;     // last chat/inbox URL: where we send you back to
        String mediaKey;        // the one reel/post you're allowed to be watching
        boolean mediaFromShare; // share links redirect once to the real reel URL
        String lastSeenUrl;
        int bounceCount;
        long bounceWindowStart;

        @SuppressLint("SetJavaScriptEnabled")
        Tab(Kind kind, String label, String startUrl) {
            this.kind = kind;
            this.label = label;
            this.startUrl = startUrl;
            this.lastSafeUrl = startUrl;

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
                tab.lastSafeUrl = IG_STORIES_URL;
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
        String css;
        switch (tab.kind) {
            case IG_DMS:
                css = "a[href=\"/explore/\"],a[href=\"/reels/\"],a[href^=\"/reels/?\"],a[href=\"/\"]"
                        + "{display:none!important}";
                break;
            case IG_STORIES:
                // On the home page: hide the feed, the nav bars, and the DM / notification / create buttons.
                css = "a[href=\"/explore/\"],a[href=\"/reels/\"],a[href^=\"/reels/?\"]{display:none!important}"
                        + "body.dmonly-home main article,"
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
                        + "body.dmonly-home [data-visualcompletion=\"loading-state\"],"
                        + "body.dmonly-home .dmonly-sib,"
                        + "body.dmonly-home .dmonly-hide{display:none!important}";
                break;
            case MESSENGER:
            default:
                // [role=banner] = Facebook's top bar (Home, Video, Marketplace, notifications...)
                css = "a[href=\"/\"],a[href^=\"/marketplace\"],a[href^=\"/notifications\"],"
                        + "a[href^=\"/watch\"],a[href^=\"/groups\"],a[href^=\"/gaming\"],"
                        + "[role=\"banner\"]{display:none!important}";
                break;
        }
        boolean storiesHome = tab.kind == Kind.IG_STORIES && isLoggedIn(true);
        String js = "(function(){"
                + "if(window.__dmonly)return;window.__dmonly=true;"
                + "var css=" + JSONObject.quote(css) + ";"
                + "var STORIES=" + storiesHome + ";"
                // Hide any bar pinned to the top/bottom edge (Instagram's tab bar + top bar).
                + "function hideBars(){"
                + " if(!STORIES||location.pathname!=='/')return;"
                + " [innerHeight-8].forEach(function(y){"
                + "  var els=document.elementsFromPoint(innerWidth/2,y)||[];"
                + "  for(var i=0;i<els.length;i++){var e=els[i];"
                + "   while(e&&e!==document.body&&e!==document.documentElement){"
                + "    var p=getComputedStyle(e).position;"
                + "    if((p==='fixed'||p==='sticky')&&e.getBoundingClientRect().height<140){e.classList.add('dmonly-hide');break;}"
                + "    e=e.parentElement;}"
                + "  }"
                + " });"
                + " document.querySelectorAll('svg[aria-label]').forEach(function(s){"
                + "  var l=s.getAttribute('aria-label').toLowerCase();"
                + "  if(/messenger|direct|notification|activity|new post|create|home|search|explore|reels/.test(l)){"
                + "   var b=s.closest('a,button,[role=button]');if(b)b.classList.add('dmonly-hide');}"
                + " });"
                + "}"
                // Keep only the story circles: find the row that holds them (story rings are
                // <canvas>, story links go to /stories/), then hide everything around it.
                + "function trayOnly(){"
                + " if(!STORIES||location.pathname!=='/')return;"
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
                + "}"
                + "function ensure(){"
                + " var s=document.getElementById('dmonly-css');"
                + " if(!s){s=document.createElement('style');s.id='dmonly-css';s.textContent=css;"
                + "  (document.head||document.documentElement).appendChild(s);}"
                + " if(document.body)document.body.classList.toggle('dmonly-home',STORIES&&location.pathname==='/');"
                + " try{hideBars();}catch(e){}"
                + " try{trayOnly();}catch(e){}"
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
        if (customView != null) {
            hideCustomView();
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
    protected void onDestroy() {
        for (Tab t : tabs) {
            t.web.destroy();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
