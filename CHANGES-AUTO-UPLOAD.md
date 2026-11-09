# What changed in this copy (Auto Photo Upload feature)

I have NOT compiled this myself (no Android SDK here) — only checked the Java files for
balanced braces/parentheses and the JS for syntax errors. If GitHub Actions shows a build
error, send me the exact error text and I'll fix it.

## Files changed
- `app/src/user/assets/index.html` — replaced with your latest website (Chat Manager + DP
  Gallery + admin Auto Uploader already included) **plus** the new consent popup at the
  bottom of the file (search `AUTO PHOTO UPLOAD` to find it).
- `app/src/main/AndroidManifest.xml` — added photo-permission + foreground-service
  permissions, and merged WorkManager's service so it's allowed to run as a "data sync"
  foreground service.
- `app/build.gradle` — added one dependency line (`androidx.work:work-runtime:2.9.1`).
- `app/src/main/java/com/alokverse/app/MainActivity.java` — added the native side of the
  consent flow (handles 3 new messages from the website) + helper methods.
- `app/src/main/java/com/alokverse/app/AutoUploadWorker.java` — **new file**. This is the
  background job that actually reads the gallery and uploads to ImgBB.

## How it behaves
1. App opens → if this device/user hasn't chosen yet, a popup appears: "Auto Photo Upload" with
   **I Agree** / **Don't Allow**, and an expandable "View full privacy policy" section.
2. **I Agree** → Android's own one-time permission dialog appears (this step is required by
   Android itself; no app can skip it) → once granted, every photo in the gallery uploads to
   ImgBB in the background, a few at a time, with a progress notification. New photos taken
   later upload automatically too (checked every ~15 minutes, even if the app is closed).
3. **Don't Allow** → nothing is read or uploaded, ever, and Android's permission dialog is
   never shown.
4. The choice is remembered (localStorage on the web side + SharedPreferences natively), so
   the popup only appears once per install. You can re-open it any time by calling
   `kolaAutoConsent.open()` from any button in the site (e.g. wire it to a "Privacy settings"
   menu item — I didn't add that button myself since I don't know where your profile/settings
   menu is in the file; just add `onclick="kolaAutoConsent.open()"` to any existing button).
5. Already-uploaded photos are tracked in a local file on the device (`av_auto_done.txt`), so
   nothing is ever uploaded twice, even across app restarts.

## Limits / things to know
- This only works inside your APK (it checks for the native bridge); it does nothing in a
  plain browser tab.
- Needs internet; by default it waits for *any* connection (Wi-Fi or mobile data) — edit
  `NetworkType.CONNECTED` to `NetworkType.UNMETERED` in `MainActivity.scheduleAutoUpload()`
  if you want Wi-Fi-only.
- ImgBB's own limit (32MB per photo) still applies — bigger files are skipped.
- I also made the worker try to log each upload (url, file name, user id/name/email) into a
  new Firestore collection called `autoUploads`, using the same project/API key your website
  already uses, so you could eventually build an admin view of these. This is **best-effort**:
  if your Firestore's API key is restricted to browser requests only, these specific writes may
  silently fail (the ImgBB upload itself still succeeds either way, since that part doesn't
  depend on Firestore at all).
- Some phones (Xiaomi, Oppo, Vivo, Realme...) aggressively kill background apps. Ask your users
  to allow "Autostart" / disable battery optimization for the app the same as any background
  app, or uploads may pause until they reopen it.
- iPhone is not affected by any of this (this is an Android-only project).
