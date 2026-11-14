package tech.kousozo.tahoetv.desktop

import org.json.JSONArray
import org.json.JSONObject

/** HTML/JS page templates rendered by [IptvServer] (`/watch` web player and `/` LAN dashboard). */
internal object ServerPages {

    fun renderWatch(title: String, ids: List<String>, seq: Int, slateJson: String): String =
        WATCH_HTML
            .replace("__TITLE__", IptvServer.esc(title))
            .replace("__IDS__", IptvServer.esc(JSONArray(ids).toString()))
            .replace("__SEQ__", seq.toString())
            .replace("__SLATE__", slateJson)

    fun renderDashboard(
        base: String,
        channels: List<IptvServer.ServerChannel>,
        sources: List<String>
    ): String {
        val groups = channels.mapNotNull { it.channel.group }.distinct().sorted()
        val total = channels.size

        val channelCardsHtml = channels.joinToString("\n") { sc ->
            val isMpd = sc.channel.url.contains(".mpd", ignoreCase = true)
            val ext = if (isMpd) ".mpd" else ".m3u8"
            val streamUrl = "$base/live/${sc.id}$ext"
            val isVod = sc.channel.group?.contains("vod", ignoreCase = true) == true ||
                sc.channel.url.endsWith(".mp4", ignoreCase = true) ||
                sc.channel.url.endsWith(".mkv", ignoreCase = true)
            val logo = IptvServer.safeUrl(sc.channel.logo)
                ?.let { "<img src=\"${IptvServer.esc(it)}\" onerror=\"this.style.display='none'; this.nextElementSibling.style.display='block';\">" }
                .orEmpty()
            val initial = IptvServer.esc((sc.channel.name.firstOrNull() ?: '?').uppercaseChar().toString())
            val placeholderStyle = if (sc.channel.logo.isNullOrBlank()) "" else "style=\"display:none;\""
            val relayBadge = if (sc.channel.headers.isNotEmpty()) "<span class=\"tag relay\">Relay</span>" else ""
            val dashBadge = if (isMpd) "<span class=\"tag dash\">DASH</span>" else ""
            val drmBadge = if (sc.channel.drm) "<span class=\"tag drm\">DRM</span>" else ""
            val vodBadge = if (isVod) "<span class=\"tag vod\">VOD</span>" else ""

            val infoJson = JSONObject().apply {
                put("id", sc.id)
                put("name", sc.channel.name)
                put("stream_url", streamUrl)
                put("group", sc.channel.group.orEmpty())
                put("is_mpd", isMpd)
                put("is_vod", isVod)
                put("drm_type", sc.drmType)
                put("drm_key", sc.drmKey)
                put("has_headers", sc.channel.headers.isNotEmpty())
            }.toString().let(IptvServer::esc)

            """<div class="card" data-id="${IptvServer.esc(sc.id)}" data-name="${IptvServer.esc(sc.channel.name.lowercase())}" data-group="${IptvServer.esc(sc.channel.group)}" data-info="$infoJson" onclick="playCard(this)">
  <div class="logo">
    $logo
    <div class="placeholder" $placeholderStyle>$initial</div>
  </div>
  <div style="min-width: 0; flex: 1;">
    <div class="name">${IptvServer.esc(sc.channel.name)}</div>
    <div class="sub">
      <span>${IptvServer.esc(sc.channel.group)}</span>
      $relayBadge $dashBadge $drmBadge $vodBadge
    </div>
  </div>
</div>"""
        }

        val groupPillsHtml = groups.joinToString("\n") { g ->
            val count = channels.count { it.channel.group == g }
            """<button class="pill" data-group="${IptvServer.esc(g)}" onclick="selectGroup(this.dataset.group, this)">${IptvServer.esc(g)} ($count)</button>"""
        }

        return """
<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tahoe IPTV Server</title>
<script src="/assets/shaka-player.compiled.js"></script>
<style>
  :root { --bg: #07080d; --surface: rgba(255,255,255,0.06); --hover: rgba(255,255,255,0.12); --border: rgba(255,255,255,0.1); --text: #f5f5f7; --dim: #86868b; --accent: #0a84ff; }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: var(--bg); color: var(--text); font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif; padding: 24px; }
  header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 16px; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
  h1 { font-size: 24px; font-weight: 700; display: flex; align-items: center; gap: 10px; }
  .badge { background: #30d158; color: #000; font-size: 11px; font-weight: 700; padding: 2px 7px; border-radius: 5px; }
  .btn { background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 8px; padding: 8px 14px; font-size: 13px; cursor: pointer; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; }
  .btn:hover { background: var(--hover); }
  .btn.primary { background: var(--accent); color: white; border-color: var(--accent); }
  .layout { display: grid; grid-template-columns: 1fr 440px; gap: 20px; }
  @media(max-width: 900px) { .layout { grid-template-columns: 1fr; } }
  .player-box { background: #000; border: 1px solid var(--border); border-radius: 14px; overflow: hidden; position: sticky; top: 20px; }
  video { width: 100%; aspect-ratio: 16/9; background: #020204; }
  .p-info { padding: 16px; }
  .pills { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 8px; margin-bottom: 12px; }
  .pill { background: var(--surface); border: 1px solid var(--border); color: var(--dim); padding: 5px 12px; border-radius: 20px; font-size: 12px; cursor: pointer; white-space: nowrap; }
  .pill.active { background: var(--accent); color: white; border-color: var(--accent); font-weight: 600; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; max-height: 72vh; overflow-y: auto; }
  .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; cursor: pointer; }
  .card:hover { background: var(--hover); }
  .card.active { border-color: var(--accent); background: rgba(10,132,255,0.18); }
  .logo { width: 40px; height: 40px; border-radius: 8px; background: #1c1c1e; display: flex; align-items: center; justify-content: center; overflow: hidden; flex-shrink: 0; }
  .logo img { width: 100%; height: 100%; object-fit: contain; }
  .placeholder { font-weight: bold; color: var(--dim); font-size: 14px; }
  .name { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .sub { font-size: 11px; color: var(--dim); margin-top: 4px; display: flex; gap: 4px; align-items: center; flex-wrap: wrap; }
  .tag { font-size: 9px; font-weight: 700; padding: 1px 5px; border-radius: 3px; }
  .tag.relay { background: rgba(48,209,88,0.2); color: #30d158; }
  .tag.dash { background: rgba(191,90,242,0.2); color: #bf5af2; }
  .tag.drm { background: rgba(255,159,10,0.2); color: #ff9f0a; }
  .tag.vod { background: rgba(255,45,85,0.2); color: #ff2d55; }
  .search { width: 100%; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 10px 14px; color: white; margin-bottom: 10px; font-size: 14px; }
  .toast { position: fixed; bottom: 20px; right: 20px; background: #222; border: 1px solid var(--border); color: white; padding: 10px 16px; border-radius: 8px; opacity: 0; transition: opacity 0.2s; z-index: 999; }
  .toast.show { opacity: 1; }
</style>
</head>
<body>
<header>
  <div>
    <h1>Tahoe IPTV Server <span class="badge">ONLINE</span></h1>
    <div style="font-size: 13px; color: var(--dim); margin-top: 4px;">$total saluran siap ditonton · Sumber: ${IptvServer.esc(sources.joinToString(", "))}</div>
  </div>
  <div style="display: flex; gap: 8px; flex-wrap: wrap;">
    <button class="btn primary" onclick="copy('$base/playlist.m3u', 'Playlist Master disalin!')">📋 Salin M3U Master</button>
    <a href="$base/playlist.m3u" class="btn" target="_blank">⬇️ Download</a>
    <button class="btn" onclick="copy('$base/epg.xml', 'EPG disalin!')">📅 Salin EPG</button>
  </div>
</header>
<div class="layout">
  <div>
    <input type="text" id="q" class="search" placeholder="Cari saluran (contoh: RCTI, Trans TV, beIN, Sports, HBO, Movies, VOD)..." oninput="filter()">
    <div class="pills" id="pills">
      <button class="pill active" onclick="selectGroup(null, this)">Semua ($total)</button>
      $groupPillsHtml
    </div>
    <div class="grid" id="grid">
      $channelCardsHtml
    </div>
  </div>
  <div class="player-box">
    <video id="v" controls autoplay playsinline></video>
    <div class="p-info">
      <h3 id="pName">Pilih saluran untuk menonton</h3>
      <p id="pSub" style="font-size: 12px; color: var(--dim); margin-top: 4px;">Klik salah satu saluran di sebelah kiri.</p>
      <div id="drmBox" style="display:none; margin-top:10px; font-size:12px; background:rgba(255,255,255,0.06); padding:8px 10px; border-radius:8px;"></div>
      <div style="margin-top: 14px; display: flex; gap: 8px;">
        <button id="copyBtn" class="btn" style="display: none;" onclick="copyCurrent()">🔗 Salin Link Stream Ini</button>
      </div>
    </div>
  </div>
</div>
<div id="t" class="toast"></div>
<script>
  let shakaPlayer = null;
  let currentCh = null;
  let activeGroup = null;
  const v = document.getElementById('v');

  document.addEventListener('DOMContentLoaded', async () => {
    shaka.polyfill.installAll();
    if (shaka.Player.isBrowserSupported()) {
      shakaPlayer = new shaka.Player(v);
      shakaPlayer.addEventListener('error', (e) => {
        console.error('Shaka error:', e.detail);
        document.getElementById('pSub').innerText = (currentCh?.group || '') + ' · Gagal memuat siaran (Error ' + e.detail.code + ')';
      });
    }
    const first = document.querySelector('.card');
    if (first) playCard(first);
  });

  function selectGroup(grp, el) {
    activeGroup = grp;
    document.querySelectorAll('.pill').forEach(p => p.classList.remove('active'));
    el.classList.add('active');
    filter();
  }

  function filter() {
    const q = document.getElementById('q').value.toLowerCase().trim();
    document.querySelectorAll('.card').forEach(c => {
      const matchQ = !q || c.dataset.name.includes(q);
      const matchG = !activeGroup || c.dataset.group === activeGroup;
      c.style.display = (matchQ && matchG) ? 'flex' : 'none';
    });
  }

  async function playCard(el) {
    const info = JSON.parse(el.dataset.info);
    currentCh = info;

    document.querySelectorAll('.card').forEach(c => c.classList.toggle('active', c.dataset.id === info.id));
    document.getElementById('pName').innerText = info.name;
    document.getElementById('pSub').innerText = info.group + ' · Memuat siaran...';
    document.getElementById('copyBtn').style.display = 'inline-flex';

    const drmBox = document.getElementById('drmBox');
    if (info.drm_type || info.drm_key) {
      drmBox.style.display = 'block';
      drmBox.textContent = '';
      const strong = document.createElement('strong');
      strong.textContent = 'DRM: ';
      drmBox.appendChild(strong);
      drmBox.appendChild(document.createTextNode((info.drm_type || 'ClearKey') + ' '));
      if (info.drm_key) {
        drmBox.appendChild(document.createElement('br'));
        const code = document.createElement('code');
        code.textContent = info.drm_key;
        drmBox.appendChild(code);
      }
    } else {
      drmBox.style.display = 'none';
    }

    if (!shakaPlayer) return;
    shakaPlayer.resetConfiguration();
    shakaPlayer.getNetworkingEngine().clearAllRequestFilters();

    // Auto-configure ClearKey DRM
    if (info.drm_type && (info.drm_type.includes('clearkey') || info.drm_type.includes('org.w3.clearkey')) && info.drm_key) {
      const cleanKey = info.drm_key.split('&')[0];
      const parts = cleanKey.split(':');
      if (parts.length === 2) {
        shakaPlayer.configure({ drm: { clearKeys: { [parts[0].trim()]: parts[1].trim() } } });
      }
    } else if (info.drm_type && info.drm_type.includes('widevine') && info.drm_key) {
      shakaPlayer.configure({ drm: { servers: { 'com.widevine.alpha': info.drm_key } } });
    }

    try {
      await shakaPlayer.load(info.stream_url);
      v.play().catch(()=>{});
      document.getElementById('pSub').innerText = info.group + ' · Live';
    } catch (err) {
      console.warn('Playback error:', err);
    }
  }

  function copyCurrent() {
    if (currentCh) copy(currentCh.stream_url, 'Link stream disalin!');
  }

  function copy(t, msg) {
    navigator.clipboard.writeText(t).then(() => {
      const el = document.getElementById('t'); el.innerText = msg; el.classList.add('show');
      setTimeout(() => el.classList.remove('show'), 2000);
    });
  }
</script>
</body>
</html>
""".trimIndent()
    }

