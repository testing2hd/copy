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
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs yt-dlp with the user's settings, keeps it updated, saves results to the gallery. */
final class Downloader {
    private Downloader() { }

    interface Progress { void update(int percent, String line); }

    static final class Result { Uri uri; int count; String name; }

    static final long DAY = 24L * 3600 * 1000;
    private static final String PREFS = "engine";
    private static final Set<String> TEMP = new HashSet<>(Arrays.asList("part", "ytdl", "temp"));
    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList("m4a", "mp3", "opus", "aac", "flac", "wav", "ogg", "m4b"));
    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList("mp4", "mkv", "webm", "mov", "avi", "flv", "3gp", "ts"));
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

    // ---------- history ----------

    static void addHistory(Context c, String url, String title, String status) {
        try {
            JSONArray old = history(c);
            JSONArray nw = new JSONArray();
            JSONObject e = new JSONObject().put("url", url).put("title", title).put("status", status)
                    .put("time", System.currentTimeMillis());
            nw.put(e);
            for (int i = 0; i < old.length() && i < 29; i++) nw.put(old.get(i));
            c.getSharedPreferences(PREFS, 0).edit().putString("history", nw.toString()).apply();
        } catch (Exception ignored) { }
    }

    static JSONArray history(Context c) {
        try { return new JSONArray(c.getSharedPreferences(PREFS, 0).getString("history", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    // ---------- format list ----------

    /** Lists the formats a link offers, so the user can pick one. */
    static JSONObject info(Context c, String url) throws Exception {
        ensureInit(c);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--no-playlist");
        network(c, r, Config.dl(c));
        VideoInfo vi = YoutubeDL.getInstance().getInfo(r);
        JSONArray arr = new JSONArray();
        if (vi.getFormats() != null) {
            for (VideoFormat f : vi.getFormats()) {
                String ext = f.getExt() == null ? "" : f.getExt();
                if (ext.equals("mhtml") || f.getFormatId() == null) continue;
                String v = f.getVcodec() == null ? "none" : f.getVcodec();
                String a = f.getAcodec() == null ? "none" : f.getAcodec();
                long size = f.getFileSize() > 0 ? f.getFileSize() : f.getFileSizeApproximate();
                arr.put(new JSONObject().put("id", f.getFormatId()).put("ext", ext).put("height", f.getHeight())
                        .put("fps", f.getFps()).put("v", v).put("a", a)
                        .put("note", f.getFormatNote() == null ? "" : f.getFormatNote()).put("size", size));
            }
        }
        return new JSONObject().put("title", vi.getTitle() == null ? url : vi.getTitle()).put("formats", arr);
    }

    // ---------- download ----------

    /** Downloads, retrying once after an engine update if it fails. fmt is an optional yt-dlp format selector. */
    static Result download(Context c, String url, String fmt, Progress p) throws Exception {
        cancelled = false;
        ensureInit(c);
        updateIfStale(c);
        File dir = new File(c.getExternalFilesDir(null), "tmp/" + System.currentTimeMillis());
        dir.mkdirs();
        try {
            try {
                run(c, url, fmt, dir, p);
            } catch (Exception first) {
                if (cancelled) throw first;
                p.update(-1, "Download failed, updating engine and retrying...");
                updateEngine(c);
                run(c, url, fmt, dir, p);
            }
            return save(c, dir);
        } finally {
            currentId = null;
            deleteAll(dir);
        }
    }

    private static String s(JSONObject d, String k, String def) { return d.optString(k, def).trim(); }
    private static boolean b(JSONObject d, String k) { return d.optBoolean(k, false); }
    private static int n(JSONObject d, String k, int def) {
        try { return Integer.parseInt(s(d, k, "" + def)); } catch (Exception e) { return def; }
    }

    private static void network(Context c, YoutubeDLRequest r, JSONObject d) throws Exception {
        if (!s(d, "proxy", "").isEmpty()) r.addOption("--proxy", s(d, "proxy", ""));
        if (!s(d, "userAgent", "").isEmpty()) r.addOption("--user-agent", s(d, "userAgent", ""));
        if (!s(d, "geo", "").isEmpty()) r.addOption("--geo-bypass-country", s(d, "geo", "").toUpperCase(Locale.ROOT));
        if (b(d, "ipv4")) r.addOption("-4");
        if (b(d, "noCert")) r.addOption("--no-check-certificates");
        String cookies = s(d, "cookies", "");
        if (!cookies.isEmpty()) {
            File f = new File(c.getFilesDir(), "cookies.txt");
            try (OutputStream o = new FileOutputStream(f)) { o.write((cookies + "\n").getBytes("UTF-8")); }
            r.addOption("--cookies", f.getAbsolutePath());
        }
    }

    private static void run(Context c, String url, String fmt, File dir, Progress p) throws Exception {
        JSONObject d = Config.dl(c);
        boolean audio = "audio".equals(s(d, "mode", "video"));
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        String tpl = s(d, "template", "");
        if (tpl.isEmpty()) tpl = "%(playlist_index&{} - |)s%(title).100B [%(id)s].%(ext)s";
        r.addOption("-o", dir.getAbsolutePath() + "/" + tpl);
        if (!b(d, "playlist")) r.addOption("--no-playlist");
        else if (!s(d, "playlistItems", "").isEmpty()) r.addOption("--playlist-items", s(d, "playlistItems", ""));
        r.addOption("--no-mtime");
        r.addOption("-N", n(d, "threads", 4));
        r.addOption("--retries", n(d, "retries", 10));
        r.addOption("--fragment-retries", n(d, "retries", 10));
        network(c, r, d);

        if (fmt != null && !fmt.isEmpty()) {
            r.addOption("-f", fmt);
            r.addOption("--merge-output-format", s(d, "container", "mp4"));
        } else if (audio) {
            r.addOption("-f", "ba/b");
            r.addOption("-x");
            if (!"best".equals(s(d, "audioFormat", "m4a"))) r.addOption("--audio-format", s(d, "audioFormat", "m4a"));
            r.addOption("--audio-quality", s(d, "audioQuality", "0"));
        } else {
            String q = s(d, "quality", "best");
            String vc = s(d, "vcodec", "h264");
            String sort = "best".equals(q) ? "res" : "res:" + q;
            if (!"any".equals(vc)) sort += ",vcodec:" + ("av1".equals(vc) ? "av01" : vc);
            if ("h264".equals(vc)) sort += ",acodec:m4a";
            r.addOption("-f", "bv*+ba/b");
            r.addOption("-S", sort);
            r.addOption("--merge-output-format", s(d, "container", "mp4"));
        }
        if (!s(d, "formatOverride", "").isEmpty()) r.addOption("-f", s(d, "formatOverride", ""));
        if (!s(d, "sortOverride", "").isEmpty()) r.addOption("-S", s(d, "sortOverride", ""));

        String subs = s(d, "subs", "none");
        if (!"none".equals(subs) && !audio) {
            r.addOption("--write-subs");
            if (b(d, "autoSubs")) r.addOption("--write-auto-subs");
            r.addOption("--sub-langs", s(d, "subLangs", "en.*"));
            if ("embed".equals(subs)) r.addOption("--embed-subs");
        }
        if (b(d, "embedThumb")) { r.addOption("--embed-thumbnail"); r.addOption("--convert-thumbnails", "jpg"); }
        if (b(d, "embedMeta")) r.addOption("--embed-metadata");
        if (b(d, "embedChapters")) r.addOption("--embed-chapters");
        String sb = s(d, "sponsorblock", "off");
        if ("remove".equals(sb)) r.addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction");
        else if ("mark".equals(sb)) r.addOption("--sponsorblock-mark", "all");
        if (!s(d, "sections", "").isEmpty()) r.addOption("--download-sections", s(d, "sections", ""));
        if (!s(d, "rate", "").isEmpty()) r.addOption("-r", s(d, "rate", ""));
        if (b(d, "restrict")) r.addOption("--restrict-filenames");
        if (b(d, "archive")) r.addOption("--download-archive", new File(c.getFilesDir(), "archive.txt").getAbsolutePath());
        if (!s(d, "extra", "").isEmpty()) r.addCommands(split(s(d, "extra", ""))); // anything else yt-dlp supports

        currentId = "lr-" + System.currentTimeMillis();
        YoutubeDL.getInstance().execute(r, currentId, (Float pr, Long eta, String line) -> {
            p.update(pr == null ? -1 : Math.round(pr), line);
            return kotlin.Unit.INSTANCE;
        });
    }

    /** Splits a command line into arguments, honouring single and double quotes. */
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char q = 0;
        boolean has = false;
        for (char ch : s.toCharArray()) {
            if (q != 0) { if (ch == q) q = 0; else cur.append(ch); }
            else if (ch == '"' || ch == '\'') { q = ch; has = true; }
            else if (Character.isWhitespace(ch)) { if (has || cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); has = false; } }
            else cur.append(ch);
        }
        if (has || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static void collect(File f, List<File> out) {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) { if (k.isDirectory()) collect(k, out); else out.add(k); }
    }

    private static Result save(Context c, File dir) throws Exception {
        List<File> files = new ArrayList<>();
        collect(dir, files);
        String folder = s(Config.dl(c), "folder", "LinkRouter").replaceAll("[^A-Za-z0-9 _.-]", "");
        if (folder.isEmpty()) folder = "LinkRouter";
        ContentResolver cr = c.getContentResolver();
        Result res = new Result();
        Uri fallback = null;
        String fallbackName = null;
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (TEMP.contains(ext)) continue;
            boolean audio = AUDIO.contains(ext), video = VIDEO.contains(ext);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) mime = audio ? "audio/mp4" : video ? "video/" + ext : "application/octet-stream";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (audio ? "Music/" : video ? "Movies/" : "Download/") + folder);
            Uri base = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    : video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri out = cr.insert(base, v);
            if (out == null) continue;
            try (InputStream in = new FileInputStream(f); OutputStream os = cr.openOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int len;
                while ((len = in.read(buf)) > 0) os.write(buf, 0, len);
            }
            if (audio || video) { res.uri = out; res.name = name; res.count++; }
            else if (fallback == null) { fallback = out; fallbackName = name; }
        }
        if (res.uri == null && fallback != null) { res.uri = fallback; res.name = fallbackName; res.count = 1; }
        if (res.uri == null) throw new Exception("No file was produced");
        return res;
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
