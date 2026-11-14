(function () {
  const REPO = "fk0u/cleanyourtv-tahoe";
  const BLOB = "https://github.com/" + REPO + "/blob/main/";
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const store = { get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
                  set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} } };

  // ---------------------------------------------------------------- language
  const english = {};
  $$("[data-i18n]").forEach(el => { english[el.dataset.i18n] = el.textContent; });
  const OS = (() => { const u = navigator.userAgent; const p = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || "";
    if (/Android/i.test(u)) return "android"; if (/Win/i.test(p)) return "windows"; if (/Mac/i.test(p) && !/iPhone|iPad/.test(u) && !(navigator.maxTouchPoints > 1)) return "mac"; /* iPads in desktop mode report Mac */ return ""; })();
  const OS_LABEL = { android: "· Android", windows: "· Windows", mac: "· macOS" };

  function normLang(l) {
    if (!l) return "";
    return l.toLowerCase().split(/[-_]/)[0];
  }

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
    $$("[data-i18n]").forEach(el => { const k = el.dataset.i18n; el.textContent = t[k] || english[k]; });
    $$(".lang button").forEach(b => { b.classList.toggle("on", b.dataset.lang === lang); b.setAttribute("aria-pressed", String(b.dataset.lang === lang)); });
    $("#install-link").href = BLOB + (t._docs || "docs/INSTALL.md");
    $("#contrib-link").href = BLOB + (t._contrib || "CONTRIBUTING.md");
    document.title = t._title || "Tahoe TV — a free Android TV home screen with live TV";
    $$("button", dots).forEach((d, k) => d.setAttribute("aria-label", (t["dot.label"] || "Screenshot ") + (k + 1)));
    caption();
  }
  $$(".lang button").forEach(b => b.addEventListener("click", () => { store.set("lang", b.dataset.lang); apply(b.dataset.lang); }));

  // ---------------------------------------------------------------- feature tabs
  const tabBtns = $$(".tabs button");
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

  // ---------------------------------------------------------------- TV slideshow
  const slides = $$(".tv-screen .slide");
  let at = 0, timer = null, userStopped = false, hoverPaused = false, focusPaused = false;
  const dots = $("#dots");
  slides.forEach((_, i) => { const d = document.createElement("button"); d.setAttribute("aria-label", "Screenshot " + (i + 1)); d.onclick = () => show(i, true); dots.appendChild(d); });
  function caption() { const s = slides[at]; if (!s) return; const k = s.dataset.cap; $("#caption").textContent = (window.I18N[current] || {})[k] || ({
    "cap.home": "Home", "cap.hero": "Hero + actions", "cap.launchpad": "Launchpad", "cap.cc": "Control Center", "cap.assistant": "Gemini assistant",
    "cap.prayer": "Prayer times & adhan", "cap.pair": "Connect a phone (QR + code)", "cap.profiles": "Who's watching?" })[k] || ""; }
  function show(i, user) {
    slides[at].classList.remove("on"); at = (i + slides.length) % slides.length; slides[at].classList.add("on");
    $$("button", dots).forEach((d, k) => {
      const on = k === at;
      d.classList.toggle("on", on);
      if (on) d.setAttribute("aria-current", "true");
      else d.removeAttribute("aria-current");
    });
    caption();
    if (user) { userStopped = true; clearInterval(timer); timer = null; } // the viewer picked one: stop advancing
  }
  const still = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  function syncSlideTimer() {
    clearInterval(timer); timer = null;
    if (!still && !userStopped && !hoverPaused && !focusPaused) timer = setInterval(() => show(at + 1), 4500);
  }
  [$(".tv-frame"), dots].filter(Boolean).forEach(el => {
    el.addEventListener("mouseenter", () => { hoverPaused = true; syncSlideTimer(); });
    el.addEventListener("mouseleave", () => { hoverPaused = false; syncSlideTimer(); });
    el.addEventListener("focusin", () => { focusPaused = true; syncSlideTimer(); });
    el.addEventListener("focusout", () => { focusPaused = false; syncSlideTimer(); });
  });
  show(0); syncSlideTimer();

  // ---------------------------------------------------------------- downloads for this computer
  if (OS) {
    $("#cta-os").textContent = OS_LABEL[OS];
    $$(".dl[data-os='" + OS + "']").forEach(c => c.classList.add("mine"));
  }

  // ---------------------------------------------------------------- reveal on scroll, card light
  const io = "IntersectionObserver" in window ? new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { threshold: .12 }) : null;
  $$(".reveal").forEach((el, i) => { el.style.transitionDelay = (i % 4) * 70 + "ms"; io ? io.observe(el) : el.classList.add("in"); });
  $$(".card").forEach(c => c.addEventListener("pointermove", e => { const r = c.getBoundingClientRect(); c.style.setProperty("--mx", (e.clientX - r.left) + "px"); c.style.setProperty("--my", (e.clientY - r.top) + "px"); }));

  // ---------------------------------------------------------------- live data from GitHub (optional)
  const gh = p => fetch("https://api.github.com/repos/" + REPO + p, { headers: { Accept: "application/vnd.github+json" } }).then(r => r.ok ? r.json() : Promise.reject(r.status));
  gh("").then(r => { $("#stars").textContent = "★ " + (r.stargazers_count >= 1000 ? (r.stargazers_count / 1000).toFixed(1) + "k" : r.stargazers_count); }).catch(() => {});
  gh("/releases/latest").then(r => { $("#version").textContent = r.tag_name; }).catch(() => {});
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
