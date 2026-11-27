/* Dialogs (choices, PIN, keyboard), Settings, the profile picker and first-time setup. A dialog is
 * { el, key(k), back() } pushed with app.modal(). ES5. */
(function (root) {
  'use strict';
  var U = root.TahoeUi, D = root.TahoeData, Text = root.TahoeText, L = root.TahoeLib;
  var KEY = U.KEY, el = U.el, add = U.add, clear = U.clear;
  var LETTERS = ['abcdefg', 'hijklmn', 'opqrstu', 'vwxyz', '1234567', '890'];

  /** An on-screen keyboard built into [box]; [value] reads the text and [change] writes it. */
  function keyboard(app, box, nav, value, change, url, done) {
    var keepR = nav.r, keepC = nav.c;
    nav.reset(); clear(box);
    var rows = LETTERS.map(function (r) { return r.split(''); });
    if (url) rows.push([':', '/', '.', '-', '_', '?', '='], ['&', '%', '@', '+', '~', '#', 'https://']);
    rows.forEach(function (chars, r) {
      var line = el('div', 'keys');
      chars.forEach(function (c) { var k = el('div', 'key', c); line.appendChild(k); nav.add(r, k, function () { change(value() + c); }); });
      box.appendChild(line);
    });
    var actions = [[app.S.keyboardSpace, function () { change(value() + ' '); }], [app.S.keyboardDelete, function () { change(value().slice(0, -1)); }], [app.S.clear, function () { change(''); }]];
    if (done) actions.push([app.S.keyboardDone, done]);
    var line = el('div', 'keys');
    actions.forEach(function (a) { var k = el('div', 'key long', a[0]); line.appendChild(k); nav.add(rows.length, k, a[1]); });
    box.appendChild(line);
    nav.r = keepR; nav.c = keepC;
  }

  /** A titled panel with rows of focusable things. [build](page, nav) fills it and may be called again to redraw. */
  function dialog(app, title, build, opts) {
    opts = opts || {};
    var wrap = el('div', 'dialog' + (opts.full ? ' full' : '') + (opts.wide ? ' wide' : '')), panel = el('div', 'panel'), body = el('div', 'scroll'), head = el('h2', null, title), nav = new U.Nav(body, opts.full ? 760 : 700);
    add(wrap, add(panel, head, add(el('div', 'viewport'), body)));
    var d = {
      el: wrap, nav: nav,
      title: function (t) { head.textContent = t; },
      draw: function () { var r = nav.r, c = nav.c; nav.reset(); clear(body); build(body, nav, d); nav.r = r; nav.c = c; nav.focus(true); },
      key: function (k) { nav.key(k); },
      back: opts.back || function () { app.close(d); },
      type: opts.type
    };
    return d;
  }
  function option(nav, body, row, label, value, ok, cls) {
    var d = add(el('div', cls || 'item'), el('span', 'title', label), value !== undefined && value !== null ? el('span', 'value', value) : null);
    body.appendChild(d); nav.add(row, d, ok);
    return d;
  }

  /** Buttons under a message. */
  function ask(app, title, message, buttons) {
    var d = dialog(app, title, function (body, nav, me) {
      if (message) body.appendChild(el('p', null, message));
      var line = el('div', 'buttons');
      buttons.forEach(function (b, i) {
        var n = el('div', 'button' + (i === 0 ? ' primary' : ''), b[0]);
        line.appendChild(n); nav.add(0, n, function () { app.close(me); if (b[1]) b[1](); });
      });
      body.appendChild(line);
    });
    app.modal(d);
    return d;
  }

  /** One choice from a list; with [many], toggles until Done. */
  function choose(app, title, options, current, done, many) {
    var picked = many ? current.slice() : current;
    var d = dialog(app, title, function (body, nav, me) {
      options.forEach(function (o, i) {
        var on = many ? picked.indexOf(o[0]) >= 0 : picked === o[0];
        option(nav, body, i, o[1], on ? '●' : '', function () {
          if (!many) { app.close(me); return done(o[0]); }
          var at = picked.indexOf(o[0]);
          if (at >= 0) picked.splice(at, 1); else picked.push(o[0]);
          me.draw();
        });
      });
      if (many) option(nav, body, options.length, app.S.done, null, function () { app.close(me); done(picked); }, 'item action');
    });
    options.forEach(function (o, i) { if (!many && o[0] === current) d.nav.r = i; });
    app.modal(d);
  }

  /** Four digits, with the remote's number keys or the pad. */
  function pin(app, title, done) {
    var typed = '';
    function press(c) { if (c === null) typed = typed.slice(0, -1); else if (!/^\d$/.test(c)) return; else if (typed.length < 4) typed += c; d.draw(); if (typed.length === 4) { app.close(d); done(typed); } }
    var d = dialog(app, title, function (body, nav) {
      body.appendChild(el('div', 'dots', [0, 1, 2, 3].map(function (i) { return i < typed.length ? '●' : '○'; }).join(' ')));
      ['123', '456', '789'].forEach(function (r, i) {
        var line = el('div', 'keys center');
        r.split('').forEach(function (c) { var k = el('div', 'key', c); line.appendChild(k); nav.add(i, k, function () { press(c); }); });
        body.appendChild(line);
      });
      var line = el('div', 'keys center'), zero = el('div', 'key', '0'), del = el('div', 'key long', app.S.keyboardDelete);
      add(line, zero, del); nav.add(3, zero, function () { press('0'); }); nav.add(3, del, function () { press(null); });
      body.appendChild(line);
    }, { type: press });
    app.modal(d);
  }

  /** Text entry with the on-screen keyboard. */
  function text(app, title, initial, url, done) {
    var value = initial || '';
    function change(v) { value = v; d.draw(); }
    var d = dialog(app, title, function (body, nav, me) {
      body.appendChild(el('div', 'field', value || ' '));
      var box = el('div', 'keyboard'), inner = new U.Nav(null);
      keyboard(app, box, inner, function () { return value; }, change, url, function () { app.close(me); done(value.trim()); });
      body.appendChild(box);
      inner.rows.forEach(function (row, r) { row.forEach(function (i) { nav.add(r, i.el, i.ok); }); });
    }, { type: function (c) { change(c === null ? value.slice(0, -1) : value + c); } });
    app.modal(d);
  }

  /** City search: type, pick one of the results. */
  function city(app, done) {
    text(app, app.S.obCity, app.cfg.city, false, function (q) {
      if (!q) return done(null);
      app.toast(app.S.searching);
      D.cities(q, app.cfg.lang, function (found) {
        if (!found.length) { app.toast(app.S.notFound); return city(app, done); }
        choose(app, app.S.obCity, found.map(function (c, i) { return [i, c.name + (c.region ? ' — ' + c.region : '')]; }), -1, function (i) { done(found[i]); });
      });
    });
  }

  function countryOptions() { return Object.keys(D.COUNTRIES).map(function (c) { return [c, D.COUNTRIES[c]]; }); }
  function catOptions(app) { return L.CATS.filter(function (c) { return c !== 'national' && c !== 'other'; }).map(function (c) { return [c, app.S.cats[c]]; }); }
  function setCity(app, c) { if (!c) return; app.cfg.city = c.name; app.cfg.lat = c.lat; app.cfg.lon = c.lon; app.cfg.country = c.country || ''; }

  // ---------------------------------------------------------------- settings page

  function settings(app) {
    var head, body = el('div', 'scroll'), nav = new U.Nav(body, 900);
    nav.onPointer = app.toPage;
    function changed(reload) { app.saveCfg(reload); app.redraw(); }
    function onoff(v) { return v ? app.S.on : app.S.off; }
    function rows() {
      var S = app.S, c = app.cfg;
      return [
        [S.setProfile, c.name || S.unset, function () { text(app, S.setProfile, c.name, false, function (v) { c.name = v; changed(); }); }],
        [S.switchProfile, app.profileName() || S.owner, function () { app.switchProfile(); }],
        [S.setLang, Text.NAMES[c.lang], function () { choose(app, S.setLang, Text.LANGS.map(function (l) { return [l, Text.NAMES[l]]; }), c.lang, function (l) { c.lang = l; app.saveCfg(); app.relabel(); }); }],
        [S.setCity, c.city || S.unset, function () { city(app, function (found) { setCity(app, found); changed(); app.ambient(); }); }],
        [S.setPrayer, onoff(c.prayer), function () { c.prayer = !c.prayer; changed(); app.ambient(); }],
        [S.setCountries, c.countries.join(', '), function () { choose(app, S.setCountries, countryOptions(), c.countries, function (v) { c.countries = v.length ? v : ['ID']; changed(true); }, true); }],
        [S.setCats, c.cats.map(function (x) { return S.cats[x]; }).join(', ') || S.unset, function () { choose(app, S.setCats, catOptions(app), c.cats, function (v) { c.cats = v; changed(); app.resort(); }, true); }],
        [S.setSort, S[c.sort === 'asc' ? 'sortAsc' : c.sort === 'desc' ? 'sortDesc' : 'sortPopularity'], function () { c.sort = c.sort === 'popularity' ? 'asc' : c.sort === 'asc' ? 'desc' : 'popularity'; changed(); app.resort(); }],
        [S.setPlaylist, c.playlist || S.builtIn, function () { text(app, S.setPlaylist, c.playlist, true, function (v) { if (v && !/^https?:\/\//i.test(v)) return app.toast(S.badUrl); c.playlist = v; changed(true); }); }],
        [S.setReload, null, function () { app.toast(S.reloading); app.reload(); }],
        [S.setAsk, onoff(c.askProfile), function () { c.askProfile = !c.askProfile; changed(); }],
        [S.setPreview, onoff(c.preview), function () { c.preview = !c.preview; if (!c.preview) window.TahoePreview.clear(); changed(); }],
        [S.setPin, c.pin ? '••••' : S.unset, function () { var set = function () { pin(app, S.pinNew, function (v) { c.pin = v; changed(); app.toast(S.pinSaved); }); }; if (c.pin) pin(app, S.pin, function (v) { if (v === c.pin) set(); else app.toast(S.pinWrong); }); else set(); }],
        [S.setKids, c.kidsMinutes ? Text.fill(S.minutes, c.kidsMinutes) : S.off, function () { app.withPin(function () { choose(app, S.setKids, [0, 30, 60, 90, 120, 180].map(function (m) { return [m, m ? Text.fill(S.minutes, m) : S.off]; }), c.kidsMinutes, function (m) { c.kidsMinutes = m; changed(); }); }); }],
        [S.setHistory, null, function () { app.clearHistory(); app.toast(S.cleared); }],
        [S.setUpdate, app.version + ' · ' + app.updateSource(), function () { app.checkUpdate(true); }],
        [S.setAuto, onoff(c.autoUpdate), function () { c.autoUpdate = !c.autoUpdate; changed(); }]
      ].concat(window.TahoeBoot && window.TahoeBoot.source === 'ota' ? [[S.setRollback, window.TahoeBoot.bundled, function () { app.rollback(); }]] : []).concat([
        [S.setSetup, null, function () { onboarding(app); }],
        [S.setAbout, null, function () { ask(app, 'Tahoe TV', Text.fill(S.about, app.version), [[S.done]]); }]
      ]);
    }
    return {
      mount: function (page) { head = add(el('div', 'head'), el('h1', null, app.S.settings)); add(page, head, add(el('div', 'viewport'), body)); },
      draw: function (show) {
        var r = nav.r; nav.reset(); clear(body);
        rows().forEach(function (x, i) { option(nav, body, i, x[0], x[1], x[2]); });
        nav.r = r; nav.focus(show);
      },
      key: function (k) { var r = nav.key(k); return r === 'up' ? true : r; }
    };
  }

  // ---------------------------------------------------------------- profiles

  function profiles(app, cancelable) {
    var d = dialog(app, app.S.who, function (body, nav, me) {
      var line = el('div', 'profiles');
      [['owner', app.cfg.name || app.S.owner, ''], ['kids', app.S.kidsProfile, app.S.kidsNote], ['guest', app.S.guest, '']].forEach(function (p) {
        var card = add(el('div', 'profile ' + p[0]), el('div', 'face', U.monogram(p[1])), el('div', 'name', p[1]), el('div', 'sub', p[2]));
        line.appendChild(card);
        nav.add(0, card, function () { app.enterProfile(p[0], me); });
      });
      body.appendChild(line);
    }, { full: true, back: function () { if (cancelable) app.close(d); } });
    app.modal(d);
  }

  // ---------------------------------------------------------------- first-time setup

  function onboarding(app) {
    var step = 0, c = app.cfg;
    var steps = ['lang', 'name', 'city', 'countries', 'cats', 'prayer', 'ready'];
    function next() { if (step + 1 >= steps.length) return finish(); step++; d.draw(); d.nav.r = 0; d.nav.c = 0; d.nav.focus(true); }
    function finish() { c.setupDone = true; app.close(d); app.saveCfg(true); app.relabel(); app.ambient(); }
    var d = dialog(app, '', function (body, nav, me) {
      var S = app.S, s = steps[step], r = 0;
      me.title({ lang: S.obLang, name: S.obName, city: S.obCity, countries: S.obCountries, cats: S.obCats, prayer: S.obPrayer, ready: S.obReady }[s] + '   ' + (step + 1) + '/' + steps.length);
      function row(label, value, ok, cls) { option(nav, body, r++, label, value, ok, cls); }
      if (s === 'lang') Text.LANGS.forEach(function (l) { row(Text.NAMES[l], c.lang === l ? '●' : '', function () { c.lang = l; app.S = Text.strings(l); next(); }); });
      if (s === 'name') { row(S.setProfile, c.name || S.unset, function () { text(app, S.obName, c.name, false, function (v) { c.name = v; next(); }); }); row(c.name ? S.continue_ : S.skip, null, next, 'item action'); }
      if (s === 'city') { row(S.setCity, c.city || S.unset, function () { city(app, function (found) { setCity(app, found); next(); }); }); row(c.city ? S.continue_ : S.skip, null, next, 'item action'); }
      if (s === 'countries' || s === 'cats') {
        var list = s === 'countries' ? countryOptions() : catOptions(app), chosen = s === 'countries' ? c.countries : c.cats;
        list.forEach(function (o) { row(o[1], chosen.indexOf(o[0]) >= 0 ? '●' : '', function () { var at = chosen.indexOf(o[0]); if (at >= 0) chosen.splice(at, 1); else chosen.push(o[0]); me.draw(); }); });
        row(S.continue_, null, function () { if (s === 'countries' && !chosen.length) chosen.push('ID'); next(); }, 'item action');
      }
      if (s === 'prayer') [[true, S.yes], [false, S.no]].forEach(function (o) { row(o[1], c.prayer === o[0] ? '●' : '', function () { c.prayer = o[0]; next(); }); });
      if (s === 'ready') row(S.watch, null, finish, 'item action');
    }, { full: true, back: function () { if (step > 0) { step--; d.draw(); } else if (c.setupDone) app.close(d); } });
    app.modal(d);
  }

  root.TahoeSetup = { keyboard: keyboard, dialog: dialog, option: option, ask: ask, choose: choose, pin: pin, text: text, settings: settings, profiles: profiles, onboarding: onboarding };
})(this);
