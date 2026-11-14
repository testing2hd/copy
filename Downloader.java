package com.linkrouter.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import org.json.JSONObject;

/** Runs yt-dlp, keeps it updated, and saves finished files to Movies/LinkRouter (or Music/LinkRouter). */
final class Downloader {
    private Downloader() { }

    interface Progress { void update(int percent, String line); }

    static final long DAY = 24L * 3600 * 1000;
    private static final String PREFS = "engine";
    private static boolean ready;
    static volatile String currentId;
    static volatile boolean cancelled;

    static synchronized void ensureInit(Context c) throws Exception {
        if (ready) return;
        YoutubeDL.getInstance().init(c.getApplicationContext());
        FFmpeg.getInstance().init(c.getApplicationContext());
        ready = true;
    }

    static String version(Context c) {
        try { return YoutubeDL.getInstance().version(c.getApplicationContext()); } catch (Throwable t) { return null; }
    }

    static long lastUpdate(Context c) { return c.getSharedPreferences(PREFS, 0).getLong("last", 0); }

    /** Fetches the newest yt-dlp from GitHub. Returns a short status string. */
    static synchronized String updateEngine(Context c) throws Exception {
        ensureInit(c);
        boolean nightly = !"stable".equals(Config.dl(c).optString("channel", "nightly"));
        YoutubeDL.UpdateChannel ch = nightly ? YoutubeDL.UpdateChannel._NIGHTLY : YoutubeDL.UpdateChannel._STABLE;
        YoutubeDL.UpdateStatus s = YoutubeDL.getInstance().updateYoutubeDL(c.getApplicationContext(), ch);
        c.getSharedPreferences(PREFS, 0).edit().putLong("last", System.currentTimeMillis()).apply();
        return s == YoutubeDL.UpdateStatus.DONE ? "Updated" : "Already up to date";
    }

    static void updateIfStale(Context c) {
        if (System.currentTimeMillis() - lastUpdate(c) < DAY) return;
        try { updateEngine(c); } catch (Throwable ignored) { }
    }

    static void cancel() {
        cancelled = true;
        String id = currentId;
        if (id != null) { try { YoutubeDL.getInstance().destroyProcessById(id); } catch (Throwable ignored) { } }
    }

    /** Downloads, retrying once after an engine update if it fails. Returns the saved file's Uri. */
    static Uri download(Context c, String url, Progress p) throws Exception {
        cancelled = false;
        ensureInit(c);
        updateIfStale(c);
        File dir = new File(c.getExternalFilesDir(null), "tmp/" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            try {
                run(c, url, dir, p);
            } catch (Exception first) {
                if (cancelled) throw first;
                p.update(-1, "Download failed, updating engine and retrying...");
                updateEngine(c);
                run(c, url, dir, p);
            }
            return save(c, dir);
        } finally {
            currentId = null;
            deleteAll(dir);
        }
    }

    private static void run(Context c, String url, File dir, Progress p) throws Exception {
        JSONObject dl = Config.dl(c);
        String quality = dl.optString("quality", "best");
        String container = dl.optString("container", "mp4");
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("-o", dir.getAbsolutePath() + "/%(title).100B [%(id)s].%(ext)s");
        r.addOption("--no-playlist");
        r.addOption("--no-mtime");
        r.addOption("-N", 4);
        if ("audio".equals(quality)) {
            r.addOption("-f", "ba/b");
            r.addOption("-x");
            r.addOption("--audio-format", "m4a");
        } else {
            String res = "best".equals(quality) ? "res" : "res:" + quality;
            r.addOption("-f", "bv*+ba/b");
            r.addOption("-S", res + ",vcodec:h264,acodec:m4a");
            r.addOption("--merge-output-format", container);
        }
        String cookies = dl.optString("cookies", "").trim();
        if (!cookies.isEmpty()) {
            File f = new File(c.getFilesDir(), "cookies.txt");
            try (OutputStream o = new FileOutputStream(f)) { o.write((cookies + "\n").getBytes("UTF-8")); }
            r.addOption("--cookies", f.getAbsolutePath());
        }
        currentId = "lr-" + System.currentTimeMillis();
        YoutubeDL.getInstance().execute(r, currentId, (Float pr, Long eta, String line) -> {
            p.update(pr == null ? -1 : Math.round(pr), line);
            return kotlin.Unit.INSTANCE;
        });
    }

    private static Uri save(Context c, File dir) throws Exception {
        File best = null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".json") || n.endsWith(".txt")) continue;
                if (best == null || f.length() > best.length()) best = f;
            }
        }
        if (best == null) throw new Exception("No file was produced");
        String ext = best.getName().substring(best.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        boolean audio = ext.equals("m4a") || ext.equals("mp3") || ext.equals("opus") || ext.equals("aac");
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = audio ? "audio/mp4" : "video/" + ext;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, best.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, (audio ? "Music/" : "Movies/") + "LinkRouter");
        ContentResolver cr = c.getContentResolver();
        Uri base = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri out = cr.insert(base, v);
        if (out == null) throw new Exception("Could not create file in gallery");
        try (InputStream in = new java.io.FileInputStream(best); OutputStream os = cr.openOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
