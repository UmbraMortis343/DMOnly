# The Resistance

*Talk to your friends without being farmed.*

An Android app for Instagram DMs and Messenger without the feeds. Social apps are built to keep you scrolling;
this one keeps the useful part (your conversations) and refuses the rest.
Open source. **Not affiliated with, endorsed by, or connected to Meta, Instagram or Facebook.**
It shows the normal instagram.com and facebook.com websites and hides the parts that are built to keep you scrolling.

## What it does

| Tab | What you get |
|---|---|
| **DMs** | Instagram direct messages. Posts and reels friends send you open, but swiping to the next reel sends you back. |
| **Stories** | **Search**, **+ Post** and **+ Story** buttons, story circles, then posts from people you follow (newest first). Search people, open profiles and their posts, follow. No suggested posts, Explore grid or Reels feed. |
| **Messenger** | Facebook Messenger chats (via facebook.com/messages). |
| **Settings** | Turn tabs on/off, notifications, take a timed **feed break** (5–60 min of normal Instagram), check for updates. |

Links to other websites open in your normal browser. Instagram tabs stay in portrait.

## Install (Android 8+)

1. On your phone, open **[Releases](../../releases/latest)** and tap **TheResistance.apk**.
2. Allow your browser to install apps when asked, then tap **Install**.
3. Turn that permission back off afterwards: *Settings → Apps → Special app access → Install unknown apps → your browser → Off*.
4. Open Resistance and log in to Instagram and Facebook as normal.

## Updates

The app checks for a new version about twice a day (or tap **Settings → Check for updates**).
When there is one, tap **Download**, open the file, and install. Updates install over the old version and you stay logged in.
Every version and what changed is listed on the [Releases](../../releases) page.

## Privacy & security

- Your logins go straight to Instagram/Facebook over HTTPS. There is no other server, analytics or tracking.
- The app never reads what you type or your messages. Its only page script hides buttons and tells the app which page you're on.
- Logins are stored in the app's private storage (no backups), and the APK is a signed, non-debuggable release build.
- The signing key is kept in encrypted GitHub secrets, not in this repository.

## Notifications

Websites can't receive notifications, so keep the official Instagram and Messenger apps installed with notifications
on (you can remove them from your home screen). Then turn on **Settings → Notifications** in The Resistance:
their notifications are re-shown by The Resistance (sender, message, picture, and the Reply/Like buttons, which also
work on a smartwatch), the originals are removed, and tapping one opens The Resistance instead of the official app.

It needs Android's *notification access*. It only reads Instagram and Messenger notifications and ignores everything
else. If Android calls it a "restricted setting": *Settings → Apps → The Resistance → ⋮ → Allow restricted settings*.

## Building it yourself

Pushing to `main` builds and publishes a release with GitHub Actions. To build in your own copy, add two repository
secrets: `DMONLY_KEYSTORE_B64` (a PKCS12 keystore with key alias `dmonly`, base64-encoded) and
`DMONLY_KEYSTORE_PASSWORD`. Without them the workflow skips the build.

The blocking rules live in `evaluate()` and `injectHelpers()` in `MainActivity.java`.
Instagram and Facebook change their sites often, so things may need adjusting.
