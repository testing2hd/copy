const $ = (s) => document.querySelector(s);
window.addEventListener('error', (e) => { const m = $('#msg'); if (m) m.textContent = 'Script error: ' + e.message; });
window.addEventListener('unhandledrejection', (e) => { const m = $('#msg'); if (m) m.textContent = 'Error: ' + ((e.reason && e.reason.message) || e.reason); });
// No bundler here, so call the native bridge directly instead of Capacitor.registerPlugin.
const LR = new Proxy({}, {
  get: (_, method) => (opts) => {
    const C = window.Capacitor;
    if (!C) return Promise.reject(new Error('Capacitor bridge not found'));
    if (typeof C.nativePromise === 'function') return C.nativePromise('LinkRouter', method, opts || {});
    if (typeof C.registerPlugin === 'function') return C.registerPlugin('LinkRouter')[method](opts || {});
    return Promise.reject(new Error('Capacitor bridge has no nativePromise'));
  },
});
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let cfg = { apps: [] }, installedApps = [], status = null, saveTimer;

function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.hidden = false;
  clearTimeout(toast.h); toast.h = setTimeout(() => (t.hidden = true), 2800);
}
const run = async (fn) => { try { await fn(); } catch (e) { toast(e.message || String(e)); } setTimeout(refresh, 500); };

function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    cfg.apps.forEach((a) => (a.pkg = (a.pkg || '').trim()));
    LR.saveConfig({ config: cfg }).catch((e) => toast(e.message));
  }, 300);
}
const saveNow = async () => { clearTimeout(saveTimer); cfg.apps.forEach((a) => (a.pkg = (a.pkg || '').trim())); await LR.saveConfig({ config: cfg }); };

function renderApps() {
  const box = $('#apps'); box.innerHTML = '';
  cfg.apps.forEach((a, i) => {
    const d = document.createElement('div'); d.className = 'app';
    d.innerHTML = `
      <select aria-label="Choose an installed app">
        <option value="">Choose an installed app...</option>
        ${installedApps.map((x) => `<option value="${esc(x.pkg)}">${esc(x.label)}</option>`).join('')}
      </select>
      <input type="text" data-k="label" value="${esc(a.label)}" placeholder="Name shown in popup" aria-label="Name">
      <input type="text" data-k="pkg" value="${esc(a.pkg)}" placeholder="Package name, e.g. com.example.app" spellcheck="false" autocapitalize="off" aria-label="Package name">
      <div class="foot"><b class="inst" data-i="${i}"></b><button class="small ghost" data-del>Remove</button></div>`;
    d.querySelectorAll('input[data-k]').forEach((el) => el.addEventListener('input', () => { a[el.dataset.k] = el.value; save(); updateInstalled(); }));
    d.querySelector('select').onchange = (e) => {
      const x = installedApps.find((z) => z.pkg === e.target.value);
      if (!x) return;
      a.pkg = x.pkg; a.label = x.label; save(); renderApps(); updateInstalled();
    };
    d.querySelector('[data-del]').onclick = () => { cfg.apps.splice(i, 1); save(); renderApps(); };
    box.appendChild(d);
  });
  updateInstalled();
}

function updateInstalled() {
  document.querySelectorAll('.inst').forEach((el) => {
    const a = cfg.apps[+el.dataset.i];
    const inst = status && status.installed && a ? status.installed[(a.pkg || '').trim()] : undefined;
    el.textContent = !a || !a.pkg ? '' : inst === undefined ? 'Save to check' : inst ? 'Installed' : 'Not installed';
    el.className = 'inst ' + (inst ? 'ok' : '');
  });
}

