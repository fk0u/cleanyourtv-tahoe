/* Tahoe TV for webOS: state, menu, profiles, timers and the remote. ES5. */
(function () {
  'use strict';
  var Resume = window.TahoeResume, U = window.TahoeUi, D = window.TahoeData, E = window.TahoeEpg, Text = window.TahoeText, Setup = window.TahoeSetup, Screens = window.TahoeScreens, Player = window.TahoePlayer, Update = window.TahoeUpdate, Preview = window.TahoePreview;
  var KEY = U.KEY, $ = U.$, el = U.el, add = U.add, clear = U.clear, store = U.store;

  var DEFAULTS = { lang: null, name: '', city: '', lat: null, lon: null, country: '', countries: ['ID'], cats: [], prayer: true, playlist: '', sort: 'popularity', askProfile: false, pin: null, kidsMinutes: 0, setupDone: false, preview: true, autoUpdate: true, aspect: 'fit' };
  var all = [], allFilms = [];
  var modals = [], screen = null, screenId = 'home', zone = 'page', menuAt = 0, sleepAt = 0, sleepMinutes = 0, bonus = 0;

  var app = { cfg: null, S: null, profile: 'owner', live: [], films: [], guide: {}, weather: null, prayer: null, loading: true, guideLoading: false, version: '' };

  function now() { return new Date().getTime(); }
  function pkey(k) { return 'p.' + app.profile + '.' + k; }
  function kids() { return app.profile === 'kids'; }

  // ---------------------------------------------------------------- data

  function derive() {
    var list = D.sorted(all, app.cfg);
    app.live = kids() ? list.filter(function (e) { return (e.cat === 'kids' || e.cat === 'documentary') && !/crime/i.test(e.name); }) : list;
    app.films = kids() ? [] : allFilms;
  }
  app.resort = function () { derive(); app.redraw(); };
  app.reload = function () {
    app.loading = true; app.redraw();
    D.channels(app.cfg, function (live, films, done) {
      if (done) app.loading = false;
      if (live) { all = live; allFilms = films; derive(); }
      app.redraw();
      if (done && all.length) loadGuide();
    });
  };
  function loadGuide() {
    app.guideLoading = true;
    D.guide(app.cfg, all, function (guide, done) { app.guide = guide; if (done) app.guideLoading = false; app.redraw(); });
  }
  app.ambient = function () {
    D.weather(app.cfg, function (w) { app.weather = w; app.redraw(); });
    D.prayer(app.cfg, function (p) { app.prayer = p; app.redraw(); });
  };
  app.saveCfg = function (reload) {
    store.set('cfg', app.cfg);
    if (reload) { store.set('cache', null); store.set('guide', null); all = []; allFilms = []; app.guide = {}; derive(); app.reload(); }
  };

  // ---------------------------------------------------------------- favourites, history, resume

  app.isFav = function (key) { return !!store.get(pkey('favs'), {})[key]; };
  app.toggleFav = function (key) {
    var f = store.get(pkey('favs'), {});
    if (f[key]) delete f[key]; else f[key] = 1;
    store.set(pkey('favs'), f); app.toast(f[key] ? app.S.added : app.S.removed);
    if (!Player.isOpen()) app.redraw();
  };
  app.watched = function (key) {
    var r = store.get(pkey('recent'), []).filter(function (k) { return k !== key; });
    r.unshift(key); store.set(pkey('recent'), r.slice(0, 20));
    remember({ kind: 'live', key: key });
  };
  /** Watch history, newest first: { kind: 'live' | 'film', key, item, at }. One line per channel or title. */
  function remember(h) {
    h.at = now();
    var list = store.get(pkey('history'), []).filter(function (x) { return !(x.kind === h.kind && x.key === h.key); });
    list.unshift(h); store.set(pkey('history'), list.slice(0, 100));
  }
  app.history = function () {
    var live = {}, films = {};
    app.live.forEach(function (e, i) { live['$' + e.key] = i; });
    app.films.forEach(function (x) { films['$' + x.key] = x; });
    return store.get(pkey('history'), []).map(function (h) {
      if (h.kind === 'live' && live['$' + h.key] !== undefined) return { h: h, entry: app.live[live['$' + h.key]], index: live['$' + h.key] };
      if (h.kind === 'film' && films['$' + h.key]) return { h: h, title: films['$' + h.key] };
      return null;
    }).filter(Boolean);
  };
  app.forget = function (h) { store.set(pkey('history'), store.get(pkey('history'), []).filter(function (x) { return !(x.kind === h.kind && x.key === h.key); })); app.redraw(); };
  app.recents = function () {
    var byKey = {};
    app.live.forEach(function (e) { byKey['$' + e.key] = e; });
    return store.get(pkey('recent'), []).map(function (k) { return byKey['$' + k]; }).filter(Boolean);
  };
  app.progress = function (entry, t, duration) { store.set(pkey('resume'), Resume.remember(store.get(pkey('resume'), {}), entry.streams[0], t, duration, now())); };
  app.resumeAt = function (entry) { return entry.fromStart ? 0 : Resume.seek(store.get(pkey('resume'), {})[entry.streams[0]]); };
  var done_ = Resume.done;
  /** 0..1 for the progress bar: a film's own position, or the share of a series already watched. */
  app.titleProgress = function (x) {
    var r = store.get(pkey('resume'), {});
    if (!x.series) { var one = r[x.items[0].url]; return one ? Math.min(1, one.t / one.d) : 0; }
    return x.items.filter(function (i) { return done_(r[i.url]); }).length / x.items.length;
  };
  app.continueFilms = function () {
    var r = store.get(pkey('resume'), {});
    return app.films.filter(function (x) { var p = app.titleProgress(x); return x.series ? x.items.some(function (i) { return r[i.url]; }) && p < 1 : p > 0 && p <= 0.92; });
  };
  app.clearHistory = function () { store.set(pkey('recent'), []); store.set(pkey('resume'), {}); store.set(pkey('history'), []); store.set('last', null); app.redraw(); };

  // ---------------------------------------------------------------- watching

  app.watch = function (entries, index) { if (blocked()) return; Preview.clear(); Player.start(entries[index], entries, index); };
  app.preview = function (entry) { if (!Player.isOpen() && !modals.length) Preview.show(entry); };
  app.poster = function (url) { if (!Player.isOpen()) Preview.poster(url || ''); };
  function filmEntries(title) {
    var r = store.get(pkey('resume'), {});
    return title.items.map(function (it, k) {
      var label = it.episode ? (it.season ? 'S' + it.season + ' ' : '') + 'E' + it.episode : String(k + 1), saved = r[it.url];
      return { key: title.key + ':' + k, name: title.title + (title.items.length > 1 ? ' · ' + label : ''), label: title.items.length > 1 && it.episode ? label : it.name, cat: null, streams: [it.url], vod: true, resume: Resume.seek(saved), saved: saved, poster: store.get('posters', {})[title.key] || null };
    });
  }
  app.openTitle = function (title) {
    var entries = filmEntries(title);
    function play(i, fromStart) { if (blocked()) return; var list = filmEntries(title); list[i].fromStart = !!fromStart; remember({ kind: 'film', key: title.key, item: i }); Preview.clear(); Player.start(list[i], list, i); }
    if (entries.length > 1) {
      var d = Setup.dialog(app, title.title, function (body, nav) {
        entries.forEach(function (e, i) {
          Setup.option(nav, body, i, e.label, done_(e.saved) ? '✓ ' + app.S.watched : e.resume > 30 ? U.span(e.resume) : '', function () { app.close(d); play(i); });
        });
      });
      for (var i = 0; i < entries.length; i++) if (!done_(entries[i].saved)) { d.nav.r = i; break; }
      return app.modal(d);
    }
    if (entries[0].resume > 30) return Setup.ask(app, title.title, '', [[Text.fill(app.S.resume, U.span(entries[0].resume)), function () { play(0); }], [app.S.restart, function () { play(0, true); }]]);
    play(0);
  };
  app.stopped = function (entry) {
    zone = 'page';
    app.redraw();
  };

  // ---------------------------------------------------------------- sleep timer, reminders, kids' time

  app.sleepLabel = function () { return sleepMinutes ? Text.fill(app.S.minutes, Math.max(1, Math.ceil((sleepAt - now()) / 60000))) : app.S.off; };
  app.cycleSleep = function () {
    var steps = [0, 15, 30, 45, 60, 90];
    sleepMinutes = steps[(steps.indexOf(sleepMinutes) + 1) % steps.length]; sleepAt = sleepMinutes ? now() + sleepMinutes * 60000 : 0;
  };
  function reminders() { return store.get('reminders', []); }
  app.isReminded = function (key, start) { return reminders().some(function (r) { return r.key === key && r.start === start; }); };
  app.toggleReminder = function (entry, p) {
    var on = app.isReminded(entry.key, p[0]), list = reminders().filter(function (r) { return !(r.key === entry.key && r.start === p[0]); });
    if (!on) list.push({ key: entry.key, name: entry.name, start: p[0], title: p[2] });
    store.set('reminders', list); app.toast(on ? app.S.unreminded : app.S.reminded);
  };
  function checkReminders() {
    var t = now(), list = reminders(), due = null, index = -1;
    var keep = list.filter(function (r) {
      if (t - r.start >= 5 * 60000) return false; // missed
      if (due || r.start - t > 30000) return true;
      for (var i = 0; i < app.live.length; i++) if (app.live[i].key === r.key) { due = r; index = i; return false; }
      return true; // not on this profile's list: kept for one that has it
    });
    if (keep.length !== list.length) store.set('reminders', keep);
    if (!due) return;
    Setup.ask(app, app.S.startsNow, due.title + ' — ' + due.name, [[app.S.watch, function () { app.watch(app.live, index); }], [app.S.later]]);
  }
  function usage() { var u = store.get('kidsUsage', null), day = new Date().toDateString(); return u && u.day === day ? u : { day: day, seconds: 0, bonus: 0 }; }
  function blocked() {
    if (!kids() || !app.cfg.kidsMinutes) return false;
    var u = usage();
    if (u.seconds < (app.cfg.kidsMinutes + u.bonus) * 60) return false;
    Setup.ask(app, app.S.timeUp, '', [[app.S.addTime, function () { app.withPin(function () { var v = usage(); v.bonus += 30; store.set('kidsUsage', v); }); }], [app.S.switchProfile, function () { app.switchProfile(); }]]);
    return true;
  }

  // ---------------------------------------------------------------- profiles & PIN

  app.profileName = function () { return app.profile === 'owner' ? app.cfg.name : app.profile === 'kids' ? app.S.kidsProfile : app.S.guest; };
  app.withPin = function (then) {
    if (!app.cfg.pin) return Setup.pin(app, app.S.pinNew, function (v) { app.cfg.pin = v; app.saveCfg(); app.toast(app.S.pinSaved); then(); });
    Setup.pin(app, app.S.pin, function (v) { if (v === app.cfg.pin) then(); else app.toast(app.S.pinWrong); });
  };
  app.switchProfile = function () { Setup.profiles(app, true); };
  app.enterProfile = function (id, dialog) {
    function go() {
      app.close(dialog); app.profile = id; store.set('profile', id);
      if (id === 'guest') { store.set('p.guest.favs', {}); store.set('p.guest.recent', []); store.set('p.guest.resume', {}); }
      derive(); menuAt = 0; open('home');
    }
    // the kids' profile needs a PIN to leave it, so make one on the way in
    if ((id === 'kids' && !app.cfg.pin) || (kids() && id !== 'kids')) return app.withPin(go);
    go();
  };

  // ---------------------------------------------------------------- updates

  var updating = false;
  /** Looks for a newer release. Manual: always answers. Otherwise once in a while, never over the player or in the Kids profile;
   *  with automatic updates on, it downloads quietly and the new version starts the next time Tahoe TV opens. */
  app.checkUpdate = function (manual) {
    if (updating) return manual && app.toast(app.S.updating);
    Update.check(function (error, info) {
      if (error) { if (manual) app.toast(app.S.checkFailed); return; }
      if (!info) { if (manual) app.toast(Text.fill(app.S.upToDate, app.version)); return; }
      var ready = Update.waiting();
      if (ready === info.version) { if (manual) askRestart(info.version); return; }
      if (info.needsPackage) {
        if (manual || !snoozed(info.version)) Setup.ask(app, Text.fill(app.S.updateTitle, info.version), app.S.updatePackage, [[app.S.later, function () { snooze(info.version); }]]);
        return;
      }
      if (!manual && (kids() || Update.bad() === info.version)) return;
      if (!manual && app.cfg.autoUpdate) return fetch(info, false);
      if (!manual && (Player.isOpen() || modals.length || snoozed(info.version))) return;
      var notes = info.notes ? info.notes.slice(0, 520) + (info.notes.length > 520 ? '…' : '') : Text.fill(app.S.updateBody, app.version);
      Setup.ask(app, Text.fill(app.S.updateTitle, info.version), notes, [[app.S.updateNow, function () { fetch(info, true); }], [app.S.later, function () { snooze(info.version); }]]);
    });
  };
  function snooze(v) { store.set('updateLater', { version: v, at: now() }); }
  function snoozed(v) { var l = store.get('updateLater', null); return l && l.version === v && now() - l.at < 86400000; }
  function fetch(info, loud) {
    updating = true;
    Update.download(info, function (n, total) { if (loud) app.toast(Text.fill(app.S.downloading, n, total)); }, function (error) {
      updating = false;
      if (error) { if (loud) app.toast(Text.fill(app.S.updateFailed, error)); return; }
      if (loud) askRestart(info.version); else app.notify(Text.fill(app.S.updateReady, info.version), app.S.updateReadyBody);
    });
  }
  function askRestart(v) {
    Setup.ask(app, Text.fill(app.S.updateReady, v), app.S.updateReadyBody, [[app.S.restartNow, function () { if (Player.isOpen()) Player.stop(); Update.restart(); }], [app.S.later]]);
  }
  app.updateSource = function () { return Update.source() === 'ota' ? app.S.fromUpdate : app.S.fromPackage; };
  app.rollback = function () { Setup.ask(app, app.S.setRollback, Text.fill(app.S.rollbackBody, window.TahoeBoot ? window.TahoeBoot.bundled : ''), [[app.S.setRollback, function () { Update.reset(); }], [app.S.cancel]]); };

  // ---------------------------------------------------------------- weather and prayer times

  app.weatherDialog = function () {
    if (app.cfg.lat === null) return app.toast(app.S.setCityFirst);
    app.toast(app.S.searching);
    D.forecast(app.cfg, function (f) {
      if (!f) return app.toast(app.S.checkFailed);
      var d = Setup.dialog(app, Text.fill(app.S.weatherTitle, app.cfg.city), function (body, nav) {
        if (f.now) body.appendChild(add(el('div', 'weatherNow'), el('span', 'temp', f.now.temp + '°'), add(el('div', 'two'), el('span', 'title', app.S.wx[D.sky(f.now.code)]), el('span', 'sub', Text.fill(app.S.humidityWind, f.now.humidity, f.now.wind)))));
        var days = el('div', 'days');
        f.days.forEach(function (x, i) {
          var name = i === 0 ? app.S.today : new Date(x.day).toLocaleDateString(app.cfg.lang, { weekday: 'long' });
          name = name.charAt(0).toUpperCase() + name.slice(1);
          var card = add(el('div', 'day'), el('div', 'name', name), el('div', 'sky', app.S.wx[D.sky(x.code)]), el('div', 'range', x.max + '° / ' + x.min + '°'));
          days.appendChild(card); nav.add(0, card, function () { app.close(d); });
        });
        body.appendChild(days);
      }, { wide: true });
      app.modal(d);
    });
  };
  app.prayerDialog = function () {
    if (!app.prayer) return app.toast(app.cfg.lat === null ? app.S.setCityFirst : app.S.checkFailed);
    var d = Setup.dialog(app, app.S.prayerTitle + (app.cfg.city ? ' · ' + app.cfg.city : ''), function (body, nav) {
      var t = new Date(), hm = U.pad(t.getHours()) + ':' + U.pad(t.getMinutes()), next = null;
      app.prayer.forEach(function (p) { if (!next && p[1] > hm) next = p[0]; });
      app.prayer.forEach(function (p, i) { Setup.option(nav, body, i, app.S.prayer[p[0]], p[1].replace(':', '.') + (p[0] === next ? '  ●' : ''), function () { app.close(d); }); });
    });
    app.modal(d);
  };
  function prayerCall() {
    if (!app.cfg.prayer || !app.prayer) return;
    var t = new Date(), hm = U.pad(t.getHours()) + ':' + U.pad(t.getMinutes()), day = t.toDateString();
    app.prayer.forEach(function (p) {
      var id = day + p[0];
      if (p[1] !== hm || store.get('prayerCalled', '') === id) return;
      store.set('prayerCalled', id);
      app.notify(Text.fill(app.S.prayerNow, app.S.prayer[p[0]]), p[1].replace(':', '.') + (app.cfg.city ? ' · ' + app.cfg.city : ''));
    });
  }

  /** A banner over everything (also over the player) for a few seconds. */
  app.notify = function (title, body) {
    $('noticeTitle').textContent = title; $('noticeBody').textContent = body || '';
    $('notice').className = 'show'; clearTimeout(app.notify.h);
    app.notify.h = setTimeout(function () { $('notice').className = ''; }, 9000);
  };

  // ---------------------------------------------------------------- menu, pages, dialogs

  function menu() { return kids() ? ['home', 'live', 'guide', 'history', 'search', 'profile'] : ['home', 'live', 'guide', 'film', 'history', 'search', 'settings']; }
  function drawMenu() {
    var box = clear($('menu')), items = menu();
    items.forEach(function (id, i) {
      var label = id === 'profile' ? app.S.switchProfile : app.S[id];
      var d = el('div', 'item' + (id === screenId ? ' on' : '') + (zone === 'menu' && i === menuAt ? ' focus' : ''), label);
      d.onclick = function () { menuAt = i; if (id === 'profile') return app.switchProfile(); zone = 'page'; open(id); };
      box.appendChild(d);
    });
  }
  function open(id, argument) {
    screenId = id;
    Preview.clear();
    var page = clear($('page'));
    page.className = 'page-' + id;
    void page.offsetWidth; // restart the entry animation
    page.className = 'page-' + id + ' enter';
    screen = id === 'settings' ? Setup.settings(app) : Screens[id](app, argument);
    screen.mount(page);
    menu().forEach(function (m, i) { if (m === id) menuAt = i; });
    app.redraw();
  }
  var pending = 0;
  /** Redraws the menu and page once, however many things changed at the same time. */
  app.redraw = function () {
    if (pending) return;
    pending = setTimeout(function () {
      pending = 0;
      if (Player.isOpen() || !screen) return;
      drawMenu(); screen.draw(zone === 'page' && !modals.length);
    }, 0);
  };
  app.go = function (id, argument) { zone = 'page'; open(id, argument); };
  app.toPage = function () { zone = 'page'; app.redraw(); };
  app.relabel = function () { app.S = Text.strings(app.cfg.lang); document.documentElement.lang = app.cfg.lang; open(screenId); };
  app.modal = function (d) { Preview.stop(); modals.push(d); $('modals').appendChild(d.el); d.draw(); app.redraw(); };
  app.close = function (d) {
    var i = modals.indexOf(d);
    if (i < 0) return;
    modals.splice(i, 1); $('modals').removeChild(d.el);
    if (modals.length) modals[modals.length - 1].draw();
    app.redraw();
  };
  app.toast = function (text) { var t = $('toast'); t.textContent = text; t.className = 'show'; clearTimeout(app.toast.h); app.toast.h = setTimeout(function () { t.className = ''; }, 2400); };

  // ---------------------------------------------------------------- remote

  function onKey(ev) {
    var k = ev.keyCode;
    if (k === KEY.ESC || k === KEY.BKSP) k = KEY.BACK;
    if ((k >= 37 && k <= 40) || k === KEY.OK || k === KEY.BACK || k === KEY.CH_UP || k === KEY.CH_DOWN) ev.preventDefault();
    var top = modals[modals.length - 1];
    // typing on a real keyboard, or the remote's digits on the PIN pad
    var typed = ev.key && ev.key.length === 1 ? ev.key : (k >= 48 && k <= 57 ? String(k - 48) : null);
    var target = top || (!Player.isOpen() && screenId === 'search' && zone === 'page' ? screen : null);
    if (target && target.type) {
      if (ev.keyCode === KEY.BKSP) return target.type(null);
      if (typed) return target.type(typed);
    }
    if (top) { if (k === KEY.BACK) return top.back(); return top.key(k); }
    if (Player.isOpen()) return Player.key(k);
    if (!screen) return;
    if (zone === 'menu') {
      var items = menu();
      if (k === KEY.UP || k === KEY.DOWN) { menuAt = Math.max(0, Math.min(items.length - 1, menuAt + (k === KEY.UP ? -1 : 1))); if (items[menuAt] !== 'profile') open(items[menuAt]); else app.redraw(); return; }
      if (k === KEY.RIGHT || k === KEY.OK) { if (items[menuAt] === 'profile') return app.switchProfile(); zone = 'page'; return app.redraw(); }
      if (k === KEY.BACK) {
        if (screenId !== 'home') { menuAt = 0; return open('home'); }
        return Setup.ask(app, app.S.exitAsk, '', [[app.S.exit, function () { window.close(); }], [app.S.cancel]]);
      }
      return;
    }
    if (k === KEY.BACK) { zone = 'menu'; return app.redraw(); }
    if (screen.key(k) === 'left') { zone = 'menu'; app.redraw(); }
  }

  // ---------------------------------------------------------------- start

  function clock() {
    var t = new Date();
    $('sideTime').textContent = U.clock(t.getTime());
    $('sideDate').textContent = t.toLocaleDateString(app.cfg.lang, { weekday: 'long', day: 'numeric', month: 'long' });
    $('sideWeather').textContent = app.weather ? app.weather.temp + '° ' + app.S.wx[D.sky(app.weather.code)] : '';
    if (Player.isOpen()) $('osdClock').textContent = U.clock(t.getTime());
  }

  var ticks = 0;
  function tick() {
    ticks++;
    var t = now();
    if (sleepAt && t >= sleepAt) { sleepAt = 0; sleepMinutes = 0; if (Player.isOpen()) Player.stop(); app.toast(app.S.sleepDone); }
    if (kids() && app.cfg.kidsMinutes && Player.playingPicture()) { var u = usage(); u.seconds += 20; store.set('kidsUsage', u); if (u.seconds >= (app.cfg.kidsMinutes + u.bonus) * 60) { Player.stop(); blocked(); } }
    if (!modals.length) checkReminders();
    prayerCall();
    if (ticks % 3 === 0 && !modals.length && !document.hidden) app.redraw(); // clock, progress bars, what's on
    if (ticks % 90 === 0) app.ambient();
    if (ticks % 540 === 0) { if (all.length) loadGuide(); app.checkUpdate(false); } // every 3 hours
    clock();
  }

  function migrate() { // the first webOS version kept one list of favourites and the playlist on their own
    var favs = store.get('favs', null), playlist = store.get('playlist', null);
    if (favs) { store.set('p.owner.favs', favs); store.set('favs', null); }
    if (playlist) { app.cfg.playlist = playlist; store.set('playlist', null); store.set('cfg', app.cfg); }
  }

  function init() {
    // Outfit is a variable font: old engines (webOS 4 = Chromium 53) would draw it hair-thin, so they keep the TV's font
    if (window.CSS && CSS.supports && CSS.supports('font-variation-settings', 'normal')) document.documentElement.className = 'vf';
    var saved = store.get('cfg', {}), k;
    app.cfg = {};
    for (k in DEFAULTS) app.cfg[k] = saved[k] !== undefined ? saved[k] : DEFAULTS[k];
    if (!app.cfg.lang) app.cfg.lang = Text.pick(navigator.language);
    migrate();
    app.S = Text.strings(app.cfg.lang); document.documentElement.lang = app.cfg.lang;
    app.profile = store.get('profile', 'owner');
    Player.init(app); Preview.init(app);
    document.addEventListener('keydown', onKey);
    var cache = store.get('cache', null), guide = store.get('guide', null);
    if (cache && cache.live) { all = cache.live; allFilms = cache.films || []; }
    if (guide && guide.data) app.guide = guide.data;
    derive();
    open('home');
    if (!app.cfg.setupDone) Setup.onboarding(app);
    else if (app.cfg.askProfile) Setup.profiles(app, false);
    D.version(function (v) {
      app.version = v;
      var seen = store.get('seenVersion', null);
      if (seen && seen !== v) app.notify(Text.fill(app.S.updated, v), app.updateSource());
      store.set('seenVersion', v);
      setTimeout(function () { app.checkUpdate(false); }, 20000);
    });
    app.reload(); app.ambient();
    // like a TV: back on the last channel
    var last = store.get('last', null);
    if (last && app.cfg.setupDone && !app.cfg.askProfile) for (var i = 0; i < app.live.length; i++) if (app.live[i].key === last) { app.watch(app.live, i); break; }
    setInterval(tick, 20000);
    clock(); setInterval(clock, 10000);
    if (window.TahoeBoot) window.TahoeBoot.ready(); // this version started fine
  }

  init();
})();
