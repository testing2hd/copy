# Link Router

Android-only Capacitor app. Copy a YouTube / Facebook / TikTok / Instagram link and a popup offers **Download** (built-in yt-dlp + ffmpeg, runs in the background) or opening it in another downloader app (Vidmate, Seal Plus by default; editable in the app).

The yt-dlp engine updates itself every 12 hours (nightly channel by default), and again automatically after any failed download. Settings: quality, video format, update channel, cookies. Files go to Movies/LinkRouter (audio to Music/LinkRouter). Needs Android 10+.

## Build
Push to GitHub (`main`). Actions -> "Build APK" -> download `link-router-apk` -> install `app-debug.apk`.
Optional: add repo secret `DEBUG_KEYSTORE_BASE64` (base64 of a debug.keystore) so every build has the same signature and updates install over the old one.

## Use
1. Start Shizuku. Open Link Router and finish the short setup list (it only shows what is missing).
2. Turn on "Detect copied links". The line under it shows what the app is doing.
3. Copy a video link. The popup appears. If not, read the status line.

You can also use Share -> Link Router from any app, or "Show test popup" to check the popup alone.
After a reboot, start Shizuku again, then the watcher reconnects by itself.

## Settings
Quality, codec, audio format, playlists, clips, subtitles, thumbnail/metadata, SponsorBlock, network, file names and a free-text box for any other yt-dlp argument. "Choose format" lists every format of a link. The notification has Mode / Quality / Stop buttons.