// Every yt-dlp option the app exposes: [key, label, type, options]. "extra" accepts any other yt-dlp argument.
const Q = [['best', 'Best available'], ['2160', '4K (2160p)'], ['1440', '1440p'], ['1080', '1080p'], ['720', '720p'], ['480', '480p'], ['360', '360p']];
const SCHEMA = [
  ['Video & audio', [
    ['mode', 'Download as', 'select', [['video', 'Video'], ['audio', 'Audio only']]],
    ['quality', 'Max video quality', 'select', Q],
    ['container', 'Video format', 'select', [['mp4', 'MP4'], ['mkv', 'MKV'], ['webm', 'WebM']]],
    ['vcodec', 'Preferred video codec', 'select', [['h264', 'H.264 (plays everywhere)'], ['vp9', 'VP9'], ['av1', 'AV1'], ['any', 'Any (highest quality)']]],
    ['audioFormat', 'Audio format', 'select', [['m4a', 'M4A'], ['mp3', 'MP3'], ['opus', 'Opus'], ['flac', 'FLAC'], ['wav', 'WAV'], ['best', 'Original, no conversion']]],
    ['audioQuality', 'Audio quality', 'select', [['0', 'Best'], ['5', 'Medium'], ['9', 'Small']]],
  ]],
  ['Playlists & clips', [
    ['playlist', 'Download whole playlist', 'check'],
    ['playlistItems', 'Playlist items (e.g. 1-5,8)', 'text'],
    ['sections', 'Only this part (e.g. *1:30-2:45)', 'text'],
    ['archive', 'Skip videos already downloaded', 'check'],
  ]],
  ['Subtitles, thumbnail & metadata', [
    ['subs', 'Subtitles', 'select', [['none', 'None'], ['embed', 'Embed in video'], ['file', 'Separate file']]],
    ['subLangs', 'Subtitle languages (e.g. en.*,ne,all)', 'text'],
    ['autoSubs', 'Include auto-generated subtitles', 'check'],
    ['embedThumb', 'Embed thumbnail', 'check'],
    ['embedMeta', 'Embed metadata', 'check'],
    ['embedChapters', 'Embed chapters', 'check'],
    ['sponsorblock', 'SponsorBlock', 'select', [['off', 'Off'], ['remove', 'Cut sponsors, self-promo, reminders'], ['mark', 'Mark as chapters']]],
  ]],
  ['Network', [
    ['rate', 'Speed limit (e.g. 2M)', 'text'],
    ['threads', 'Parallel fragments', 'text'],
    ['retries', 'Retries', 'text'],
    ['proxy', 'Proxy (e.g. socks5://host:port)', 'text'],
    ['geo', 'Pretend to be in country (e.g. US)', 'text'],
    ['userAgent', 'User agent', 'text'],
    ['ipv4', 'Force IPv4', 'check'],
    ['noCert', 'Ignore certificate errors', 'check'],
  ]],
  ['Files', [
    ['folder', 'Folder name (inside Movies / Music)', 'text'],
    ['template', 'File name template (yt-dlp -o syntax)', 'text'],
    ['restrict', 'Safe file names (ASCII only)', 'check'],
  ]],
  ['Advanced', [
    ['formatOverride', 'Format selector (-f), overrides quality', 'text'],
    ['sortOverride', 'Format sorting (-S)', 'text'],
    ['extra', 'Extra yt-dlp arguments (any option, e.g. --write-description --no-overwrites)', 'area'],
    ['cookies', 'Cookies (Netscape cookies.txt) for private or age-limited videos', 'area'],
    ['channel', 'Engine update channel', 'select', [['nightly', 'Nightly (fixes arrive first)'], ['stable', 'Stable']]],
  ]],
];

function renderSettings() {
  const d = cfg.dl || (cfg.dl = {});
  const box = $('#settings'); box.innerHTML = '';
  SCHEMA.forEach(([title, fields], i) => {
    const det = document.createElement('details'); if (i === 0) det.open = true;
    det.innerHTML = `<summary>${esc(title)}</summary>`;
    fields.forEach(([key, label, type, opts]) => {
      const w = document.createElement('label');
      if (type === 'check') {
        w.className = 'chk'; w.innerHTML = `<input type="checkbox"><span>${esc(label)}</span>`;
        const el = w.querySelector('input'); el.checked = !!d[key];
        el.onchange = () => { d[key] = el.checked; save(); };
      } else {
        w.className = 'fld';
        const inner = type === 'select' ? `<select>${opts.map((o) => `<option value="${esc(o[0])}">${esc(o[1])}</option>`).join('')}</select>`
          : type === 'area' ? '<textarea rows="3" spellcheck="false"></textarea>' : '<input type="text" spellcheck="false" autocapitalize="off">';
        w.innerHTML = `${esc(label)}${inner}`;
        const el = w.querySelector('select,textarea,input'); el.value = d[key] ?? '';
        el.onchange = el.oninput = () => { d[key] = el.value; save(); };
      }
      det.appendChild(w);
    });
    box.appendChild(det);
  });
}

function fmtSize(n) { return n ? (n > 1e9 ? (n / 1e9).toFixed(1) + ' GB' : Math.max(1, Math.round(n / 1e6)) + ' MB') : ''; }