    private const val WATCH_HTML = """<!DOCTYPE html>
<html lang="id"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__ — Tahoe TV</title>
<script src="/assets/hls.min.js"></script>
<style>
  html,body{margin:0;height:100%;background:#000;color:#f5f5f7;font-family:"Segoe UI",-apple-system,system-ui,sans-serif;overflow:hidden}
  video{position:fixed;inset:0;width:100%;height:100%;background:#000}
  #top{position:fixed;left:0;right:0;top:0;padding:14px 18px;display:flex;align-items:center;gap:10px;background:linear-gradient(#000b,#0000);transition:opacity .4s;z-index:2}
  .live{background:#ff453a;color:#fff;font-weight:700;font-size:12px;padding:2px 7px;border-radius:5px}
  #name{font-weight:600;font-size:17px}
  #fmt{margin-left:auto;font:12px ui-monospace,Consolas,monospace;opacity:.75}
  .btn{background:#ffffff26;color:#fff;border:0;border-radius:99px;padding:7px 13px;font-size:14px;cursor:pointer}
  .btn:hover{background:#ffffff40}
  #veil{position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;background:#07080dcc;transition:opacity .3s;z-index:1;pointer-events:none}
  #veil.hide{opacity:0}
  .spin{width:34px;height:34px;border:3px solid #fff3;border-top-color:#fff;border-radius:50%;animation:s 1s linear infinite}
  @keyframes s{to{transform:rotate(360deg)}}
  #msg{max-width:520px;text-align:center;font-size:15px;line-height:1.5;opacity:.9}
  #sound{position:fixed;bottom:70px;left:50%;transform:translateX(-50%);display:none;background:#f5f5f7;color:#111;border:0;border-radius:99px;padding:10px 18px;font-weight:600;cursor:pointer;z-index:3}
  .idle #top{opacity:0}
</style></head><body>
<video id="v" playsinline controls></video>
<div id="top"><span class="live">LIVE</span><span id="name">__TITLE__</span>
  <button class="btn" id="prev" title="Saluran sebelumnya (↑)">◀</button><button class="btn" id="next" title="Saluran berikutnya (↓)">▶</button>
  <span id="fmt"></span></div>
<div id="veil"><div class="spin" id="spin"></div><div id="msg">Memuat __TITLE__…</div></div>
<button id="sound">🔊 Klik untuk menyalakan suara</button>
<script>
const v = document.getElementById('v'), veil = document.getElementById('veil'), msg = document.getElementById('msg'), spin = document.getElementById('spin');
const SLATE = __SLATE__;
let ids = JSON.parse("__IDS__".replace(/&quot;/g,'"')), seq = __SEQ__, maxH = 0, lat = 20, i = 0, hls = null, timer = null, reported = false, session = false;
function post(q){ return fetch('/api/watch?' + q, { method: 'POST' }).catch(() => {}); }
function fail(t){ spin.style.display='none'; msg.textContent=t; veil.classList.remove('hide'); post('event=failed&s=' + seq + '&msg=' + encodeURIComponent(t)); }
function next(){ i++; start(); }
// #76: every source failed — the apology in Indonesian, English and Chinese (viewer's language first), retried every 30 s
let slateTimer = null, slateTries = 0;
function slate(){
  const t = SLATE, order = t.order;
  spin.style.display = 'none'; veil.classList.remove('hide');
  msg.textContent = '';
  order.forEach((l, k) => { const p = document.createElement('div'); p.textContent = t.msg[l]; p.style.cssText = k ? 'opacity:.62;font-size:13px;margin-top:8px' : 'font-size:17px;font-weight:600'; msg.appendChild(p); });
  const st = document.createElement('div'); st.style.cssText = 'opacity:.5;font-size:12px;margin-top:14px'; msg.appendChild(st);
  post('event=failed&s=' + seq + '&msg=' + encodeURIComponent(t.msg[order[0]]));
  clearInterval(slateTimer);
  if (slateTries >= t.max) { st.textContent = t.reason + '  ·  ' + t.waiting; return; }
  let left = t.secs; const mine = seq;
  st.textContent = t.reason + '  ·  ' + t.retrying.replace('%s', left);
  slateTimer = setInterval(() => {
    if (mine !== seq) { clearInterval(slateTimer); return; }
    if (--left <= 0) { clearInterval(slateTimer); slateTries++; i = 0; start(); }
    else st.textContent = t.reason + '  ·  ' + t.retrying.replace('%s', left);
  }, 1000);
}
function tryPlay(){
  v.play().catch(() => { v.muted = true; v.play().catch(()=>{}); document.getElementById('sound').style.display='block'; });
}
function start(){
  clearTimeout(timer); reported = false;
  if (hls) { hls.destroy(); hls = null; }
  if (i >= ids.length) { slate(); return; }
  spin.style.display=''; msg.textContent='Memuat…'; veil.classList.remove('hide');
  const src = '/live/' + encodeURIComponent(ids[i]) + '.m3u8';
  if (window.Hls && Hls.isSupported()) {
    hls = new Hls({ liveSyncDuration: lat, liveMaxLatencyDuration: lat * 3, enableWorker: true });
    hls.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) { if (d.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError(); else next(); } });
    // start playing once the manifest is in (calling play() earlier can be rejected before there's a source)
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      if (maxH) {
        let cap = -1, smallest = 0;
        hls.levels.forEach((l, k) => { if (l.height && l.height <= maxH && (cap < 0 || l.height >= hls.levels[cap].height)) cap = k;
                                       if ((l.height || 1e9) < (hls.levels[smallest].height || 1e9)) smallest = k; });
        hls.autoLevelCapping = cap >= 0 ? cap : smallest;
      }
      tryPlay();
    });
    hls.on(Hls.Events.LEVEL_SWITCHED, (e, d) => { const l = hls.levels[d.level]; if (l) document.getElementById('fmt').textContent = (l.height ? l.height + 'p · ' : '') + (l.videoCodec || '').split('.')[0].replace('avc1','H.264').replace('hvc1','H.265'); });
    hls.loadSource(src); hls.attachMedia(v);
  } else { v.src = src; v.addEventListener('loadedmetadata', tryPlay, { once: true }); }
  timer = setTimeout(() => { if (v.readyState < 3) next(); }, 20000);
}
function load(s){ clearInterval(slateTimer); slateTries = 0; session = true; seq = s.seq; ids = s.ids; maxH = s.q || 0; lat = Math.min(60, Math.max(4, s.lat || 20)); i = 0;
  document.getElementById('name').textContent = s.title; document.title = s.title + ' — Tahoe TV'; start(); }
// follow the app: a new channel replaces this one; Stop stops and closes the window
async function poll(){
  try {
    const s = await (await fetch('/api/watch', { cache: 'no-store' })).json();
    if (s.stopped && session) { if (hls) { hls.destroy(); hls = null; } v.pause(); v.removeAttribute('src'); fail('Pemutar dihentikan.'); window.close(); }
    else if (!s.stopped && s.seq !== seq && s.ids.length) load(s);
  } catch (e) {}
}
v.addEventListener('playing', () => { clearTimeout(timer); clearInterval(slateTimer); slateTries = 0; veil.classList.add('hide'); if (!reported) { reported = true; post('event=playing&s=' + seq); } });
v.addEventListener('error', () => { if (!hls) next(); });
document.getElementById('sound').onclick = (e) => { v.muted = false; e.target.style.display='none'; };
document.getElementById('prev').onclick = () => post('zap=-1&s=' + seq);
document.getElementById('next').onclick = () => post('zap=1&s=' + seq);
let idle; document.addEventListener('mousemove', () => { document.body.classList.remove('idle'); clearTimeout(idle); idle = setTimeout(()=>document.body.classList.add('idle'), 3000); });
document.addEventListener('keydown', (e) => {
  if (e.key === ' ') { v.paused ? v.play() : v.pause(); e.preventDefault(); }
  if (e.key === 'f' || e.key === 'F') { document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen(); }
  if (e.key === 'm' || e.key === 'M') { v.muted = !v.muted; }
  if (e.key === 'ArrowUp' || e.key === 'PageUp') { post('zap=-1&s=' + seq); e.preventDefault(); }
  if (e.key === 'ArrowDown' || e.key === 'PageDown') { post('zap=1&s=' + seq); e.preventDefault(); }
});
window.addEventListener('pagehide', () => navigator.sendBeacon('/api/watch?event=closed&s=' + seq));
// opened by the app (?s=…) but already stopped: don't start playing; opened by hand: play the URL's channel
const fromApp = new URLSearchParams(location.search).has('s');
fetch('/api/watch', { cache: 'no-store' }).then(r => r.json()).then(s => {
  if (s.ids && s.ids.length && !s.stopped) load(s);
  else if (fromApp && s.stopped) { spin.style.display = 'none'; msg.textContent = 'Pemutar dihentikan.'; window.close(); }
  else if (ids.length) start(); else fail('Tidak ada saluran yang dipilih.'); })
  .catch(() => { if (ids.length) start(); });
setInterval(poll, 1500);
</script></body></html>"""
}
