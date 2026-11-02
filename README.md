# DM Only

Instagram DMs, Instagram Stories and Messenger — with the feeds blocked.

**Tabs:** DMs · Stories · Messenger

- Reels/posts/videos friends send you open normally. Swipe to the next one and you're sent back to the chat.
- Stories tab shows the stories row only (the feed under it is hidden).
- Home feed, Explore, Reels tab, profiles, Watch and Marketplace are blocked.
- Links to other websites open in your normal browser.
- Tap the tab you're on to jump back to the inbox. Long-press a tab for Reload / Back to start (and, on Messenger, a desktop-site switch if the mobile site won't load).

## Build it — option A: GitHub (no installs)

1. On a computer, go to github.com → **New repository** → name it `DMOnly` → **Private** → Create.
2. On the empty repo page click **uploading an existing file**, then drag in **everything inside** this folder. Commit.
   - The `.github` folder is hidden. If it didn't upload (check for a `.github` folder in the repo), click **Add file → Create new file**, type `.github/workflows/build.yml` as the name, paste in that file's contents, and commit.
3. Open the **Actions** tab and wait ~4 minutes for the green tick.
4. On your phone, open the repo → **Releases** → **latest** → tap `DMOnly.apk`.
5. Android will ask to allow installs from your browser — allow it, then install.

Every time you change a file and commit, a fresh APK appears under Releases. It installs over the old one without logging you out.

## Build it — option B: Android Studio

1. Install Android Studio, then **File → Open** this folder.
2. Let it sync (it may offer to add a Gradle wrapper — say yes).
3. Plug in your phone with USB debugging on and press **Run ▶**.

## Notes

- **No notifications.** Websites inside an app can't push notifications. Keep the official apps installed with notifications on (or check this app regularly).
- Instagram changes its site often. If something slips through or gets wrongly blocked, the rules are all in `evaluate()` and `injectHelpers()` in `MainActivity.java`.
- The signing key (`app/sideload.keystore`, password `android`) is just for sideloading so updates install cleanly. Don't use it for anything else.