async function chooseFormat() {
  const url = $('#dlUrl').value.trim(); const box = $('#fmts');
  if (!url) return toast('Paste a link first');
  box.hidden = false; box.innerHTML = '<p class="msg">Reading formats...</p>';
  try {
    await saveNow();
    const info = await LR.listFormats({ url });
    const fs = (info.formats || []).slice().sort((a, b) => (b.height - a.height) || (b.size - a.size));
    const vid = fs.filter((f) => f.v !== 'none'), aud = fs.filter((f) => f.v === 'none' && f.a !== 'none');
    box.innerHTML = `<p class="hint">${esc(info.title)}</p>`;
    const add = (title, list, audioOnly) => {
      if (!list.length) return;
      box.insertAdjacentHTML('beforeend', `<h3>${title}</h3>`);
      list.forEach((f) => {
        const b = document.createElement('button'); b.className = 'ghost';
        const kind = f.v !== 'none' && f.a === 'none' ? ' (video only, audio added)' : '';
        b.textContent = (audioOnly ? 'Audio' : (f.height ? f.height + 'p' : f.note || 'video') + (f.fps > 30 ? f.fps : '')) + ' · ' + f.ext + ' · ' + (audioOnly ? f.a : f.v.split('.')[0]) + (fmtSize(f.size) ? ' · ' + fmtSize(f.size) : '') + kind;
        b.onclick = () => run(async () => { await LR.download({ url, format: f.v !== 'none' && f.a === 'none' ? `${f.id}+ba/${f.id}` : f.id }); box.hidden = true; toast('Download started'); });
        box.appendChild(b);
      });
    };
    add('Video', vid, false); add('Audio only', aud, true);
    if (!vid.length && !aud.length) box.insertAdjacentHTML('beforeend', '<p class="msg">No formats listed for this link.</p>');
  } catch (e) { box.innerHTML = `<p class="msg">${esc(e.message || e)}</p>`; }
}

function renderHistory() {
  const h = (status && status.history) || [];
  $('#histEmpty').hidden = h.length > 0;
  $('#hist').innerHTML = h.map((e) => `<li><span class="st ${esc(e.status)}">${esc(e.status === 'done' ? 'Saved' : e.status === 'failed' ? 'Failed' : 'Cancelled')}</span> ${esc(e.title || e.url)}<div class="hint">${esc(e.url)}</div></li>`).join('');
}

function renderStatus() {
  if (!status) return;
  const up = status.engineUpdated ? new Date(status.engineUpdated).toLocaleString() : 'never';
  renderHistory();
  $('#eng').textContent = 'Engine: ' + (status.engine || 'not ready yet') + ' (updated ' + up + ')';
  $('#svc').checked = status.serviceRunning;
  $('#msg').textContent = status.message || '';
  const todo = [];
  if (!status.overlay) todo.push(['Allow "Display over other apps"', 'Allow', () => LR.openOverlaySettings()]);
  if (!status.notifications) todo.push(['Allow notifications', 'Allow', () => LR.requestNotifications()]);
  if (!status.shizukuRunning) todo.push(['Start Shizuku', 'Open Shizuku', () => LR.openShizuku()]);
  else if (!status.shizukuGranted) todo.push(['Let Link Router use Shizuku', 'Authorize', () => LR.requestShizuku()]);
  const ul = $('#todo'); ul.innerHTML = '';
  todo.forEach(([t, b, fn]) => {
    const li = document.createElement('li');
    li.innerHTML = `<span>${esc(t)}</span>`;
    const btn = document.createElement('button'); btn.className = 'small'; btn.textContent = b;
    btn.onclick = () => run(fn); li.appendChild(btn); ul.appendChild(li);
  });
  $('#setup').hidden = todo.length === 0;
  updateInstalled();
}

async function refresh() {
  try { status = await LR.getStatus(); renderStatus(); }
  catch (e) { $('#msg').textContent = 'Error: ' + (e.message || e); }
}

$('#dlGo').onclick = () => run(async () => { await LR.download({ url: $('#dlUrl').value }); $('#dlUrl').value = ''; toast('Download started'); });
$('#fmtGo').onclick = chooseFormat;
$('#upd').onclick = () => { toast('Updating engine...'); run(async () => { toast((await LR.updateEngine()).result); }); };
$('#addApp').onclick = () => { cfg.apps.push({ label: '', pkg: '' }); save(); renderApps(); };
$('#test').onclick = () => run(async () => { await saveNow(); await LR.testPopup(); });
$('#svc').onchange = (e) => run(async () => {
  await saveNow();
  if (e.target.checked) await LR.startService(); else await LR.stopService();
});

document.addEventListener('visibilitychange', async () => {
  if (document.hidden) return;
  refresh();
  // The notification buttons change mode/quality natively, so pick those up before the next save.
  try { cfg = await LR.getConfig(); renderSettings(); } catch (e) { /* keep current */ }
});
setInterval(() => { if (!document.hidden) refresh(); }, 2000);

(async () => {
  $('#svc').checked = false;
  try { cfg = await LR.getConfig(); } catch (e) { $('#msg').textContent = 'Error: ' + (e.message || e); }
  renderApps();
  renderSettings();
  refresh();
  try { installedApps = (await LR.listApps()).apps || []; renderApps(); } catch (e) { /* picker is optional */ }
})();
