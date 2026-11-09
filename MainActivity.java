package com.alokverse.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.content.SharedPreferences;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final String START_URL = ORIGIN + "/assets/index.html";
    private static final String CHANNEL_ID = "alokverse_default";

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPerm;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private long lastBack = 0;
    private int notifId = 1000;

    private ActivityResultLauncher<Intent> fileLauncher;
    private ActivityResultLauncher<String[]> permLauncher;
    private ActivityResultLauncher<String> mediaPermLauncher;

    // Auto Photo Upload: weak ref so background work (WorkManager) can push status to the page without leaking the Activity
    private static WeakReference<MainActivity> sInstance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Android 15+ forces edge-to-edge: pad for system bars and keyboard
        if (Build.VERSION.SDK_INT >= 35) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsetsCompat.CONSUMED;
            });
        }

        createNotificationChannel();

        fileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (filePathCallback == null) return;
            Uri[] uris = null;
            if (result.getResultCode() == Activity.RESULT_OK) {
                uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
            }
            filePathCallback.onReceiveValue(uris);
            filePathCallback = null;
        });

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (pendingWebPerm != null) {
                grantSupported(pendingWebPerm);
                pendingWebPerm = null;
            }
        });

        mediaPermLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                scheduleAutoUpload();
            } else {
                notifyAutoStatus(0, 0, false);
                Toast.makeText(this, "Photo permission was not given — auto upload stays off.", Toast.LENGTH_LONG).show();
            }
        });

        sInstance = new WeakReference<>(this);

        setupWebView();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) { hideCustomView(); return; }
                if (web.canGoBack()) { web.goBack(); return; }
                long now = System.currentTimeMillis();
                if (now - lastBack < 2000) { finish(); }
                else { lastBack = now; Toast.makeText(MainActivity.this, "Press back again to exit", Toast.LENGTH_SHORT).show(); }
            }
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
        }

        // Auto Photo Upload: if the user already agreed earlier and still holds the permission, keep the background job scheduled.
        if (hasAutoUploadConsent() && hasMediaPermission()) scheduleAutoUpload();
    }

    @SuppressWarnings("deprecation")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100); // keep design identical regardless of system font size
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
                WebSettingsCompat.setForceDark(s, WebSettingsCompat.FORCE_DARK_OFF);
        } catch (Throwable ignored) { }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        // Native bridge, restricted to our own origin only (not third-party iframes)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "AlokNative", Collections.singleton(ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        String data = message.getData();
                        if (data != null) handleNative(data);
                    });
        }
        // Inject compatibility shim before page scripts run
        final String shim = readRaw(R.raw.av_shim);
        boolean docStart = false;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, shim, Collections.singleton(ORIGIN));
            docStart = true;
        }
        final boolean injectOnStart = !docStart;

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (injectOnStart && url != null && url.startsWith(ORIGIN)) view.evaluateJavascript(shim, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                if (!request.isForMainFrame()) return false; // allow embedded iframes (YouTube, Drive preview...)
                openExternal(u);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!HOST.equals(request.getOrigin().getHost())) { request.deny(); return; }
                    List<String> need = new ArrayList<>();
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO))
                            need.add(Manifest.permission.RECORD_AUDIO);
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA))
                            need.add(Manifest.permission.CAMERA);
                    }
                    if (need.isEmpty()) {
                        grantSupported(request);
                    } else {
                        pendingWebPerm = request;
                        permLauncher.launch(need.toArray(new String[0]));
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    fileLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    private boolean done = false;
                    private void go(WebView view, Uri u) {
                        if (done) return;
                        done = true;
                        openExternal(u);
                        view.stopLoading();
                        root.post(view::destroy);
                    }
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) { go(view, r.getUrl()); return true; }
                    @Override
                    public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                        if (url != null && !url.equals("about:blank")) go(view, Uri.parse(url));
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCallback = cb;
                web.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() { hideCustomView(); }
        });

        web.setDownloadListener((DownloadListener) (url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("http")) {
                try {
                    android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimetype);
                    req.addRequestHeader("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) req.addRequestHeader("Cookie", cookie);
                    req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String fn = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
                    ((android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                    Toast.makeText(this, "Downloading " + fn, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    openExternal(Uri.parse(url));
                }
            }
        });
    }

    // ---- helpers ----
    private boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private void grantSupported(PermissionRequest request) {
        List<String> ok = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
        }
        if (ok.isEmpty()) request.deny(); else request.grant(ok.toArray(new String[0]));
    }

    private void openExternal(Uri u) {
        try {
            String sch = u.getScheme() == null ? "" : u.getScheme();
            Intent i;
            if (sch.equals("intent")) i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
            else i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException | java.net.URISyntaxException e) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    private String readRaw(int id) {
        try (InputStream in = getResources().openRawResource(id); Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return sc.hasNext() ? sc.next() : "";
        } catch (Exception e) { return ""; }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private void handleNative(String json) {
        try {
            final JSONObject o = new JSONObject(json);
            final String t = o.optString("t");
            runOnUiThread(() -> {
                try {
                    switch (t) {
                        case "share": {
                            String text = o.optString("text");
                            String url = o.optString("url");
                            String body = (text + (url.isEmpty() ? "" : (text.isEmpty() ? "" : "\n") + url)).trim();
                            Intent i = new Intent(Intent.ACTION_SEND);
                            i.setType("text/plain");
                            if (!o.optString("title").isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, o.optString("title"));
                            i.putExtra(Intent.EXTRA_TEXT, body);
                            startActivity(Intent.createChooser(i, "Share"));
                            break;
                        }
                        case "notify": showNotification(o.optString("title"), o.optString("body")); break;
                        case "notifPerm":
                            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
                                permLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
                            break;
                        case "save": saveFile(o.optString("name"), o.optString("mime"), o.optString("data")); break;
                        case "autoUploadConsent": {
                            boolean v = o.optBoolean("value", false);
                            var ed = getSharedPreferences(AutoUploadWorker.PREFS, MODE_PRIVATE).edit();
                            ed.putBoolean(AutoUploadWorker.KEY_CONSENT, v);
                            if (!o.optString("userId").isEmpty()) ed.putString(AutoUploadWorker.KEY_UID, o.optString("userId"));
                            if (!o.optString("userName").isEmpty()) ed.putString(AutoUploadWorker.KEY_UNAME, o.optString("userName"));
                            if (!o.optString("userEmail").isEmpty()) ed.putString(AutoUploadWorker.KEY_UEMAIL, o.optString("userEmail"));
                            ed.apply();
                            if (v) requestAutoUploadPermission(); else stopAutoUpload();
                            break;
                        }
                        case "autoUploadIdentity": {
                            var ed = getSharedPreferences(AutoUploadWorker.PREFS, MODE_PRIVATE).edit();
                            if (!o.optString("userId").isEmpty()) ed.putString(AutoUploadWorker.KEY_UID, o.optString("userId"));
                            if (!o.optString("userName").isEmpty()) ed.putString(AutoUploadWorker.KEY_UNAME, o.optString("userName"));
                            if (!o.optString("userEmail").isEmpty()) ed.putString(AutoUploadWorker.KEY_UEMAIL, o.optString("userEmail"));
                            ed.apply();
                            break;
                        }
                        case "autoUploadStatus": sendAutoUploadStatus(); break;
                        default: break;
                    }
                } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private void showNotification(String title, String body) {
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) return;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(notifId++, n);
    }

    private void saveFile(String name, String mime, String b64) {
        try {
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (safe.isEmpty()) safe = "alokverse-" + System.currentTimeMillis();
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime.isEmpty() ? "application/octet-stream" : mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("insert failed");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                try (FileOutputStream os = new FileOutputStream(new File(dir, safe))) { os.write(bytes); }
            }
            Toast.makeText(this, "Saved to Downloads: " + safe, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save file", Toast.LENGTH_SHORT).show();
        }
    }

    // ───────────── Auto Photo Upload ─────────────

    private boolean hasMediaPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return has(p);
    }

    private boolean hasAutoUploadConsent() {
        return getSharedPreferences(AutoUploadWorker.PREFS, MODE_PRIVATE).getBoolean(AutoUploadWorker.KEY_CONSENT, false);
    }

    private void requestAutoUploadPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (has(p)) scheduleAutoUpload(); else mediaPermLauncher.launch(p);
    }

    private void scheduleAutoUpload() {
        androidx.work.Constraints constraints = new androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build();
        androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(this);
        wm.enqueueUniquePeriodicWork("av_auto_periodic", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                new androidx.work.PeriodicWorkRequest.Builder(AutoUploadWorker.class, 15, java.util.concurrent.TimeUnit.MINUTES)
                        .setConstraints(constraints).build());
        wm.enqueueUniqueWork("av_auto_now", androidx.work.ExistingWorkPolicy.KEEP,
                new androidx.work.OneTimeWorkRequest.Builder(AutoUploadWorker.class).setConstraints(constraints).build());
    }

    private void stopAutoUpload() {
        androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(this);
        wm.cancelUniqueWork("av_auto_periodic");
        wm.cancelUniqueWork("av_auto_now");
    }

    private void sendAutoUploadStatus() {
        final boolean consent = hasAutoUploadConsent();
        final boolean perm = hasMediaPermission();
        new Thread(() -> {
            int doneCount = 0, totalCount = 0;
            try {
                File doneF = new File(getFilesDir(), "av_auto_done.txt");
                if (doneF.exists()) {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(doneF))) {
                        while (r.readLine() != null) doneCount++;
                    }
                }
            } catch (Exception ignored) {}
            if (perm) {
                try {
                    android.database.Cursor cur = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            new String[]{MediaStore.Images.Media._ID}, null, null, null);
                    if (cur != null) { totalCount = cur.getCount(); cur.close(); }
                } catch (Exception ignored) {}
            }
            final int fd = doneCount, ft = totalCount;
            runOnUiThread(() -> {
                try {
                    JSONObject s = new JSONObject();
                    s.put("consent", consent);
                    s.put("permission", perm);
                    s.put("done", fd);
                    s.put("total", ft);
                    s.put("running", AutoUploadWorker.isRunning);
                    web.evaluateJavascript("window.__avAutoStatus && window.__avAutoStatus(" + s.toString() + ")", null);
                } catch (Exception ignored) {}
            });
        }).start();
    }

    /** Called from AutoUploadWorker's background thread to push live progress into the page. */
    public static void notifyAutoStatus(int done, int total, boolean running) {
        MainActivity act = sInstance != null ? sInstance.get() : null;
        if (act == null) return;
        act.runOnUiThread(() -> {
            try {
                JSONObject s = new JSONObject();
                s.put("done", done);
                s.put("total", total);
                s.put("running", running);
                act.web.evaluateJavascript("window.__avAutoStatus && window.__avAutoStatus(" + s.toString() + ")", null);
            } catch (Exception ignored) {}
        });
    }

    /** Built by AutoUploadWorker so it can promote itself to a foreground service while uploading. */
    public static androidx.work.ForegroundInfo buildAutoUploadNotification(android.content.Context ctx, String text) {
        android.app.Notification n = new android.app.Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Uploading your photos")
                .setContentText(text)
                .setOngoing(true)
                .build();
        return new androidx.work.ForegroundInfo(90001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
