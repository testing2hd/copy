package com.linkrouter.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Handles the notification's Mode and Quality/Format buttons. */
public class QuickReceiver extends BroadcastReceiver {
    private static final String[] QUALITY = {"best", "1080", "720", "480"};
    private static final String[] AUDIO = {"m4a", "mp3", "opus"};

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            JSONObject cfg = Config.load(c);
            JSONObject d = cfg.getJSONObject("dl");
            boolean audio = "audio".equals(d.optString("mode", "video"));
            if ("MODE".equals(i.getAction())) {
                d.put("mode", audio ? "video" : "audio");
            } else {
                String key = audio ? "audioFormat" : "quality";
                String[] opts = audio ? AUDIO : QUALITY;
                String cur = d.optString(key, opts[0]);
                int idx = 0;
                for (int k = 0; k < opts.length; k++) if (opts[k].equals(cur)) idx = k;
                d.put(key, opts[(idx + 1) % opts.length]);
            }
            Config.save(c, cfg);
            if (ClipWatchService.running) {
                c.getSystemService(NotificationManager.class).notify(1, ClipWatchService.buildNotification(c, Config.getStatus(c)));
            }
        } catch (Exception ignored) { }
    }
}
