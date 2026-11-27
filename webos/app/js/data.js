/* Everything from the network: channels, guide, posters, weather, prayer times, city search, latest version. ES5. */
(function (root) {
  'use strict';
  var L = root.TahoeLib, E = root.TahoeEpg, U = root.TahoeUi, store = U.store;
  var DEFAULT_PLAYLIST = 'https://plsemar.pages.dev'; // same default as the other Tahoe TV apps (OttEngine)
  var ID_SOURCES = [
    'https://iptv-org.github.io/iptv/countries/id.m3u',
    'https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv.m3u',
    'https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv-ott.m3u',
    'https://raw.githubusercontent.com/windozalmi/Playlist_IPTV_Pribadi/m3u-ipvt/IPTV%20Indonesia%20by%20WINDO%20ZALMI',
    'https://raw.githubusercontent.com/yositapjani/iptv-indonesia/main/indonesia.m3u'
  ];
  var ID_GUIDES = ['https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml', 'https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml', 'https://epg.pw/xmltv/epg_ID.xml'];
  var COUNTRIES = { ID: 'Indonesia', MY: 'Malaysia', SG: 'Singapore', BN: 'Brunei', US: 'United States', GB: 'United Kingdom', AU: 'Australia', JP: 'Japan', KR: 'Korea', CN: 'China', TW: 'Taiwan', IN: 'India', SA: 'Saudi Arabia', AE: 'UAE', TR: 'Türkiye' };

  function get(url, done, timeout) {
    var x = new XMLHttpRequest(), over = false;
    function end(t) { if (!over) { over = true; done(t); } }
    x.open('GET', url, true); x.timeout = timeout || 20000;
    x.onload = function () { end(x.status === 0 || (x.status >= 200 && x.status < 300) ? x.responseText : null); };
    x.onerror = x.ontimeout = x.onabort = function () { end(null); };
    try { x.send(); } catch (e) { end(null); }
  }
  function json(url, done) { get(url, function (t) { var j = null; try { j = JSON.parse(t); } catch (e) { } done(j); }); }

  var generation = 0;
  /** The viewer's playlist first (shown as soon as it arrives), then the free sources of the chosen countries. */
  function channels(cfg, done) {
    var mine = ++generation, urls = [cfg.playlist || DEFAULT_PLAYLIST];
    (cfg.countries.length ? cfg.countries : ['ID']).forEach(function (c) {
      if (c === 'ID') urls = urls.concat(ID_SOURCES); else urls.push('https://iptv-org.github.io/iptv/countries/' + c.toLowerCase() + '.m3u');
    });
    var got = [], left = urls.length;
    urls.forEach(function (url, i) {
      get(url, function (text) {
        if (mine !== generation) return;
        left--;
        if (text && !/^\s*<(!doctype|html)/i.test(text)) {
          var list = L.parseM3u(text);
          got[i] = i === 0 ? list.map(function (c) { c.primary = true; return c; }) : list.filter(L.isFree);
        }
        if (i !== 0 && left !== 0) return;
        var all = [], seen = {};
        got.forEach(function (l) { (l || []).forEach(function (c) { if (!seen[c.url]) { seen[c.url] = 1; all.push(c); } }); });
        if (!all.length) { if (left === 0) done(null, null, true); return; }
        var live = L.arrange(all), films = L.library(all);
        if (left === 0) store.set('cache', { live: live, films: films });
        done(live, films, left === 0);
      });
    });
  }

  /** National first, then the categories the viewer likes, then the rest; or by name. */
  function sorted(live, cfg) {
    var l = live.slice();
    function name(a, b) { var x = a.name.toLowerCase(), y = b.name.toLowerCase(); return x < y ? -1 : x > y ? 1 : 0; }
    if (cfg.sort === 'asc') return l.sort(name);
    if (cfg.sort === 'desc') return l.sort(function (a, b) { return name(b, a); });
    function group(e) { if (e.cat === 'national') return 0; var f = cfg.cats.indexOf(e.cat); return f >= 0 ? 1 + f : 100 + L.CATS.indexOf(e.cat); }
    return l.sort(function (a, b) { return group(a) - group(b) || a.rank - b.rank || name(a, b); });
  }

  var guideRun = 0;
  /** Cached guide at once, then every source in turn, parsed in small steps. Fetched again after 6 hours. */
  function guide(cfg, live, done) {
    var mine = ++guideRun, cached = store.get('guide', null), now = new Date().getTime();
    if (cached && cached.data) { done(cached.data, false); if (now - cached.at < 6 * 3600000) { done(cached.data, true); return; } }
    var want = E.wanted(live), result = {}, urls = [];
    cfg.countries.forEach(function (c) { if (c === 'ID') urls = urls.concat(ID_GUIDES); else urls.push('https://epg.pw/xmltv/epg_' + c + '.xml'); });
    if (!urls.length) urls = ID_GUIDES.slice();
    function one(i) {
      if (mine !== guideRun) return;
      if (i >= urls.length) {
        if (Object.keys(result).length) store.set('guide', { at: new Date().getTime(), data: result });
        done(Object.keys(result).length ? result : (cached && cached.data) || {}, true);
        return;
      }
      get(urls[i], function (xml) {
        if (mine !== guideRun) return;
        if (!xml) return one(i + 1);
        var p = E.parser(xml, want, now - 3 * 3600000, now + 24 * 3600000);
        (function step() {
          if (mine !== guideRun) return;
          if (!p.step(300)) return setTimeout(step, 15);
          xml = null; E.merge(result, p.result()); done(result, false); one(i + 1);
        })();
      }, 60000);
    }
    one(0);
  }

  var posters = store.get('posters', {}), missed = {}, pending = {};
  /** A poster by title: TVMaze or iTunes, then IMDb's suggestions; only a title that really matches. One lookup per title at a
   *  time (pages redraw on every key press); a miss is only remembered until the app restarts, so a bad moment isn't kept. */
  function poster(x, done) {
    if (posters[x.key]) return done(posters[x.key]);
    if (missed[x.key]) return;
    if (pending[x.key]) return void pending[x.key].push(done);
    pending[x.key] = [done];
    var q = encodeURIComponent(x.title), key = x.title.toLowerCase().replace(/[^a-z0-9 ]/g, '').trim().replace(/ +/g, '_');
    function finish(url) {
      var waiting = pending[x.key]; delete pending[x.key];
      if (!url) { missed[x.key] = 1; return; }
      posters[x.key] = url; store.set('posters', posters);
      waiting.forEach(function (f) { f(url); });
    }
    function pick(url, choose, next) { json(url, function (j) { var r = null; try { r = choose(j); } catch (e) { } if (r) finish(r); else next(); }); }
    function imdb() {
      if (!key) return finish(null);
      pick('https://v2.sg.media-imdb.com/suggestion/' + key.charAt(0) + '/' + key + '.json', function (j) {
        var hit = (j.d || []).filter(function (r) { return r.i && L.titleMatches(x.title, r.l) && (String(r.qid).indexOf('tv') === 0) === x.series && (!x.year || !r.y || Math.abs(r.y - x.year) <= 1); })[0];
        return hit && hit.i.imageUrl.replace('._V1_.', '._V1_UX400_.');
      }, function () { finish(null); });
    }
    if (x.series) pick('https://api.tvmaze.com/search/shows?q=' + q, function (j) {
      var hit = j.filter(function (r) { return r.show.image && L.titleMatches(x.title, r.show.name); })[0];
      return hit && String(hit.show.image.medium).replace('http://', 'https://');
    }, imdb);
    else pick('https://itunes.apple.com/search?media=movie&entity=movie&limit=8&term=' + q, function (j) {
      var hit = j.results.filter(function (r) { return L.titleMatches(x.title, r.trackName) && (!x.year || Math.abs(+String(r.releaseDate).slice(0, 4) - x.year) <= 1); })[0];
      return hit && hit.artworkUrl100.replace(/\/\d+x\d+bb/, '/400x600bb');
    }, imdb);
  }

  function weather(cfg, done) {
    if (cfg.lat === null) return done(null);
    json('https://api.open-meteo.com/v1/forecast?latitude=' + cfg.lat + '&longitude=' + cfg.lon + '&current=temperature_2m,weather_code&timezone=auto', function (j) {
      done(j && j.current ? { temp: Math.round(j.current.temperature_2m), code: j.current.weather_code } : null);
    });
  }
  function prayer(cfg, done) {
    if (!cfg.prayer || cfg.lat === null) return done(null);
    var d = new Date(), day = U.pad(d.getDate()) + '-' + U.pad(d.getMonth() + 1) + '-' + d.getFullYear();
    var method = { ID: 20, MY: 17, BN: 17, SG: 11, SA: 4, AE: 16, TR: 13, IN: 1, US: 2, GB: 15 }[cfg.country] || 3;
    json('https://api.aladhan.com/v1/timings/' + day + '?latitude=' + cfg.lat + '&longitude=' + cfg.lon + '&method=' + method, function (j) {
      var t = j && j.data && j.data.timings;
      done(t ? ['Fajr', 'Dhuhr', 'Asr', 'Maghrib', 'Isha'].map(function (n) { return [n, String(t[n]).slice(0, 5)]; }) : null);
    });
  }
  function cities(q, lang, done) {
    json('https://geocoding-api.open-meteo.com/v1/search?count=8&language=' + lang + '&name=' + encodeURIComponent(q), function (j) {
      done(((j && j.results) || []).map(function (r) { return { name: r.name, region: [r.admin1, r.country].filter(Boolean).join(', '), country: r.country_code, lat: r.latitude, lon: r.longitude }; }));
    });
  }

  /** a > b for "3.8.10" style versions. */
  function newer(a, b) {
    var x = String(a).replace(/^v/, '').split(/[.-]/), y = String(b).replace(/^v/, '').split(/[.-]/);
    for (var i = 0; i < 3; i++) { var d = (+x[i] || 0) - (+y[i] || 0); if (d) return d > 0; }
    return false;
  }
  function version(done) { json('appinfo.json', function (j) { done(j && j.version ? j.version : '0.0.0'); }); }
  function latest(done) { json('https://api.github.com/repos/fk0u/tahoe-tv/releases/latest', function (j) { done(j && j.tag_name ? String(j.tag_name).replace(/^v/, '') : null); }); }

  root.TahoeData = { DEFAULT_PLAYLIST: DEFAULT_PLAYLIST, COUNTRIES: COUNTRIES, get: get, channels: channels, sorted: sorted, guide: guide, poster: poster, weather: weather, prayer: prayer, cities: cities, newer: newer, version: version, latest: latest };
})(this);
