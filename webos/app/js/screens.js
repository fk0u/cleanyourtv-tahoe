/* The pages next to the menu: Home, Live TV, Guide, Movies & Series, Search. Each returns { mount, draw(focused), key(k) }.
 * key() answers 'left' when the focus should go back to the menu. ES5. */
(function (root) {
  'use strict';
  var U = root.TahoeUi, E = root.TahoeEpg, D = root.TahoeData, Text = root.TahoeText, L = root.TahoeLib;
  var KEY = U.KEY, el = U.el, add = U.add, clear = U.clear;

  function now() { return new Date().getTime(); }
  function part(p) { return p ? Math.max(0, Math.min(1, (now() - p[0]) / (p[1] - p[0]))) : 0; }
  function bar(ratio) { var b = el('div', 'bar'), f = el('i'); f.style.width = Math.round(ratio * 100) + '%'; return add(b, f); }

  function logoBox(entry, cls) {
    var box = el('div', cls || 'logo'), mono = el('span', 'mono', U.monogram(entry.name));
    box.appendChild(mono); U.picture(entry.logos || [], box, mono);
    return box;
  }
  /** A channel tile with what is on now. */
  function channelTile(app, e, cls) {
    var t = el('div', cls || 'tile'), p = E.nowNext(app.guide[e.key], now())[0];
    add(t, logoBox(e), el('div', 'name', e.name), el('div', 'prog', p ? p[2] : (app.S.cats[e.cat] || '')));
    if (p) t.appendChild(bar(part(p)));
    if (app.isFav(e.key)) t.appendChild(el('div', 'star', '★'));
    return t;
  }
  function posterTile(app, x) {
    var t = el('div', 'poster'), box = el('div', 'art'), text = el('span', 'mono', x.title);
    box.appendChild(text); D.poster(x, function (url) { U.picture([url], box, text); });
    var progress = app.titleProgress(x);
    add(t, box, el('div', 'name', x.title), el('div', 'sub', x.series ? Text.fill(app.S.episodes, x.items.length) : (app.S.movies + (x.year ? ' · ' + x.year : ''))));
    if (progress) t.appendChild(bar(progress));
    return t;
  }
  /** Focusing a channel plays it behind the page; focusing a film shows its poster there. */
  function previewChannel(app, e) { return function () { app.preview(e); }; }
  function previewFilm(app, x, isCurrent) {
    return function () { app.preview(null); D.poster(x, function (url) { if (!isCurrent || isCurrent()) app.poster(url); }); };
  }
  function note(app) { return app.live.length ? '' : app.loading ? app.S.loading : app.S.failed; }

  // ---------------------------------------------------------------- Home

  function home(app) {
    var page = el('div', 'scroll'), nav = new U.Nav(page, 900), focused = false, head;
    nav.onPointer = app.toPage;

    function greeting() {
      var h = new Date().getHours(), S = app.S;
      return h < 11 ? (h < 4 ? S.night : S.morning) : h < 15 ? S.noon : h < 18 ? S.afternoon : S.night;
    }
    function chips() {
      var box = el('div', 'chips'), S = app.S;
      if (app.weather) box.appendChild(el('div', 'chip', app.weather.temp + '° ' + (app.cfg.city || '')));
      if (app.prayer) {
        var d = new Date(), hm = U.pad(d.getHours()) + ':' + U.pad(d.getMinutes());
        var next = app.prayer.filter(function (p) { return p[1] > hm; })[0] || app.prayer[0];
        box.appendChild(el('div', 'chip', S.prayer[next[0]] + ' ' + next[1]));
      }
      box.appendChild(el('div', 'chip clock', U.clock(now())));
      return box;
    }
    function section(title, row, cards) {
      if (!cards.length) return row;
      page.appendChild(el('h2', null, title));
      var view = el('div', 'view'), track = el('div', 'track');
      cards.forEach(function (c) { track.appendChild(c.el); nav.add(row, c.el, c.ok, { id: c.id, onFocus: c.focus }); });
      page.appendChild(add(view, track));
      return row + 1;
    }
    function channelCards(prefix, entries) {
      return entries.slice(0, 12).map(function (e, i) { return { el: channelTile(app, e, 'tile card'), id: prefix + e.key, ok: function () { app.watch(entries, i); }, focus: previewChannel(app, e) }; });
    }
    function filmCards(prefix, titles) {
      return titles.map(function (x) {
        var id = prefix + x.key;
        return { el: posterTile(app, x), id: id, ok: function () { app.openTitle(x); }, focus: previewFilm(app, x, function () { return nav.current() && nav.current().extra.id === id; }) };
      });
    }
    /** Weather and the next prayer time, each opening its full view. */
    function infoCards() {
      var S = app.S, cards = [], d = new Date(), hm = U.pad(d.getHours()) + ':' + U.pad(d.getMinutes());
      var w = app.weather;
      cards.push({ el: add(el('div', 'infocard weather'), el('div', 'big', w ? w.temp + '°' : '–'), el('div', 'name', w ? S.wx[D.sky(w.code)] : S.weather), el('div', 'sub', app.cfg.city || S.setCityFirst)),
        id: 'weather', ok: app.weatherDialog, focus: function () { app.preview(null); app.poster(''); } });
      if (app.cfg.prayer) {
        var next = app.prayer ? (app.prayer.filter(function (p) { return p[1] > hm; })[0] || app.prayer[0]) : null;
        cards.push({ el: add(el('div', 'infocard prayer'), el('div', 'big', next ? next[1].replace(':', '.') : '–'), el('div', 'name', next ? S.prayer[next[0]] : S.prayerTitle), el('div', 'sub', S.prayerTitle)),
          id: 'prayer', ok: app.prayerDialog, focus: function () { app.preview(null); app.poster(''); } });
      }
      return cards;
    }

    function draw(show) {
      focused = show;
      var keep = nav.current() && nav.current().extra.id, row = nav.r, col = nav.c;
      nav.reset(); clear(page); clear(head);
      add(head, el('h1', null, greeting() + (app.profileName() ? ', ' + app.profileName() : '')), chips());
      if (!app.live.length) { page.appendChild(el('div', 'note', note(app))); return; }
      var recent = app.recents(), featured = recent[0] || app.live[0], list = recent.length ? recent : app.live;
      var p = E.nowNext(app.guide[featured.key], now()), hero = el('div', 'hero'), copy = el('div', 'copy');
      var watch = el('div', 'button primary', app.S.watch), guide = el('div', 'button', app.S.guide);
      add(copy, el('div', 'eyebrow', featured.name.toUpperCase()), el('div', 'title', p[0] ? p[0][2] : featured.name),
        el('div', 'meta', p[0] ? U.clock(p[0][0]) + ' – ' + U.clock(p[0][1]) + (p[1] ? '   ·   ' + app.S.next + ' ' + p[1][2] : '') : (app.S.cats[featured.cat] || '')),
        p[0] ? bar(part(p[0])) : null, add(el('div', 'buttons'), watch, guide));
      copy.insertBefore(add(el('div', 'badges'), el('span', 'live', 'LIVE'), el('span', 'cat', app.S.cats[featured.cat] || '')), copy.firstChild);
      page.appendChild(add(hero, copy, logoBox(featured, 'logo big')));
      nav.add(0, watch, function () { app.watch(list, 0); }, { id: 'watch', onFocus: previewChannel(app, featured) });
      nav.add(0, guide, function () { app.go('guide', featured.key); }, { id: 'guide', onFocus: previewChannel(app, featured) });
      var r = 1;
      r = section(app.S.cont, r, channelCards('r', recent).concat(filmCards('c', app.continueFilms())));
      r = section(app.S.fav, r, channelCards('f', app.live.filter(function (e) { return app.isFav(e.key); })));
      var onAir = app.live.filter(function (e) { return app.guide[e.key] && E.nowNext(app.guide[e.key], now())[0]; });
      r = section(app.S.onAir, r, channelCards('n', onAir.length ? onAir : app.live));
      r = section(app.S.film, r, filmCards('m', app.films.slice(0, 14)));
      section(app.S.forYou, r, infoCards());
      nav.compact();
      if (!keep || !nav.focusId(keep)) { nav.r = row; nav.c = col; }
      nav.focus(show);
    }
    return {
      mount: function (box) { head = el('div', 'head'); add(box, head, add(el('div', 'viewport'), page)); },
      draw: draw,
      key: function (k) { if (!app.live.length) return k === KEY.LEFT ? 'left' : false; var r = nav.key(k); return r === 'up' ? true : r; }
    };
  }

  // ---------------------------------------------------------------- Live TV

  function live(app, wantedKey) {
    var head, chipTrack, text, chipAt = 0, area = 'grid', focused = false, cats = [];
    var box = el('div', 'grid channels');
    var grid = new U.Grid(box, 4, 3, function (e) { return channelTile(app, e); }, function (e, i) { app.watch(grid.items, i); });
    grid.onPointer = function () { area = 'grid'; app.toPage(); };
    grid.onFocus = function (e) { app.preview(e); };

    function filtered(id) {
      if (id === 'fav') return app.live.filter(function (e) { return app.isFav(e.key); });
      if (id === 'all') return app.live;
      return app.live.filter(function (e) { return e.cat === id; });
    }
    function draw(show) {
      focused = show;
      var current = cats[chipAt] ? cats[chipAt].id : 'all', keep = grid.current() && grid.current().key;
      cats = [{ id: 'all', label: app.S.all }, { id: 'fav', label: app.S.fav }];
      L.CATS.forEach(function (c) { if (app.live.some(function (e) { return e.cat === c; })) cats.push({ id: c, label: app.S.cats[c] }); });
      chipAt = 0; cats.forEach(function (c, i) { if (c.id === current) chipAt = i; });
      var items = filtered(cats[chipAt].id);
      grid.set(items, wantedKey || keep); wantedKey = undefined;
      clear(chipTrack);
      cats.forEach(function (c, i) {
        var d = el('div', 'chip' + (i === chipAt ? ' on' : '') + (show && area === 'chips' && i === chipAt ? ' focus' : ''), c.label);
        d.onclick = function () { chipAt = i; area = 'chips'; grid.set(filtered(c.id)); app.toPage(); };
        chipTrack.appendChild(d);
      });
      var on = chipTrack.children[chipAt];
      if (on) chipTrack.style.transform = 'translateX(' + -Math.max(0, on.offsetLeft - 700) + 'px)';
      clear(head); add(head, el('h1', null, app.S.live), el('span', 'count', Text.fill(app.S.count, items.length)));
      grid.draw(show && area === 'grid');
      text.textContent = note(app) || (items.length ? app.S.liveHint : cats[chipAt].id === 'fav' ? app.S.emptyFav : app.S.empty);
    }
    return {
      mount: function (page) { head = el('div', 'head'); chipTrack = el('div', 'track'); text = el('div', 'hint'); add(page, head, add(el('div', 'view chiprow'), chipTrack), box, text); },
      draw: draw,
      key: function (k) {
        if (area === 'chips') {
          if (k === KEY.DOWN || k === KEY.OK) { if (grid.items.length) area = 'grid'; }
          else if (k === KEY.LEFT) { if (chipAt === 0) return 'left'; chipAt--; grid.set(filtered(cats[chipAt].id)); }
          else if (k === KEY.RIGHT) { if (chipAt + 1 < cats.length) { chipAt++; grid.set(filtered(cats[chipAt].id)); } }
          else return k === KEY.UP;
          draw(true); return true;
        }
        if (k === KEY.RED && grid.current()) { app.toggleFav(grid.current().key); draw(true); return true; }
        var r = grid.key(k);
        if (r === 'up') { area = 'chips'; draw(true); return true; }
        return r;
      }
    };
  }

  // ---------------------------------------------------------------- Guide

  function guide(app, wantedKey) {
    var head, chList, prList, text, ch = 0, pr = 0, area = 'channels', channels = [], programmes = [];

    function list(box, items, at, active, make, press) {
      clear(box);
      var from = Math.max(0, Math.min(at - 3, items.length - 8));
      items.slice(from, from + 8).forEach(function (x, k) {
        var i = from + k, d = make(x, i);
        if (i === at) d.className += active ? ' focus' : ' on';
        d.onclick = function () { app.toPage(); press(i); };
        box.appendChild(d);
      });
    }
    function selected() { return channels[ch]; }
    function draw(show) {
      var keep = selected() && selected().key, keepStart = programmes[pr] && programmes[pr][0];
      var withGuide = [], without = [];
      app.live.forEach(function (e) { (app.guide[e.key] && app.guide[e.key].length ? withGuide : without).push(e); });
      channels = withGuide.concat(without);
      var target = wantedKey || keep;
      if (target) channels.forEach(function (e, i) { if (e.key === target) ch = i; });
      wantedKey = undefined;
      ch = Math.max(0, Math.min(ch, channels.length - 1));
      var t = now();
      programmes = selected() ? (app.guide[selected().key] || []).filter(function (p) { return p[1] > t; }) : [];
      if (keepStart) programmes.forEach(function (p, i) { if (p[0] === keepStart) pr = i; });
      pr = Math.max(0, Math.min(pr, programmes.length - 1));
      if (!programmes.length) area = 'channels';
      clear(head); add(head, el('h1', null, app.S.guide), el('span', 'count', app.guideLoading ? app.S.guideLoading : U.clock(t)));
      list(chList, channels, ch, show && area === 'channels', function (e) {
        var d = el('div', 'row'), p = E.nowNext(app.guide[e.key], t)[0];
        return add(d, logoBox(e, 'logo small'), add(el('div', 'two'), el('span', 'title', e.name), el('span', 'sub', p ? p[2] : app.S.noGuide)));
      }, function (i) { ch = i; pr = 0; area = 'channels'; app.watch(channels, i); });
      list(prList, programmes, pr, show && area === 'programmes', function (p) {
        var d = el('div', 'row'), live_ = p[0] <= t;
        add(d, el('span', 'time', U.clock(p[0])), add(el('div', 'two'), el('span', 'title', p[2]), el('span', 'sub', live_ ? app.S.now + ' · ' + app.S.watch : app.isReminded(selected().key, p[0]) ? '⏰ ' + app.S.reminded : U.clock(p[0]) + ' – ' + U.clock(p[1]))));
        if (live_) d.appendChild(bar(part(p)));
        return d;
      }, function (i) { pr = i; area = 'programmes'; press(); });
      if (!programmes.length && selected()) prList.appendChild(el('div', 'note', app.guideLoading ? app.S.guideLoading : app.S.noGuide));
      text.textContent = note(app) || app.S.guideHint;
      if (show && selected()) app.preview(selected());
    }
    function press() {
      var p = programmes[pr];
      if (!p) return;
      if (p[0] <= now()) return app.watch(channels, ch);
      app.toggleReminder(selected(), p); draw(true);
    }
    return {
      mount: function (page) { head = el('div', 'head'); chList = el('div', 'list narrow'); prList = el('div', 'list wide'); text = el('div', 'hint'); add(page, head, add(el('div', 'columns'), chList, prList), text); },
      draw: draw,
      key: function (k) {
        var step = k === KEY.UP ? -1 : k === KEY.DOWN ? 1 : k === KEY.CH_UP ? -8 : k === KEY.CH_DOWN ? 8 : 0;
        if (step) { if (area === 'channels') { ch = Math.max(0, Math.min(channels.length - 1, ch + step)); pr = 0; programmes = []; } else pr = Math.max(0, Math.min(programmes.length - 1, pr + step)); draw(true); return true; }
        if (k === KEY.LEFT) { if (area === 'channels') return 'left'; area = 'channels'; draw(true); return true; }
        if (k === KEY.RIGHT) { if (programmes.length) area = 'programmes'; draw(true); return true; }
        if (k === KEY.OK) { if (area === 'channels') { if (selected()) app.watch(channels, ch); } else press(); return true; }
        if (k === KEY.RED && selected()) { app.toggleFav(selected().key); return true; }
        return false;
      }
    };
  }

  // ---------------------------------------------------------------- Movies & Series

  function films(app) {
    var head, text, box = el('div', 'grid films');
    var grid = new U.Grid(box, 6, 2, function (x) { return posterTile(app, x); }, function (x) { app.openTitle(x); });
    grid.onPointer = app.toPage;
    grid.onFocus = function (x) { D.poster(x, function (url) { if (grid.current() === x) app.poster(url); }); };
    return {
      mount: function (page) { head = el('div', 'head'); text = el('div', 'hint'); add(page, head, box, text); },
      draw: function (show) {
        var items = app.films.filter(function (t) { return !t.series; }).concat(app.films.filter(function (t) { return t.series; }));
        grid.set(items, grid.current() && grid.current().key);
        clear(head); add(head, el('h1', null, app.S.film), el('span', 'count', String(items.length)));
        grid.draw(show);
        text.textContent = items.length ? '' : (app.loading ? app.S.loading : app.S.emptyFilm);
      },
      key: function (k) { var r = grid.key(k); return r === 'up' ? true : r; }
    };
  }

  // ---------------------------------------------------------------- Search

  function search(app) {
    var query = '', field, results, keys = el('div', 'keyboard'), nav = new U.Nav(null), area = 'keys', hits = [], at = 0;
    nav.onPointer = function () { area = 'keys'; app.toPage(); };

    function find() {
      var q = query.toLowerCase().trim();
      hits = []; at = 0;
      if (!q) return;
      var t = now();
      app.live.forEach(function (e, i) { if (e.name.toLowerCase().indexOf(q) >= 0 && hits.length < 60) hits.push({ title: e.name, sub: (E.nowNext(app.guide[e.key], t)[0] || [])[2] || app.S.cats[e.cat], ok: function () { app.watch(app.live, i); } }); });
      app.live.forEach(function (e, i) {
        (app.guide[e.key] || []).forEach(function (p) {
          if (p[1] <= t || hits.length >= 120 || p[2].toLowerCase().indexOf(q) < 0) return;
          var onAir = p[0] <= t;
          hits.push({ title: p[2], sub: e.name + ' · ' + (onAir ? app.S.now : U.clock(p[0])) + (app.isReminded(e.key, p[0]) ? ' · ⏰' : ''), ok: function () { if (onAir) app.watch(app.live, i); else { app.toggleReminder(e, p); find(); drawResults(true); } } });
        });
      });
      app.films.forEach(function (x) { if (x.title.toLowerCase().indexOf(q) >= 0 && hits.length < 160) hits.push({ title: x.title, sub: x.series ? app.S.series : app.S.movies, ok: function () { app.openTitle(x); } }); });
    }
    function drawResults(show) {
      clear(results);
      at = Math.max(0, Math.min(at, hits.length - 1));
      var from = Math.max(0, Math.min(at - 3, hits.length - 8));
      hits.slice(from, from + 8).forEach(function (h, k) {
        var i = from + k, d = add(el('div', 'row' + (show && area === 'results' && i === at ? ' focus' : '')), add(el('div', 'two'), el('span', 'title', h.title), el('span', 'sub', h.sub)));
        d.onclick = function () { area = 'results'; at = i; app.toPage(); h.ok(); };
        results.appendChild(d);
      });
      if (!hits.length) results.appendChild(el('div', 'note', query ? app.S.empty : app.S.typeSearch));
    }
    function typed(value) { query = value; find(); draw(true); }
    function draw(show) {
      field.textContent = query || app.S.typeSearch; field.className = 'field' + (query ? '' : ' empty');
      root.TahoeSetup.keyboard(app, keys, nav, function () { return query; }, typed);
      nav.focus(show && area === 'keys');
      drawResults(show);
    }
    return {
      mount: function (page) { field = el('div', 'field'); results = el('div', 'list wide'); add(page, add(el('div', 'head'), el('h1', null, app.S.search)), add(el('div', 'columns'), add(el('div', 'typing'), field, keys), results)); },
      draw: function (show) { var keep = at; find(); at = keep; draw(show); },
      type: function (c) { typed(c === null ? query.slice(0, -1) : query + c); },
      key: function (k) {
        if (area === 'results') {
          if (k === KEY.LEFT) { area = 'keys'; draw(true); return true; }
          if (k === KEY.UP || k === KEY.DOWN) { at += k === KEY.UP ? -1 : 1; drawResults(true); return true; }
          if (k === KEY.OK && hits[at]) { hits[at].ok(); return true; }
          return false;
        }
        if (k === KEY.RIGHT && nav.current() && nav.c === nav.rows[nav.r].length - 1 && hits.length) { area = 'results'; draw(true); return true; }
        var r = nav.key(k);
        return r === 'up' ? true : r;
      }
    };
  }

  // ---------------------------------------------------------------- History

  function history(app) {
    var head, box = el('div', 'list wide'), text, at = 0, rows = [];
    function when(t) {
      var d = new Date(t), today = new Date(), y = new Date(today.getTime() - 86400000);
      if (d.toDateString() === today.toDateString()) return app.S.today + ' ' + U.clock(t);
      if (d.toDateString() === y.toDateString()) return app.S.yesterday + ' ' + U.clock(t);
      return d.toLocaleDateString(app.cfg.lang, { day: 'numeric', month: 'short' }) + ' ' + U.clock(t);
    }
    function play(r) { if (r.entry) app.watch(app.live, r.index); else app.openTitle(r.title); }
    function draw(show) {
      rows = app.history();
      at = Math.max(0, Math.min(at, rows.length - 1));
      clear(head); add(head, el('h1', null, app.S.history), el('span', 'count', String(rows.length)));
      clear(box);
      var from = Math.max(0, Math.min(at - 3, rows.length - 8));
      rows.slice(from, from + 8).forEach(function (r, k) {
        var i = from + k, d = el('div', 'row' + (show && i === at ? ' focus' : ''));
        if (r.entry) {
          var p = E.nowNext(app.guide[r.entry.key], now())[0];
          add(d, logoBox(r.entry, 'logo small'), add(el('div', 'two'), el('span', 'title', r.entry.name), el('span', 'sub', when(r.h.at) + (p ? '  ·  ' + app.S.now + ': ' + p[2] : ''))));
        } else {
          var progress = app.titleProgress(r.title), item = r.title.items[r.h.item || 0];
          var ep = r.title.items.length > 1 && item && item.episode ? '  ·  ' + (item.season ? 'S' + item.season + ' ' : '') + 'E' + item.episode : '';
          add(d, add(el('div', 'logo small'), el('span', 'mono', '▶')), add(el('div', 'two'), el('span', 'title', r.title.title), el('span', 'sub', when(r.h.at) + ep)));
          if (progress) d.appendChild(bar(progress));
        }
        d.onclick = function () { at = i; app.toPage(); play(r); };
        box.appendChild(d);
      });
      text.textContent = rows.length ? app.S.historyHint : '';
      if (!rows.length) box.appendChild(el('div', 'note', app.S.emptyHistory));
      var cur = rows[at];
      if (show && cur && cur.entry) app.preview(cur.entry); else if (show && cur) D.poster(cur.title, function (url) { if (rows[at] === cur) app.poster(url); });
    }
    return {
      mount: function (page) { head = el('div', 'head'); text = el('div', 'hint'); add(page, head, box, text); },
      draw: draw,
      key: function (k) {
        if (k === KEY.LEFT) return 'left';
        if (k === KEY.UP || k === KEY.DOWN) { if (k === KEY.UP && at === 0) return true; at += k === KEY.UP ? -1 : 1; draw(true); return true; }
        if (k === KEY.CH_UP || k === KEY.CH_DOWN) { at += k === KEY.CH_UP ? -8 : 8; draw(true); return true; }
        if (k === KEY.OK && rows[at]) { play(rows[at]); return true; }
        if (k === KEY.RED && rows[at]) { app.forget(rows[at].h); return true; }
        return false;
      }
    };
  }

  root.TahoeScreens = { home: home, live: live, guide: guide, film: films, history: history, search: search };
})(this);
