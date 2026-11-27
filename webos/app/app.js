/* Tahoe TV for webOS: live TV first. Remote: arrows + OK, Back, CH +/-, number keys, red = favourite. ES5 only. */
(function () {
  'use strict';
  var L = window.TahoeLib;
  var DEFAULT_PLAYLIST = 'https://plsemar.pages.dev'; // same default as the other Tahoe TV apps (OttEngine)
  var BUILT_IN = [
    'https://iptv-org.github.io/iptv/countries/id.m3u',
    'https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv.m3u',
    'https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv-ott.m3u',
    'https://raw.githubusercontent.com/windozalmi/Playlist_IPTV_Pribadi/m3u-ipvt/IPTV%20Indonesia%20by%20WINDO%20ZALMI',
    'https://raw.githubusercontent.com/yositapjani/iptv-indonesia/main/indonesia.m3u'
  ];
  var KEY = { OK: 13, LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, BACK: 461, ESC: 27, BKSP: 8, CH_UP: 33, CH_DOWN: 34, RED: 403, YELLOW: 405, PLAY: 415, PAUSE: 19, STOP: 413 };

  var id = (navigator.language || 'id').toLowerCase().indexOf('en') !== 0;
  var S = id ? {
    fav: 'Favorit', all: 'Semua saluran', film: 'Film & Series', search: 'Cari', settings: 'Pengaturan', movies: 'Film', series: 'Series',
    cats: { national: 'Nasional', sports: 'Olahraga', movies: 'Film & Sinema', entertainment: 'Hiburan', news: 'Berita', kids: 'Anak', documentary: 'Dokumenter', music: 'Musik', lifestyle: 'Gaya Hidup', religious: 'Religi', tvri: 'TVRI Daerah', local: 'TV Daerah', other: 'Lainnya' },
    loading: 'Memuat saluran…', failed: 'Daftar saluran tidak bisa dimuat. Periksa internet, lalu coba lagi.', retry: 'Coba lagi', empty: 'Belum ada isinya.',
    emptyFav: 'Belum ada favorit. Sorot saluran lalu tekan tombol merah.', emptyFilm: 'Belum ada film atau series. Tambahkan playlist berisi tautan pixeldrain atau grup Film/Series.',
    typeSearch: 'Ketik nama saluran', playlist: 'Alamat playlist (M3U)', save: 'Simpan & muat ulang', reset: 'Kembalikan bawaan', reload: 'Muat ulang saluran', badUrl: 'Alamat harus diawali http:// atau https://',
    tune: 'Menyambungkan…', backup: 'Mencoba cadangan %1 dari %2…', off: 'Siaran tidak tersedia', offHint: 'OK coba lagi · Atas/Bawah ganti saluran · Kembali ke daftar',
    hint: 'OK tonton · Merah favorit · CH ganti saluran', count: '%1 saluran', episodes: '%1 episode', added: 'Ditambahkan ke favorit', removed: 'Dihapus dari favorit'
  } : {
    fav: 'Favorites', all: 'All channels', film: 'Movies & Series', search: 'Search', settings: 'Settings', movies: 'Movies', series: 'Series',
    cats: { national: 'National', sports: 'Sports', movies: 'Movies & Cinema', entertainment: 'Entertainment', news: 'News', kids: 'Kids', documentary: 'Documentary', music: 'Music', lifestyle: 'Lifestyle', religious: 'Religious', tvri: 'TVRI Regional', local: 'Local TV', other: 'Other' },
    loading: 'Loading channels…', failed: 'The channel list could not be loaded. Check the internet and try again.', retry: 'Try again', empty: 'Nothing here yet.',
    emptyFav: 'No favorites yet. Highlight a channel and press the red button.', emptyFilm: 'No movies or series yet. Add a playlist with pixeldrain links or a Film/Series group.',
    typeSearch: 'Type a channel name', playlist: 'Playlist address (M3U)', save: 'Save & reload', reset: 'Restore default', reload: 'Reload channels', badUrl: 'The address must start with http:// or https://',
    tune: 'Connecting…', backup: 'Trying backup %1 of %2…', off: 'Channel unavailable', offHint: 'OK try again · Up/Down change channel · Back to the list',
    hint: 'OK watch · Red favorite · CH change channel', count: '%1 channels', episodes: '%1 episodes', added: 'Added to favorites', removed: 'Removed from favorites'
  };
  function f(s, a, b) { return s.replace('%1', a).replace('%2', b); }

  function $(i) { return document.getElementById(i); }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }
  function load(k, d) { try { var v = localStorage.getItem('tahoe.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem('tahoe.' + k, JSON.stringify(v)); return true; } catch (e) { return false; } }

  var live = [], films = [], favs = load('favs', {}), playlist = load('playlist', ''), loading = true, generation = 0;
  var rail = [], railAt = 0, shownRail = null, zone = 'grid', items = [], at = 0, top = 0, query = '';
  var view = 'browse', playing = null, playList = [], playAt = 0, source = 0, filmItem = 0;

  // ---------------------------------------------------------------- loading

  function get(url, done) {
    var x = new XMLHttpRequest(), over = false;
    function end(t) { if (!over) { over = true; done(t); } }
    x.open('GET', url, true); x.timeout = 20000;
    x.onload = function () { end(x.status >= 200 && x.status < 300 ? x.responseText : null); };
    x.onerror = x.ontimeout = x.onabort = function () { end(null); };
    try { x.send(); } catch (e) { end(null); }
  }

  /** The playlist first (shown as soon as it arrives), then the built-in free sources as extra channels and backups. */
  function loadChannels() {
    var mine = ++generation, urls = [playlist || DEFAULT_PLAYLIST].concat(BUILT_IN), got = [], left = urls.length;
    loading = true; render();
    urls.forEach(function (url, i) {
      get(url, function (text) {
        if (mine !== generation) return;
        left--;
        if (text && !/^\s*<(!doctype|html)/i.test(text)) {
          var list = L.parseM3u(text);
          got[i] = i === 0 ? list.map(function (c) { c.primary = true; return c; }) : list.filter(L.isFree);
        }
        if (i === 0 || left === 0) apply(got, left === 0);
      });
    });
  }

  function apply(got, done) {
    var all = [], seen = {};
    got.forEach(function (list) { (list || []).forEach(function (c) { if (!seen[c.url]) { seen[c.url] = 1; all.push(c); } }); });
    if (done) loading = false;
    if (all.length) {
      var focused = items[at] && items[at].key;
      live = L.arrange(all); films = L.library(all);
      if (done) save('cache', { live: live, films: films });
      buildRail(); fill(focused);
    }
    render();
  }

  // ---------------------------------------------------------------- browse

  function buildRail() {
    var current = rail[railAt] && rail[railAt].id;
    rail = [{ id: 'fav', label: S.fav }, { id: 'all', label: S.all }];
    L.CATS.forEach(function (c) { if (live.some(function (e) { return e.cat === c; })) rail.push({ id: 'cat:' + c, label: S.cats[c] }); });
    rail.push({ id: 'film', label: S.film }, { id: 'search', label: S.search }, { id: 'settings', label: S.settings });
    railAt = 1;
    rail.forEach(function (r, i) { if (r.id === current) railAt = i; });
  }

  function section() { return rail[railAt] ? rail[railAt].id : 'all'; }
  function isFilm() { return section() === 'film'; }
  function cols() { return isFilm() ? 6 : 4; }
  function rows() { return isFilm() ? 2 : (section() === 'search' ? 3 : 4); }

  function fill(keepKey) {
    var s = section(), q = query.toLowerCase().trim();
    if (s === 'fav') items = live.filter(function (e) { return favs[e.key]; });
    else if (s === 'all') items = live;
    else if (s.indexOf('cat:') === 0) items = live.filter(function (e) { return e.cat === s.slice(4); });
    else if (s === 'film') items = films.filter(function (t) { return !t.series; }).concat(films.filter(function (t) { return t.series; }));
    else if (s === 'search') items = q ? live.filter(function (e) { return e.name.toLowerCase().indexOf(q) >= 0; }) : [];
    else items = [];
    var found = -1;
    if (keepKey) for (var i = 0; i < items.length; i++) if (items[i].key === keepKey) { found = i; break; }
    if (found >= 0) at = found; else if (shownRail !== s) { at = 0; top = 0; } else at = Math.max(0, Math.min(at, items.length - 1));
    shownRail = s;
  }

  function monogram(name) {
    var w = name.replace(/[^A-Za-z0-9 ]/g, ' ').trim().split(/\s+/);
    return (w.length > 1 ? w[0].charAt(0) + w[1].charAt(0) : (w[0] || '?').slice(0, 3)).toUpperCase();
  }

  function picture(url, box, fallback) {
    if (!url) return;
    var img = new Image();
    img.onload = function () { box.appendChild(img); if (fallback.parentNode === box) box.removeChild(fallback); };
    img.src = url;
  }

  function channelTile(e) {
    var t = el('div', 'tile'), box = el('div', 'logo'), mono = el('span', 'mono', monogram(e.name));
    box.appendChild(mono); picture(e.logo, box, mono);
    t.appendChild(box); t.appendChild(el('div', 'name', e.name));
    if (favs[e.key]) t.appendChild(el('div', 'star', '★'));
    return t;
  }

  function filmTile(x) {
    var t = el('div', 'poster'), box = el('div', 'art'), text = el('span', 'mono', x.title);
    box.appendChild(text); poster(x, function (url) { picture(url, box, text); });
    t.appendChild(box); t.appendChild(el('div', 'name', x.title));
    t.appendChild(el('div', 'sub', x.series ? f(S.episodes, x.items.length) : (S.movies + (x.year ? ' · ' + x.year : ''))));
    return t;
  }

  var posters = load('posters', {});
  function poster(x, done) {
    if (Object.prototype.hasOwnProperty.call(posters, x.key)) { if (posters[x.key]) done(posters[x.key]); return; }
    var q = encodeURIComponent(x.title), key = x.title.toLowerCase().replace(/[^a-z0-9 ]/g, '').trim().replace(/ +/g, '_');
    function keep(url) { posters[x.key] = url || ''; if (url) { save('posters', posters); done(url); } }
    function json(url, pick, next) { get(url, function (t) { var r = null; try { r = pick(JSON.parse(t)); } catch (e) { } if (r) keep(r); else next(); }); }
    function imdb() {
      if (!key) return keep(null);
      json('https://v2.sg.media-imdb.com/suggestion/' + key.charAt(0) + '/' + key + '.json', function (j) {
        var hit = (j.d || []).filter(function (r) { return r.i && L.titleMatches(x.title, r.l) && (String(r.qid).indexOf('tv') === 0) === x.series && (!x.year || !r.y || Math.abs(r.y - x.year) <= 1); })[0];
        return hit && hit.i.imageUrl.replace('._V1_.', '._V1_UX400_.');
      }, function () { keep(null); });
    }
    if (x.series) json('https://api.tvmaze.com/search/shows?q=' + q, function (j) {
      var hit = j.filter(function (r) { return r.show.image && L.titleMatches(x.title, r.show.name); })[0];
      return hit && String(hit.show.image.medium).replace('http://', 'https://');
    }, imdb);
    else json('https://itunes.apple.com/search?media=movie&entity=movie&limit=8&term=' + q, function (j) {
      var hit = j.results.filter(function (r) { return L.titleMatches(x.title, r.trackName) && (!x.year || Math.abs(+String(r.releaseDate).slice(0, 4) - x.year) <= 1); })[0];
      return hit && hit.artworkUrl100.replace(/\/\d+x\d+bb/, '/400x600bb');
    }, imdb);
  }

  function render() {
    var railBox = $('rail'), grid = $('grid'), s = section();
    railBox.innerHTML = '';
    var from = Math.max(0, Math.min(railAt - 6, rail.length - 12));
    rail.slice(from, from + 12).forEach(function (r, k) {
      var i = from + k, d = el('div', 'item' + (i === railAt ? ' on' : '') + (i === railAt && zone === 'rail' ? ' focus' : ''), r.label);
      d.onclick = function () { zone = 'rail'; selectRail(i); if (r.id === 'search' || r.id === 'settings') enter(); };
      railBox.appendChild(d);
    });
    $('title').textContent = rail[railAt] ? rail[railAt].label : '';
    $('count').textContent = s === 'settings' ? '' : (s === 'film' ? String(items.length) : f(S.count, items.length));
    $('searchBox').hidden = s !== 'search';
    $('settingsBox').hidden = s !== 'settings';
    $('hint').textContent = S.hint;
    grid.hidden = s === 'settings';
    grid.className = isFilm() ? 'films' : (s === 'search' ? 'searching' : '');
    grid.innerHTML = '';
    var note = '';
    if (!live.length && loading) note = S.loading;
    else if (!live.length && !films.length) note = S.failed + '  [OK] ' + S.retry;
    else if (!items.length && s !== 'settings') note = s === 'fav' ? S.emptyFav : s === 'film' ? S.emptyFilm : s === 'search' && !query ? S.typeSearch : S.empty;
    $('note').textContent = note;
    if (s === 'settings') return;
    var n = cols(), row = Math.floor(at / n);
    if (row < top) top = row;
    if (row >= top + rows()) top = row - rows() + 1;
    items.slice(top * n, (top + rows()) * n).forEach(function (x, k) {
      var i = top * n + k, t = isFilm() ? filmTile(x) : channelTile(x);
      if (zone === 'grid' && i === at) t.className += ' focus';
      t.onclick = function () { zone = 'grid'; at = i; enter(); };
      grid.appendChild(t);
    });
  }

  function selectRail(i) { railAt = Math.max(0, Math.min(rail.length - 1, i)); fill(); render(); }

  function toast(text) { var t = $('toast'); t.textContent = text; t.className = 'show'; clearTimeout(toast.h); toast.h = setTimeout(function () { t.className = ''; }, 2200); }

  function enter() {
    var s = section();
    if (zone === 'rail') {
      if (s === 'search') { $('query').focus(); return render(); }
      if (s === 'settings') { zone = 'settings'; focusSetting(0); return render(); }
      if (items.length) zone = 'grid';
      return render();
    }
    if (!live.length && !loading) return loadChannels();
    var x = items[at];
    if (!x) return;
    if (!isFilm()) return watch(items, at);
    if (x.items.length > 1) return episodes(x);
    watchFilm(x, 0);
  }

  // ---------------------------------------------------------------- settings & episodes

  var settingAt = 0;
  function settingEls() { return [$('url'), $('save'), $('reset'), $('reload')]; }
  function focusSetting(i) {
    var els = settingEls(); settingAt = Math.max(0, Math.min(els.length - 1, i));
    els.forEach(function (e, k) { e.className = e.className.replace(/ ?focus/, '') + (k === settingAt ? ' focus' : ''); });
    if (settingAt > 0) { $('url').blur(); }
  }
  function pressSetting() {
    var v = $('url').value.trim();
    if (settingAt === 0) { $('url').focus(); return; }
    if (settingAt === 1) {
      if (v && !/^https?:\/\//i.test(v)) return toast(S.badUrl);
      playlist = v; save('playlist', playlist);
    }
    if (settingAt === 2) { playlist = ''; $('url').value = ''; save('playlist', ''); }
    live = []; films = []; save('cache', null); zone = 'rail'; focusSetting(-1); settingEls().forEach(function (e) { e.className = e.className.replace(/ ?focus/, ''); });
    buildRail(); railAt = 1; fill(); loadChannels();
  }

  var epTitle = null, epAt = 0;
  function episodes(t) { epTitle = t; epAt = 0; view = 'episodes'; drawEpisodes(); }
  function drawEpisodes() {
    var box = $('episodes'), list = $('epList');
    box.hidden = view !== 'episodes';
    if (box.hidden) return;
    $('epHead').textContent = epTitle.title; list.innerHTML = '';
    var from = Math.max(0, Math.min(epAt - 4, epTitle.items.length - 9));
    epTitle.items.slice(from, from + 9).forEach(function (it, k) {
      var i = from + k, label = (it.season ? 'S' + it.season + ' ' : '') + (it.episode ? 'E' + it.episode : it.name);
      var d = el('div', 'item' + (i === epAt ? ' focus' : ''), label);
      d.onclick = function () { epAt = i; $('episodes').hidden = true; watchFilm(epTitle, i); };
      list.appendChild(d);
    });
  }

  // ---------------------------------------------------------------- player

  var video, timer = 0, watchdog = 0, osdTimer = 0, lastTime = -1, stuck = 0, failed = false;

  function watch(list, index) { playList = list.slice(); playAt = index; filmItem = -1; start(playList[playAt]); }
  function watchFilm(t, i) {
    filmItem = i; playList = t.items.map(function (it, k) {
      return { key: t.key + ':' + k, name: t.title + (t.items.length > 1 ? ' · ' + (it.episode ? 'E' + it.episode : k + 1) : ''), cat: null, streams: [it.url], vod: true };
    });
    playAt = i; start(playList[i]);
  }

  function start(e) {
    playing = e; source = 0; view = 'play';
    document.body.className = 'playing';
    if (!e.vod) save('last', e.key);
    tune();
  }

  function tune() {
    failed = false; stuck = 0; lastTime = -1;
    clearTimeout(timer);
    $('slate').hidden = true;
    status(source ? f(S.backup, source + 1, playing.streams.length) : S.tune);
    showOsd();
    video.src = playing.streams[source];
    video.load();
    var p = video.play(); if (p && p['catch']) p['catch'](function () { });
    timer = setTimeout(next, playing.vod ? 30000 : 15000);
  }

  function next() {
    if (view !== 'play' || failed) return;
    if (source + 1 < playing.streams.length) { source++; return tune(); }
    failed = true; clearTimeout(timer); release();
    status(''); $('slate').hidden = false; $('slateTitle').textContent = S.off; $('slateHint').textContent = S.offHint; showOsd();
  }

  function release() { video.removeAttribute('src'); video.load(); }
  function status(t) { $('status').textContent = t; }

  function showOsd() {
    $('osd').className = 'show';
    $('osdNum').textContent = playing.vod ? '' : String(playAt + 1);
    $('osdName').textContent = playing.name;
    $('osdCat').textContent = playing.cat ? S.cats[playing.cat] : S.film;
    clearTimeout(osdTimer); osdTimer = setTimeout(function () { if (!failed) $('osd').className = ''; }, 5000);
  }

  function zap(d) {
    if (!playList.length) return;
    playAt = (playAt + d + playList.length) % playList.length;
    start(playList[playAt]);
  }

  function stop() {
    clearTimeout(timer); view = 'browse'; failed = false; release();
    document.body.className = '';
    if (playing && !playing.vod) { for (var i = 0; i < items.length; i++) if (items[i].key === playing.key) { at = i; zone = 'grid'; break; } }
    playing = null; render();
  }

  function setupVideo() {
    video = $('video');
    video.addEventListener('playing', function () { clearTimeout(timer); status(''); $('slate').hidden = true; });
    video.addEventListener('waiting', function () { if (view === 'play' && !failed) status('…'); });
    video.addEventListener('error', function () { if (video.getAttribute('src')) next(); });
    video.addEventListener('ended', function () { if (playing && playing.vod) { if (playAt + 1 < playList.length) zap(1); else stop(); } else next(); });
    watchdog = setInterval(function () { // a live picture that stops moving for 20 s is reconnected on the next feed
      if (view !== 'play' || failed || video.paused || !video.currentTime) return;
      if (video.currentTime === lastTime) { if (++stuck >= 4) { stuck = 0; if (playing.streams.length > 1) source = (source + 1) % playing.streams.length; tune(); } }
      else stuck = 0;
      lastTime = video.currentTime;
    }, 5000);
    document.addEventListener('visibilitychange', function () { // another app on top: let go of the stream
      if (document.hidden) { clearTimeout(timer); if (view === 'play') { if (playing.vod) video.pause(); else release(); } }
      else if (view === 'play') { if (playing.vod) { var p = video.play(); if (p && p['catch']) p['catch'](function () { }); } else { source = 0; tune(); } }
    });
  }

  // ---------------------------------------------------------------- keys

  var digits = '', digitTimer = 0;
  function number(d) {
    digits = (digits + d).slice(-4); toast(digits); clearTimeout(digitTimer);
    digitTimer = setTimeout(function () {
      var n = +digits - 1; digits = '';
      if (n < 0) return;
      if (view === 'play' && !playing.vod && n < playList.length) { playAt = n; start(playList[n]); }
      else if (view === 'browse' && n < items.length) { zone = 'grid'; at = n; render(); }
    }, 1400);
  }

  function toggleFav(e) {
    if (!e || !e.streams) return;
    if (favs[e.key]) delete favs[e.key]; else favs[e.key] = 1;
    save('favs', favs); toast(favs[e.key] ? S.added : S.removed);
    if (view === 'browse') { if (section() === 'fav') { fill(); if (!items.length) zone = 'rail'; } render(); }
  }

  function back(k) { return k === KEY.BACK || k === KEY.ESC; }

  function onKey(ev) {
    var k = ev.keyCode, typing = document.activeElement && document.activeElement.tagName === 'INPUT';
    if (typing) { // the on-screen keyboard owns the keys until OK, Back or Up/Down
      if (k === KEY.OK || back(k) || k === KEY.DOWN || k === KEY.UP) {
        ev.preventDefault(); document.activeElement.blur();
        if (section() === 'search') { zone = items.length && !back(k) ? 'grid' : 'rail'; render(); }
        else if (k === KEY.DOWN || k === KEY.OK) focusSetting(1);
      }
      return;
    }
    if (k === KEY.BKSP) k = KEY.BACK;
    if ((k >= 37 && k <= 40) || k === KEY.OK || back(k) || k === KEY.CH_UP || k === KEY.CH_DOWN) ev.preventDefault();

    if (view === 'play') {
      if (back(k) || k === KEY.STOP || (!playing.vod && k === KEY.LEFT)) return stop();
      if (k === KEY.CH_UP) return zap(1);
      if (k === KEY.CH_DOWN) return zap(-1);
      if (k === KEY.UP) return zap(playing.vod ? -1 : 1);
      if (k === KEY.DOWN) return zap(playing.vod ? 1 : -1);
      if (k === KEY.OK) { if (failed) { source = 0; return tune(); } if (playing.vod) { if (video.paused) video.play(); else video.pause(); } return showOsd(); }
      if (playing.vod && (k === KEY.LEFT || k === KEY.RIGHT)) { video.currentTime = Math.max(0, video.currentTime + (k === KEY.LEFT ? -10 : 10)); return showOsd(); }
      if (k === KEY.PLAY) return video.play();
      if (k === KEY.PAUSE) return video.pause();
      if (k === KEY.RED && !playing.vod) return toggleFav(playing);
      if (k >= 48 && k <= 57) return number(k - 48);
      return showOsd();
    }
    if (view === 'episodes') {
      if (back(k) || k === KEY.LEFT) { view = 'browse'; return drawEpisodes(); }
      if (k === KEY.UP || k === KEY.DOWN) { epAt = Math.max(0, Math.min(epTitle.items.length - 1, epAt + (k === KEY.UP ? -1 : 1))); return drawEpisodes(); }
      if (k === KEY.OK) { $('episodes').hidden = true; return watchFilm(epTitle, epAt); }
      return;
    }
    if (k >= 48 && k <= 57) return number(k - 48);
    if (zone === 'settings') {
      if (back(k) || k === KEY.LEFT) { zone = 'rail'; settingEls().forEach(function (e) { e.className = e.className.replace(/ ?focus/, ''); }); return render(); }
      if (k === KEY.UP) return focusSetting(settingAt - 1);
      if (k === KEY.DOWN) return focusSetting(settingAt + 1);
      if (k === KEY.OK) return pressSetting();
      return;
    }
    if (zone === 'rail') {
      if (back(k)) return window.close();
      if (k === KEY.UP) return selectRail(railAt - 1);
      if (k === KEY.DOWN) return selectRail(railAt + 1);
      if (k === KEY.RIGHT || k === KEY.OK) { if (!live.length && !loading) return loadChannels(); return enter(); }
      return;
    }
    var n = cols();
    if (back(k)) { zone = 'rail'; return render(); }
    if (k === KEY.LEFT) { if (at % n === 0) zone = 'rail'; else at--; return render(); }
    if (k === KEY.RIGHT) { if (at + 1 < items.length) at++; return render(); }
    if (k === KEY.UP) { if (at >= n) at -= n; else if (section() === 'search') { zone = 'rail'; $('query').focus(); } return render(); }
    if (k === KEY.DOWN) { if (at + n < items.length) at += n; else if (Math.floor(at / n) < Math.floor((items.length - 1) / n)) at = items.length - 1; return render(); }
    if (k === KEY.CH_UP || k === KEY.CH_DOWN) { at = Math.max(0, Math.min(items.length - 1, at + (k === KEY.CH_UP ? -1 : 1) * n * rows())); return render(); }
    if (k === KEY.OK) return enter();
    if (k === KEY.RED && !isFilm()) return toggleFav(items[at]);
  }

  // ---------------------------------------------------------------- start

  function init() {
    document.documentElement.lang = id ? 'id' : 'en';
    $('query').placeholder = S.typeSearch; $('urlLabel').textContent = S.playlist; $('url').placeholder = DEFAULT_PLAYLIST; $('url').value = playlist;
    $('save').textContent = S.save; $('reset').textContent = S.reset; $('reload').textContent = S.reload;
    $('query').oninput = function () { query = this.value; at = 0; top = 0; fill(); render(); this.focus(); };
    settingEls().forEach(function (e, i) { e.onclick = function () { zone = 'settings'; focusSetting(i); pressSetting(); }; });
    setupVideo();
    document.addEventListener('keydown', onKey);
    $('video').onclick = function () { if (view === 'play') showOsd(); };
    var cache = load('cache', null);
    if (cache && cache.live) { live = cache.live; films = cache.films || []; }
    buildRail(); fill(); render();
    var last = load('last', null);
    if (last) for (var i = 0; i < live.length; i++) if (live[i].key === last) { at = i; watch(items, i); break; } // like a TV: back on the last channel
    loadChannels();
  }

  init();
})();
