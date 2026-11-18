// /watch: a small web player for open HLS channels from the public directory (#183).
// hls.js (loaded only when a channel starts) where Media Source Extensions exist, native HLS on iPhone.
(function () {
  const HLS_JS = "https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js";
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch {} }
  };
  const video = $("#video");
  // hls.js wherever Media Source Extensions exist (Chrome, Firefox, Edge, desktop Safari); the browser's own HLS only
  // without them (iPhone Safari). Some browsers claim native HLS but fail on real streams, so hls.js goes first.
  const mse = "MediaSource" in window || "ManagedMediaSource" in window;
  const native = !mse && !!video.canPlayType("application/vnd.apple.mpegurl");

  // ------------------------------------------------------------ language (same choice as the landing page)
  const english = {};
  $$("[data-i18n]").forEach(el => { english[el.dataset.i18n] = el.textContent; });
  $$("[data-i18n-label]").forEach(el => { english[el.dataset.i18nLabel] = el.getAttribute("aria-label"); });
  const EN_EXTRA = {
    "w.cant": "This channel doesn't play in the browser. Try another one, or watch it in the Tahoe app.",
    "w.loading": "Connecting…", "w.none": "No channels match.", "w.offline": "The channel directory is not reachable right now.",
    "w.search": "Search channels", "w.all": "All"
  };
  let lang = "en";
  const t = k => (window.I18N[lang] || {})[k] || english[k] || EN_EXTRA[k] || "";
  function normLang(l) { return l ? l.toLowerCase().split(/[-_]/)[0] : ""; }
  function pickLang() {
    const q = normLang(new URLSearchParams(location.search).get("lang"));
    const nav = (navigator.languages || [navigator.language || "en"]).map(normLang);
    return [q, normLang(store.get("lang")), ...nav].find(l => l && (l === "en" || window.I18N[l])) || "en";
  }
  function applyLang(l) {
    lang = l;
    document.documentElement.lang = l === "zh" ? "zh-Hans" : l;
    document.documentElement.dir = (window.I18N_RTL || []).includes(l) ? "rtl" : "ltr";
    $$("[data-i18n]").forEach(el => { if (!el.dataset.dynamic) el.textContent = t(el.dataset.i18n); });
    $$("[data-i18n-label]").forEach(el => el.setAttribute("aria-label", t(el.dataset.i18nLabel)));
    $("#w-search").placeholder = t("w.search");
    $("#w-search").setAttribute("aria-label", t("w.search"));
    $$(".lang button").forEach(b => { b.classList.toggle("on", b.dataset.lang === l); b.setAttribute("aria-pressed", String(b.dataset.lang === l)); });
    document.title = (current ? current.name + " · " : t("w.title") + " · ") + "Tahoe TV";
    renderGroups();
  }
  $$(".lang button").forEach(b => b.addEventListener("click", () => { store.set("lang", b.dataset.lang); applyLang(b.dataset.lang); }));

  // ------------------------------------------------------------ directory
  let channels = [], groups = [], group = "", current = null, hls = null;
  const playable = () => channels.filter(c => c.stream);

  function load() {
    const q = encodeURIComponent($("#w-search").value.trim());
    fetch(`/api/public/channels/preview?q=${q}&group=${encodeURIComponent(group)}`)
      .then(r => r.ok ? r.json() : Promise.reject())
      .then(data => {
        groups = data.groups || [];
        // channels the browser can play first; the rest stay visible but marked "app only"
        channels = (data.items || []).slice().sort((a, b) => (b.stream ? 1 : 0) - (a.stream ? 1 : 0));
        renderGroups(); renderList();
        const want = new URLSearchParams(location.search).get("ch");
        if (!current && want) {
          const hit = channels.find(c => c.name.toLowerCase() === want.toLowerCase());
          if (hit) select(hit, false);
        }
      })
      .catch(() => { $("#w-list").innerHTML = ""; $("#w-list").append(li(t("w.offline"))); });
  }
  function li(text) { const el = document.createElement("li"); el.className = "dl-sub"; el.textContent = text; return el; }

  function renderGroups() {
    const box = $("#w-groups"); if (!box) return;
    box.textContent = "";
    ["", ...groups.slice(0, 20)].forEach(g => {
      const b = document.createElement("button");
      b.type = "button"; b.className = "chip-btn" + (g === group ? " on" : "");
      b.textContent = g || t("w.all");
      b.setAttribute("aria-pressed", String(g === group));
      b.addEventListener("click", () => { group = g; load(); });
      box.appendChild(b);
    });
  }

  function renderList() {
    const list = $("#w-list"); list.textContent = "";
    if (!channels.length) { list.append(li(t("w.none"))); return; }
    channels.forEach(c => {
      const item = document.createElement("li");
      const b = document.createElement("button");
      b.type = "button"; b.className = "w-row" + (c === current ? " on" : "") + (c.stream ? "" : " off");
      if (c === current) b.setAttribute("aria-current", "true");
      const logo = document.createElement("span"); logo.className = "ch-logo";
      if (c.logo) { const img = document.createElement("img"); img.src = c.logo; img.alt = ""; img.loading = "lazy"; logo.appendChild(img); }
      else logo.textContent = (c.name || "?").charAt(0).toUpperCase();
      const meta = document.createElement("span"); meta.className = "ch-meta";
      const name = document.createElement("b"); name.textContent = c.name;
      const sub = document.createElement("small"); sub.textContent = c.group + (c.stream ? "" : " · " + (c.drm ? "DRM" : c.format) + " · app");
      meta.append(name, sub);
      b.append(logo, meta);
      b.addEventListener("click", () => select(c, true));
      item.appendChild(b); list.appendChild(item);
    });
  }

  // ------------------------------------------------------------ playback
  function message(text, showApp) {
    $("#screen-msg").hidden = !text;
    const p = $("#msg-text"); p.dataset.dynamic = "1"; p.textContent = text || "";
    $("#msg-app").hidden = !showApp;
  }

  function select(c, user) {
    current = c;
    $("#w-name").dataset.dynamic = "1"; $("#w-name").textContent = c.name;
    $("#w-group").dataset.dynamic = "1"; $("#w-group").textContent = c.group + " · " + c.source;
    const logo = $("#w-logo"); logo.textContent = "";
    if (c.logo) { const img = document.createElement("img"); img.src = c.logo; img.alt = ""; logo.appendChild(img); }
    else logo.textContent = c.name.charAt(0).toUpperCase();
    document.title = c.name + " · Tahoe TV";
    const url = new URL(location.href); url.searchParams.set("ch", c.name); history.replaceState(null, "", url);
    renderList();
    stop();
    if (!c.stream) { $("#w-live").hidden = true; message(t("w.cant"), true); return; }
    message(t("w.loading"), false);
    play(c.stream, user);
  }

  function stop() {
    if (hls) { hls.destroy(); hls = null; }
    video.removeAttribute("src"); video.load();
  }

  function play(src, user) {
    const start = () => video.play().catch(() => { /* autoplay blocked: the controls stay available */ });
    if (native) { video.src = src; start(); return; }
    loadHlsJs().then(Hls => {
      if (!Hls.isSupported()) { message(t("w.cant"), true); return; }
      hls = new Hls({ enableWorker: false, maxBufferLength: 20 });
      hls.on(Hls.Events.ERROR, (_, d) => { if (d.fatal) fail(); });
      hls.loadSource(src); hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, start);
    }).catch(fail);
    if (user) video.focus({ preventScroll: true });
  }
  function fail() { stop(); $("#w-live").hidden = true; message(t("w.cant"), true); }

  video.addEventListener("playing", () => { message("", false); $("#w-live").hidden = false; });
  video.addEventListener("error", () => { if (video.getAttribute("src")) fail(); });

  let hlsPromise = null;
  function loadHlsJs() {
    if (window.Hls) return Promise.resolve(window.Hls);
    return hlsPromise || (hlsPromise = new Promise((ok, no) => {
      const s = document.createElement("script");
      s.src = HLS_JS; s.crossOrigin = "anonymous"; s.onload = () => ok(window.Hls); s.onerror = no;
      document.head.appendChild(s);
    }));
  }

  // ------------------------------------------------------------ prev/next and keys
  function step(d) {
    const list = playable(); if (!list.length) return;
    const i = list.indexOf(current);
    select(list[(i + d + list.length) % list.length], true);
  }
  $("#w-prev").addEventListener("click", () => step(-1));
  $("#w-next").addEventListener("click", () => step(1));
  document.addEventListener("keydown", e => {
    if (e.target.closest("input, textarea") || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === "ArrowUp" || e.key === "PageUp") { e.preventDefault(); step(-1); }
    else if (e.key === "ArrowDown" || e.key === "PageDown") { e.preventDefault(); step(1); }
    else if (e.key === "m" || e.key === "M") video.muted = !video.muted;
    else if (e.key === "f" || e.key === "F") {
      if (document.fullscreenElement) document.exitFullscreen(); else $("#screen").requestFullscreen?.();
    }
  });

  let typing = null;
  $("#w-search").addEventListener("input", () => { clearTimeout(typing); typing = setTimeout(load, 250); });

  applyLang(pickLang());
  load();
})();
