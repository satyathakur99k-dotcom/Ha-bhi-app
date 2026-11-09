# AlokVerse – Android APK project

Two apps are built from the same project (your two HTML files are bundled **unchanged** inside the APK):

| Flavor | App name        | Package             | Source file      |
|--------|-----------------|---------------------|------------------|
| user   | AlokVerse       | com.alokverse.app   | index__1_.html   |
| admin  | AlokVerse Admin | com.alokverse.admin | index__3_.html   |

Logo = the A + graduation-cap logo found inside your admin file (192px). For a sharper icon replace
`app/src/main/res/drawable-nodpi/ic_logo.png` (512x512) and the `mipmap-*/ic_launcher*.png` files.

## Option A – Build online (no installation) with GitHub  ✅ easiest
1. Create a free account on github.com → New repository (private is fine).
2. Upload ALL files of this folder (drag & drop the unzipped contents; keep the `.github` folder).
3. Open the **Actions** tab → "Build AlokVerse APK" → **Run workflow**. Wait ~5 minutes.
4. Open the finished run → **Artifacts → AlokVerse-APKs** → download. Inside:
   `app-user-release.apk` (AlokVerse) and `app-admin-release.apk` (Admin).

## Option B – Build on your PC with Android Studio
1. Install Android Studio (developer.android.com/studio).
2. File → Open → choose this folder → wait for Gradle sync.
3. Menu **Build → Select Build Variant** → `userRelease` (or `adminRelease`).
4. **Build → Build APK(s)**. APK appears in `app/build/outputs/apk/user/release/`.
   (Or terminal: `gradle assembleUserRelease assembleAdminRelease`.)

## Signing with YOUR OWN key (important before publishing)
Without a key the APK is signed with a temporary debug key – it installs, but you can never update it
for users later with the same key. Create your key ONCE and keep it safe (never lose it):

    keytool -genkeypair -v -keystore alokverse.jks -alias alokverse -keyalg RSA -keysize 2048 -validity 10000

* Android Studio/PC: put `alokverse.jks` in `app/` and create `keystore.properties` in the project root:

      storeFile=app/alokverse.jks
      storePassword=YOUR_PASSWORD
      keyAlias=alokverse
      keyPassword=YOUR_PASSWORD

* GitHub: Settings → Secrets → Actions → add `KEYSTORE_BASE64` (output of `base64 -w0 alokverse.jks`),
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Updating the app later
Replace `app/src/user/assets/index.html` (or `admin`) with the new HTML, increase `versionCode` and
`versionName` in `app/build.gradle`, build again with the SAME key.

## What the app does natively
* Loads the HTML locally (https://appassets.androidplatform.net) – works like a real origin, localStorage works.
* Microphone & camera permissions (calls), file/image upload picker, back button, full-screen video.
* Downloads (normal + blob/data files) go to the Downloads folder; share button opens Android share sheet.
* Notifications (Pomodoro / chat replies) shown as Android notifications while the app is running.
* External links (YouTube, Drive, WhatsApp, etc.) open in the browser/apps; embedded iframes stay inside.
* Internet is required for Firebase, fonts, Tailwind and Three.js (loaded from CDN exactly like the website).

## Test checklist before publishing
Login/sign-up, chat send/receive, image upload, voice/video call, PDF viewer, downloads, share, back button, rotation.
