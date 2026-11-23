(function () {
  const REPO = "fk0u/cleanyourtv-tahoe";
  const BLOB = "https://github.com/" + REPO + "/blob/main/";
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch {} }
  };

  function escHtml(s) {
    return String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  // ---------------------------------------------------------------- language
  const english = {};
  $$("[data-i18n]").forEach(el => { english[el.dataset.i18n] = el.textContent; });
  const OS = (() => {
    const u = navigator.userAgent;
    const p = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || "";
    if (/Android/i.test(u)) return "android";
    if (/Win/i.test(p)) return "windows";
    if (/Mac/i.test(p) && !/iPhone|iPad/.test(u) && !(navigator.maxTouchPoints > 1)) return "mac";
    if (/Linux/i.test(p + " " + u) && !/Android/i.test(u)) return "linux";
    return "";
  })();
  const OS_LABEL = { android: "· Android", windows: "· Windows", mac: "· macOS", linux: "· Debian / Ubuntu" };

  function normLang(l) { return l ? l.toLowerCase().split(/[-_]/)[0] : ""; }
  function pick() {
    const q = normLang(new URLSearchParams(location.search).get("lang"));
    const saved = normLang(store.get("lang"));
    const nav = (navigator.languages || [navigator.language || "en"]).map(normLang);
    return [q, saved, ...nav].find(l => l && (l === "en" || window.I18N[l])) || "en";
  }

  let current = "en";
  function apply(lang) {
    current = lang;
    const t = window.I18N[lang] || {};
    document.documentElement.lang = lang === "zh" ? "zh-Hans" : lang;
    document.documentElement.dir = window.I18N_RTL && window.I18N_RTL.includes(lang) ? "rtl" : "ltr";
    $$("[data-i18n]").forEach(el => {
      const k = el.dataset.i18n;
      el.textContent = t[k] || english[k] || el.textContent;
    });
    $$(".lang button").forEach(b => {
      b.classList.toggle("on", b.dataset.lang === lang);
      b.setAttribute("aria-pressed", String(b.dataset.lang === lang));
    });
    const inst = $("#install-link");
    if (inst) inst.href = BLOB + (t._docs || "docs/INSTALL.md");
    document.title = t._title || "Tahoe TV";
    $$("button", $("#dots")).forEach((d, k) => d.setAttribute("aria-label", (t["dot.label"] || "Screenshot ") + (k + 1)));
    caption();
  }
  $$(".lang button").forEach(b => b.addEventListener("click", () => { store.set("lang", b.dataset.lang); apply(b.dataset.lang); }));

  // ---------------------------------------------------------------- download device filter
  const filterBtns = $$("#dl-filter button");
  filterBtns.forEach(btn => {
    btn.addEventListener("click", () => {
      const f = btn.dataset.filter;
      filterBtns.forEach(b => b.classList.toggle("on", b === btn));
      $$(".dl[data-kind]").forEach(card => {
        const kinds = (card.dataset.kind || "").split(/\s+/);
        card.hidden = f !== "all" && !kinds.includes(f);
      });
    });
  });

  // ---------------------------------------------------------------- feature tabs
  const tabBtns = $$("#features .tabs button");
  function activateTab(b, focus) {
    tabBtns.forEach(x => {
      const on = x === b;
      x.classList.toggle("on", on);
      x.setAttribute("aria-selected", String(on));
      x.tabIndex = on ? 0 : -1;
    });
    $$("[data-panel]").forEach(p => {
      p.hidden = p.dataset.panel !== b.dataset.tab;
      if (!p.hidden) $$(".reveal", p).forEach(el => el.classList.add("in"));
    });
    if (focus) b.focus();
  }
  tabBtns.forEach((b, idx) => {
    b.addEventListener("click", () => activateTab(b, false));
    b.addEventListener("keydown", e => {
      let next = null;
      if (e.key === "ArrowRight") next = (idx + 1) % tabBtns.length;
      else if (e.key === "ArrowLeft") next = (idx - 1 + tabBtns.length) % tabBtns.length;
      else if (e.key === "Home") next = 0;
      else if (e.key === "End") next = tabBtns.length - 1;
      if (next !== null) { e.preventDefault(); activateTab(tabBtns[next], true); }
    });
  });

  // ---------------------------------------------------------------- slideshow
  const slides = $$(".tv-screen .slide");
  let at = 0, timer = null, userStopped = false;
  const dots = $("#dots");
  if (dots && slides.length) {
    slides.forEach((_, i) => {
      const d = document.createElement("button");
      d.setAttribute("aria-label", "Screenshot " + (i + 1));
      d.addEventListener("click", () => show(i, true));
      dots.appendChild(d);
    });
  }
  function caption() {
    const s = slides[at];
    const capEl = $("#caption");
    if (!s || !capEl) return;
    const k = s.dataset.cap;
    capEl.textContent = (window.I18N[current] || {})[k] || ({
      "cap.home": "Home", "cap.hero": "Hero + actions", "cap.launchpad": "Launchpad",
      "cap.cc": "Control Center", "cap.assistant": "Gemini assistant",
      "cap.prayer": "Prayer times & adhan", "cap.pair": "Connect a phone (QR + code)"
    })[k] || "";
  }
  function show(i, user) {
    if (!slides.length) return;
    slides[at].classList.remove("on");
    at = (i + slides.length) % slides.length;
    slides[at].classList.add("on");
    $$("button", dots).forEach((d, k) => {
      const on = k === at;
      d.classList.toggle("on", on);
      if (on) d.setAttribute("aria-current", "true");
      else d.removeAttribute("aria-current");
    });
    caption();
    if (user) { userStopped = true; clearInterval(timer); timer = null; }
  }
  $("#slide-prev")?.addEventListener("click", () => show(at - 1, true));
  $("#slide-next")?.addEventListener("click", () => show(at + 1, true));
  const still = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!still && slides.length) timer = setInterval(() => { if (!userStopped) show(at + 1); }, 4500);
  show(0);

  if (OS) {
    $("#cta-os").textContent = OS_LABEL[OS];
    $$(".dl[data-os='" + OS + "']").forEach(c => c.classList.add("mine"));
  }

  // reveal
  const io = "IntersectionObserver" in window ? new IntersectionObserver(es => es.forEach(e => {
    if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
  }), { threshold: .08 }) : null;
  $$(".reveal").forEach((el, i) => {
    el.style.transitionDelay = (i % 4) * 55 + "ms";
    io ? io.observe(el) : el.classList.add("in");
  });
  $$(".card").forEach(c => c.addEventListener("pointermove", e => {
    const r = c.getBoundingClientRect();
    c.style.setProperty("--mx", (e.clientX - r.left) + "px");
    c.style.setProperty("--my", (e.clientY - r.top) + "px");
  }));

  // ---------------------------------------------------------------- update modal
  const modal = $("#update-modal");
  function openModal({ tag = "Release", title = "Tahoe TV Update", body = "", href = "#start" }) {
    if (!modal) return;
    $("#modal-tag").textContent = tag;
    $("#modal-title").textContent = title;
    $("#modal-body").textContent = body;
    $("#modal-dl").href = href;
    modal.hidden = false;
  }
  function closeModal() { if (modal) modal.hidden = true; }
  $("#modal-close")?.addEventListener("click", closeModal);
  $("#modal-ok")?.addEventListener("click", closeModal);
  modal?.addEventListener("click", e => { if (e.target === modal) closeModal(); });

  let latestData = null;
  $("#version-pill")?.addEventListener("click", () => {
    if (!latestData) return;
    openModal({
      tag: "v" + latestData.version + (latestData.date ? " · " + latestData.date : ""),
      title: "Tahoe TV " + latestData.version,
      body: latestData.notes || "Latest multi-platform build with SHA-256 verification.",
      href: "#start",
    });
  });

  // ---------------------------------------------------------------- asset roles table
  const ROLE_DESC = {
    "TahoeTV-Android-TV.apk": "Android TV Only · Tahoe TV Launcher (Smart TV / TV Stick)",
    "TahoeTV-Phone-and-Tablet.apk": "Android Phone & Tablet · Tahoe TV (Watch + Remote + TV Setup)",
    "TahoeTV-App-for-TV-and-Phone.apk": "Legacy TV APK filename",
    "TahoeTV-Phone-Remote-and-Setup.apk": "Legacy Phone APK filename",
    "TahoeTV-macos-intel.dmg": "macOS Intel",
    "TahoeTV-macos-arm64.dmg": "macOS Apple Silicon",
    "TahoeTV-windows.msi": "Windows 10 / 11 (64-bit)",
    "TahoeTV-linux-amd64.deb": "Debian / Ubuntu Linux (amd64)",
    "tahoe-launcher.apk": "Legacy TV APK filename",
    "tahoe-tv.apk": "Legacy Phone APK filename",
  };
  function fmtSize(bytes) {
    if (!bytes) return "—";
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  }
  function renderRelease(rel) {
    latestData = rel;
    if (!rel) return;
    if (rel.version) {
      $("#version").textContent = "v" + rel.version;
      $("#stat-rel").textContent = "v" + rel.version;
      $("#stat-rel-date").textContent = rel.date || "Verified";
    }
    const tb = $("#sha-tbody");
    if (!tb || !Array.isArray(rel.assets)) return;
    const rows = rel.assets.filter(a => a.name !== "SHA256SUMS");
    if (!rows.length) {
      tb.innerHTML = '<tr><td colspan="4" class="dl-sub">No installer assets found in latest release.</td></tr>';
      return;
    }
    tb.innerHTML = rows.map(a => {
      const safeName = escHtml(a.name);
      const safeUrl = /^https?:\/\//i.test(a.url || "") ? escHtml(a.url) : "#";
      const sha = a.sha256 ? `<code>${escHtml(a.sha256)}</code>` : '<span class="dl-sub">Pending SHA256SUMS</span>';
      return `<tr><td><a href="${safeUrl}">${safeName}</a></td><td>${escHtml(ROLE_DESC[a.name] || "Release asset")}</td><td>${fmtSize(a.size)}</td><td>${sha}</td></tr>`;
    }).join("");

    const findUrl = name => rel.assets.find(x => x.name === name)?.url;
    const uMain = findUrl("TahoeTV-Android-TV.apk") || findUrl("TahoeTV-App-for-TV-and-Phone.apk") || findUrl("tahoe-launcher.apk");
    const uRem = findUrl("TahoeTV-Phone-and-Tablet.apk") || findUrl("TahoeTV-Phone-Remote-and-Setup.apk") || findUrl("tahoe-tv.apk");
    if (uMain && $("#dl-main-apk")) $("#dl-main-apk").href = uMain;
    if (uRem && $("#dl-remote-apk")) $("#dl-remote-apk").href = uRem;
    if (findUrl("TahoeTV-macos-intel.dmg") && $("#dl-mac-intel")) $("#dl-mac-intel").href = findUrl("TahoeTV-macos-intel.dmg");
    if (findUrl("TahoeTV-macos-arm64.dmg") && $("#dl-mac-arm")) $("#dl-mac-arm").href = findUrl("TahoeTV-macos-arm64.dmg");
    if (findUrl("TahoeTV-windows.msi") && $("#dl-win")) $("#dl-win").href = findUrl("TahoeTV-windows.msi");
    if (findUrl("TahoeTV-linux-amd64.deb") && $("#dl-linux")) $("#dl-linux").href = findUrl("TahoeTV-linux-amd64.deb");
  }

  // ---------------------------------------------------------------- real catalogue size (shown only when the server could count it)
  fetch("/api/public/catalog").then(r => r.ok ? r.json() : Promise.reject()).then(c => {
    if (!c.channels || !c.countries) return;
    $("#stat-channels").textContent = Number(c.channels).toLocaleString();
    $("#stat-countries").textContent = Number(c.countries).toLocaleString();
    $("#hero-stat-channels").hidden = false;
    $("#hero-stat-countries").hidden = false;
    const wc = $("#world-count"); if (wc) { wc.removeAttribute("data-i18n"); wc.textContent = Number(c.countries).toLocaleString(); }
  }).catch(() => {});

  // ---------------------------------------------------------------- API stats & release
  fetch("/api/public/stats").then(r => r.ok ? r.json() : Promise.reject()).then(s => {
    $("#stat-installed").textContent = Number(s.total_installed || 0).toLocaleString();
    $("#stat-active24").textContent = Number(s.active_24h || 0).toLocaleString();
    $("#stat-online").textContent = Number(s.online_now || 0).toLocaleString();
    if (s.latest_release) renderRelease(s.latest_release);
    if (s.announcement || (s.update_policy && s.update_policy.force_modal)) {
      const bar = $("#live-notice");
      bar.hidden = false;
      $("#live-notice-text").textContent = s.announcement || s.update_policy.modal_title || ("Tahoe TV v" + (s.latest_release?.version || "") + " available");
      const btn = $("#btn-open-update-modal");
      btn.hidden = false;
      btn.onclick = () => openModal({
        tag: s.update_policy?.force_modal ? "Important Update" : "Announcement",
        title: s.update_policy?.modal_title || ("Tahoe TV " + (s.latest_release?.version || "")),
        body: s.update_policy?.modal_message || s.announcement || s.latest_release?.notes || "",
      });
    }
  }).catch(() => {
    fetch("https://api.github.com/repos/" + REPO + "/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
      .then(r => r.ok ? r.json() : Promise.reject())
      .then(r => renderRelease({
        version: String(r.tag_name || "").replace(/^v/, ""),
        date: String(r.published_at || "").slice(0, 10),
        notes: r.body || "",
        assets: (r.assets || []).map(a => ({ name: a.name, url: a.browser_download_url, size: a.size, sha256: null }))
      })).catch(() => {});
  });

  // ---------------------------------------------------------------- Channel Explorer
  let activeGroup = "";
  function loadChannels() {
    const q = encodeURIComponent($("#ch-search")?.value?.trim() || "");
    const g = encodeURIComponent(activeGroup);
    fetch(`/api/public/channels/preview?q=${q}&group=${g}`).then(r => r.ok ? r.json() : Promise.reject()).then(data => {
      const gBox = $("#ch-groups");
      if (gBox) {
        gBox.innerHTML = `<button class="chip-btn ${activeGroup === "" ? "on" : ""}" data-g="">All (${data.total})</button>` +
          data.groups.slice(0, 20).map(gr => `<button class="chip-btn ${activeGroup === gr ? "on" : ""}" data-g="${escHtml(gr)}">${escHtml(gr)}</button>`).join("");
        $$("button", gBox).forEach(b => b.addEventListener("click", () => { activeGroup = b.dataset.g || ""; loadChannels(); }));
      }
      const grid = $("#ch-grid");
      if (!grid) return;
      if (!data.items.length) {
        grid.innerHTML = '<p class="dl-sub">No channels match your filter.</p>';
        return;
      }
      grid.innerHTML = data.items.map(ch => {
        const init = escHtml((ch.name || "?").charAt(0).toUpperCase());
        const logoHtml = ch.logo ? `<img src="${escHtml(ch.logo)}" alt="" loading="lazy">` : init;
        const drmBadge = ch.drm ? '<span class="tag drm">DRM</span>' : "";
        const body = `<div class="ch-logo">${logoHtml}</div><div class="ch-meta"><b>${escHtml(ch.name)}</b><small>${escHtml(ch.group)} · <span class="tag">${escHtml(ch.format)}</span>${drmBadge}</small></div>`;
        return ch.stream
          ? `<a class="ch-card play" href="watch?ch=${encodeURIComponent(ch.name)}">${body}<span class="play-ic" aria-hidden="true">▶</span></a>`
          : `<div class="ch-card">${body}</div>`;
      }).join("");
    }).catch(() => {
      const grid = $("#ch-grid");
      if (grid) grid.innerHTML = '<p class="dl-sub">Channel preview available when connected to tahoe.kousozo.tech.</p>';
    });
  }
  $("#ch-search")?.addEventListener("input", () => loadChannels());
  $("#ch-refresh")?.addEventListener("click", () => loadChannels());
  loadChannels();

  // ---------------------------------------------------------------- Offline M3U Inspector
  $("#m3u-check-btn")?.addEventListener("click", () => {
    const text = $("#m3u-input")?.value || "";
    const resEl = $("#m3u-result");
    if (!resEl) return;
    let total = 0, hls = 0, dash = 0, drm = 0, curDrm = false;
    for (const raw of text.split(/\r?\n/)) {
      const l = raw.trim();
      if (!l) continue;
      if (l.startsWith("#KODIPROP:") && /license_/i.test(l)) curDrm = true;
      else if (!l.startsWith("#") && /^https?:\/\//i.test(l)) {
        total++;
        if (/\.mpd(\?|$)/i.test(l)) dash++; else hls++;
        if (curDrm) drm++;
        curDrm = false;
      }
    }
    resEl.textContent = total ? `Found ${total} stream(s): ${hls} HLS, ${dash} DASH, ${drm} DRM-tagged.` : "Paste at least one http(s):// stream URL + #EXTINF line.";
  });

  // GitHub stars & contributors
  const gh = p => fetch("https://api.github.com/repos/" + REPO + p, { headers: { Accept: "application/vnd.github+json" } }).then(r => r.ok ? r.json() : Promise.reject(r.status));
  gh("").then(r => { $("#stars-n").textContent = (r.stargazers_count >= 1000 ? (r.stargazers_count / 1000).toFixed(1) + "k" : r.stargazers_count); }).catch(() => {});
  gh("/contributors?per_page=60").then(list => {
    const people = list.filter(p => p.type === "User" && !/\[bot\]$|^ImgBotApp$/i.test(p.login));
    if (!people.length) return;
    const box = $("#contributors"); box.textContent = "";
    people.forEach(p => {
      const a = document.createElement("a"); a.className = "person"; a.href = p.html_url; a.title = p.login + " · " + p.contributions;
      const img = document.createElement("img"); img.src = p.avatar_url + "&s=128"; img.alt = ""; img.loading = "lazy";
      const span = document.createElement("span"); span.textContent = p.login;
      a.append(img, span); box.appendChild(a);
    });
  }).catch(() => {});

  apply(pick());
})();
