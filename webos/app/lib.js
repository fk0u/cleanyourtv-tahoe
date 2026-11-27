/* Tahoe TV for webOS: playlist parsing and channel rules, the same ones as :core (M3u, LocalTv, Sources.free, FilmLibrary).
 * Plain ES5 so older webOS browsers run it; also loadable from Node for the tests. */
(function (root) {
  'use strict';

  var CATS = ['national', 'sports', 'movies', 'entertainment', 'news', 'kids', 'documentary', 'music', 'lifestyle',
    'religious', 'tvri', 'local', 'other'];

  // ---------------------------------------------------------------- parsing

  function unquote(v) { return String(v).trim().replace(/^["']|["']$/g, '').trim(); }

  function headerKey(k) {
    k = String(k).trim().toLowerCase();
    if (/^(http-)?refer?rer$/.test(k)) return 'referer';
    if (/^(http-)?user-agent$/.test(k)) return 'ua';
    return null;
  }

  function utf8(binary) {
    try { return decodeURIComponent(escape(binary)); } catch (e) { return binary; }
  }

  /** OTT servers send base64 fields; anything that doesn't decode to readable text is taken as it is. */
  function b64(v) {
    if (!v) return '';
    var s = String(v).trim();
    if (!/^[A-Za-z0-9+\/_-]+={0,2}$/.test(s)) return s;
    try {
      var t = s.replace(/-/g, '+').replace(/_/g, '/');
      while (t.length % 4) t += '=';
      var out = utf8(root.atob ? root.atob(t) : Buffer.from(t, 'base64').toString('binary')).trim();
      return out && !/[\u0000-\u0008\u000e-\u001f�]/.test(out) ? out : s;
    } catch (e) { return s; }
  }

  function parseOttJson(text) {
    var out = [], j, cats = {}, k, i;
    try { j = JSON.parse(text); } catch (e) { return out; }
    for (k in (j.categories || {})) if (j.categories[k] && j.categories[k].name) cats[k] = b64(j.categories[k].name);
    var list = j.channels || [];
    for (i = 0; i < list.length; i++) {
      var c = list[i], name = b64(c.name), url = b64(c.url);
      if (!name || !/^https?:\/\//i.test(url)) continue;
      var cat = b64(c.category);
      out.push({ name: name.replace(/\s*\(\d+[pi]\)/g, ''), url: url, logo: b64(c.icon) || null,
        group: cats[cat] || cats[c.category] || cat || null, referer: b64(c.referrer || c.referer) || null,
        ua: b64(c.user_agent) || null, drm: false });
    }
    return out;
  }

  function parseM3u(text) {
    var t = String(text || '').replace(/^﻿/, '');
    if (/^\s*\{/.test(t) && t.indexOf('"channels"') >= 0) return parseOttJson(t);
    var lines = t.split(/\r?\n/), out = [], seen = {}, info = null, head = {}, drm = false, group = null;
    function reset() { info = null; head = {}; drm = false; group = null; }
    function keyValues(raw) {
      raw.split(/[&|]/).forEach(function (kv) {
        var at = kv.indexOf('=');
        if (at < 0) return;
        var k = kv.slice(0, at), v = unquote(kv.slice(at + 1)), h = headerKey(k);
        if (h && v) head[h] = v;
        else if (k.trim().toLowerCase() === 'license_key' && v) drm = true;
      });
    }
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (line.indexOf('#EXTINF') === 0) { if (info !== null) reset(); info = line; }
      else if (line.indexOf('#EXTVLCOPT:') === 0) {
        var o = line.slice(11), at = o.indexOf('=');
        if (at > 0 && headerKey(o.slice(0, at))) head[headerKey(o.slice(0, at))] = unquote(o.slice(at + 1));
      } else if (line.indexOf('#KODIPROP:') === 0) {
        if (/license_key=\s*\S/i.test(line)) drm = true;
        if (/stream_headers=/i.test(line)) keyValues(line.slice(line.indexOf('=') + 1));
      } else if (line.indexOf('#EXTGRP:') === 0) group = line.slice(8).trim() || null;
      else if (!line || line.charAt(0) === '#') continue;
      else if (info !== null) {
        var comma = info.lastIndexOf(','), meta = comma >= 0 ? info.slice(0, comma) : info, attrs = {}, m;
        var re = /([\w-]+)="([^"]*)"/g;
        while ((m = re.exec(meta))) attrs[m[1].toLowerCase()] = m[2];
        var url = line.split('|')[0].trim();
        if (line.indexOf('|') >= 0) keyValues(line.slice(line.indexOf('|') + 1));
        var name = (comma >= 0 ? info.slice(comma + 1).trim() : '') || attrs['tvg-name'] || url;
        var grp = attrs['group-title'] || group;
        if (/^https?:\/\//i.test(url) && !seen[url]) {
          seen[url] = 1;
          out.push({ name: name.replace(/\s*\(\d+[pi]\)/g, ''), url: url, logo: attrs['tvg-logo'] || null,
            group: grp ? grp.split(';')[0] : null,
            referer: head.referer || attrs['http-referrer'] || attrs['http-referer'] || null,
            ua: head.ua || attrs['http-user-agent'] || null, drm: drm });
        }
        reset();
      } else reset();
    }
    return out;
  }

  // ---------------------------------------------------------------- names

  var PRIORITY = ['trans tv', 'trans7', 'rcti', 'mnctv', 'gtv', 'sctv', 'antv', 'indosiar', 'tvone', 'btv', 'rtv',
    'cnn indonesia', 'cnbc indonesia'];
  var NATIONAL = PRIORITY.concat(['tvri', 'inews', 'metro tv', 'kompas tv', 'net', 'moji', 'mdtv', 'nusantara tv',
    'garuda tv', 'beritasatu', 'jak tv', 'tvri sport', 'tvri world']);
  var ALIAS = { 'tvri nasional': 'TVRI', 'tvri world': 'TVRI World', 'tvri sport': 'TVRI Sport', 'tv one': 'tvOne',
    'mdtv net': 'MDTV', 'rajawali tv': 'RTV', 'mnc tv': 'MNCTV', 'global tv': 'GTV', 'transtv': 'Trans TV', 'trans 7': 'Trans7',
    'trans 7 tv': 'Trans7', 'kompastv': 'Kompas TV', 'metrotv': 'Metro TV', 'moji tv': 'Moji', 'berita satu': 'BeritaSatu',
    'cnn indonesia': 'CNN Indonesia', 'tvone': 'tvOne', 'sctv': 'SCTV', 'rcti': 'RCTI', 'antv': 'ANTV', 'gtv': 'GTV',
    'inews': 'iNews', 'indosiar': 'Indosiar' };
  var TVRI_ID = { 'east java': 'Jawa Timur', 'central java': 'Jawa Tengah', 'west java': 'Jawa Barat',
    'east kalimantan': 'Kalimantan Timur', 'west kalimantan': 'Kalimantan Barat', 'south kalimantan': 'Kalimantan Selatan',
    'central kalimantan': 'Kalimantan Tengah', 'north sulawesi': 'Sulawesi Utara', 'south sulawesi': 'Sulawesi Selatan',
    'central sulawesi': 'Sulawesi Tengah', 'southeast sulawesi': 'Sulawesi Tenggara', 'west sulawesi': 'Sulawesi Barat',
    'north sumatra': 'Sumatera Utara', 'west sumatra': 'Sumatera Barat', 'south sumatra': 'Sumatera Selatan',
    'east nusa tenggara': 'Nusa Tenggara Timur', 'west nusa tenggara': 'Nusa Tenggara Barat', 'west papua': 'Papua Barat' };

  function own(map, key) { return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : null; }

  function cleanName(raw) {
    var n = String(raw).replace(/\s*\[[^\]]*\]/g, '').replace(/\s*\([^)]*\)\)?/g, '').replace(/\s+(HD|FHD|SD)\b/gi, '')
      .replace(/\s+cad$/i, '').replace(/\s+R\+$/i, '').trim();
    if (/^tvri /i.test(n)) { var r = own(TVRI_ID, n.slice(5).toLowerCase()); if (r) n = 'TVRI ' + r; }
    return own(ALIAS, n.toLowerCase()) || n || String(raw).trim();
  }

  // ---------------------------------------------------------------- free TV rule (built-in sources only)

  var RESTREAM = /workers\.dev|dens\.tv|masterplayer|sedot|maling|\.top(:|\/|$)|\.pw(:|\/|$)|cloudns|ngebutz|pages\.dev|tunnel\.my\.id|inisemar|beetv\.my\.id|biz\.id|nextgenz|ottc\.pro|lancartech|indihometv\.com|mikrofast|verspective/i;
  var PAID = /\b(hbo|cinemax|bein|spotv|champions tv|galaxy|hits|celestial|axn|dreamworks|cartoon network|disney|nat ?geo|fox(?! weather)|warner|vision ?prime|mola|nickelodeon|animax|sportstars|motogp|f1 tv|love nature)\b|\((v\+|r\+|maxstream|vidio|biz)\)/i;
  var FILE = /\.(mp4|mkv|avi|mov|webm|mp3|m4a)(\?|$)/i;
  function isFree(c) { return !FILE.test(c.url) && !c.drm && !RESTREAM.test(c.url) && !PAID.test(c.name); }

  // ---------------------------------------------------------------- film & series

  var FILM_GROUP = /(^|[^a-z])(film|films|movie|movies|sinema|cinema|bioskop|series|serial|drama|vod|tv\s*show|anime)([^a-z]|$)/;
  var SERIES_GROUP = /(^|[^a-z])(series|serial|drama|tv\s*show|anime)([^a-z]|$)/;
  var EPISODE = /\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b|\b(?:eps?|episode|ep)\s*\.?\s*(\d{1,3})\b/i;
  var NOISE = /\b(2160p|1080p|720p|480p|360p|4k|uhd|hdr|web-?dl|web-?rip|blu-?ray|bdrip|brrip|dvdrip|hdrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|aac|ac3|dts|10bit|sub(?:s|title)?\s*(?:indo|id|eng)?|indo|dubbed|dub|nf|amzn|extended|remastered|proper|repack)\b/gi;

  function parseTitle(raw) {
    var s = String(raw).trim().replace(/\.(mp4|mkv|avi|mov|webm|m4v|ts)$/i, '').replace(/\[[^\]]*\]|\{[^}]*\}/g, ' ').replace(/_/g, ' ');
    var season = null, episode = null, year = null, m = EPISODE.exec(s);
    if (m) {
      if (m[1]) { season = +m[1]; episode = +m[2]; } else if (m[3]) { season = +m[3]; episode = +m[4]; } else episode = +m[5];
      s = s.slice(0, m.index);
    }
    var re = /[\s.(\[_-]((?:19|20)\d{2})(?=[\s.)\]_-]|$)/g, padded = ' ' + s, last = null, y;
    while ((y = re.exec(padded))) last = y;
    if (last && padded.slice(0, last.index).trim()) { year = +last[1]; s = padded.slice(0, last.index); }
    s = s.replace(NOISE, ' ').replace(/\./g, ' ').replace(/[\s\-:(]+$/, '').replace(/\s+/g, ' ').trim();
    return { title: s || String(raw).trim(), year: year, season: season, episode: episode };
  }

  function isPixeldrain(url) { return /^https?:\/\/([^\/?#]*\.)?(pixeldrain\.(com|net)|pixeldra\.in)([:\/?#]|$)/i.test(url); }
  function isVodFile(c) {
    var u = c.url.split('?')[0].toLowerCase(), g = (c.group || '').toLowerCase();
    return g.indexOf('vod') >= 0 || /\.(mp4|mkv|avi|mov|webm)/.test(u) || /^vod /i.test(c.name) || g.indexOf('film on demand') >= 0;
  }
  function isLibrary(c) {
    if (isPixeldrain(c.url)) return true;
    var g = (c.group || '').toLowerCase();
    if (!g || !FILM_GROUP.test(g)) return false;
    var p = parseTitle(c.name);
    return isVodFile(c) || p.year !== null || p.episode !== null;
  }

  function library(channels) {
    var map = {}, order = [];
    channels.forEach(function (c) {
      if (c.drm || !isLibrary(c)) return;
      var p = parseTitle(c.name), series = SERIES_GROUP.test((c.group || '').toLowerCase()) || EPISODE.test(c.name);
      var key = (series ? 's:' : 'm:') + p.title.toLowerCase() + (series ? '' : '|' + (p.year || ''));
      if (!own(map, key)) { map[key] = { key: key, title: p.title, year: p.year, series: series, items: [] }; order.push(key); }
      map[key].items.push({ url: c.url, name: c.name, season: p.season, episode: p.episode });
    });
    return order.map(function (k) {
      var t = map[k];
      t.items.sort(function (a, b) { return (a.season || 0) - (b.season || 0) || (a.episode || 0) - (b.episode || 0); });
      return t;
    }).sort(function (a, b) { return a.title.toLowerCase() < b.title.toLowerCase() ? -1 : 1; });
  }

  // ---------------------------------------------------------------- live channels

  function has(s, words) { for (var i = 0; i < words.length; i++) if (s.indexOf(words[i]) >= 0) return true; return false; }

  function category(name, group) {
    var l = name.toLowerCase(), g = (group || '').toLowerCase();
    if (NATIONAL.indexOf(l) >= 0) return 'national';
    if (has(g, ['sport', 'worldcup', 'soccer', 'football', 'otomotif', 'motor']) || has(l, ['bein', 'spotv', 'sport', 'premier league', 'soccer', 'nba', 'golf', 'motogp'])) return 'sports';
    if (has(g, ['movie', 'cinema', 'hbo', 'film', 'bioskop']) || has(l, ['hbo', 'cinemax', 'bioskop', 'celestial', 'thrill', 'paramount'])) return 'movies';
    if (l.indexOf('tvri ') === 0) return 'tvri';
    if (has(g, ['kid', 'animation', 'anime', 'family']) || has(l, ['cartoon network', 'nickelodeon', 'nick jr', 'cbeebies', 'animax'])) return 'kids';
    if (has(g, ['news', 'business', 'berita']) || has(l, ['cnn', 'cnbc', 'bbc', 'al jazeera', 'bloomberg', 'cna'])) return 'news';
    if (has(g, ['documentary', 'knowledge', 'education']) || has(l, ['discovery', 'nat geo', 'national geographic', 'animal planet', 'history'])) return 'documentary';
    if (has(g, ['entertainment', 'series', 'comedy', 'hiburan']) || has(l, ['tvn', 'axn', 'warner', 'lifetime'])) return 'entertainment';
    if (has(g, ['music', 'radio']) || has(l, ['mtv', 'trace'])) return 'music';
    if (has(g, ['lifestyle', 'cooking', 'auto']) || has(l, ['food network', 'hgtv'])) return 'lifestyle';
    if (has(g, ['religi', 'christian', 'islam', 'dakwah']) || has(l, ['rodja', 'quran', 'madani', 'nabawi'])) return 'religious';
    if (!g || g === 'general' || g === 'undefined' || has(g, ['local', 'culture'])) return 'local';
    return 'other';
  }

  var BRANDS = ['sctv', 'indosiar', 'rcti', 'mnctv', 'antv', 'transtv', 'trans7', 'tvone', 'metrotv', 'kompastv', 'gtv'];
  /** A feed filed under one national brand whose address clearly belongs to another one. */
  function mismatched(name, url) {
    var own_ = name.toLowerCase().replace(/ /g, ''), u = url.split('?')[0].toLowerCase();
    if (BRANDS.indexOf(own_) < 0 || u.indexOf(own_) >= 0) return false;
    return BRANDS.some(function (b) { return b !== own_ && new RegExp('(^|[^a-z0-9])' + b + '([^a-z0-9]|$)').test(u); });
  }

  /** Lower is better. A web app can't send a Referer or User-Agent with a stream, so feeds that need one go last. */
  function streamRank(c, primary) {
    var u = c.url.toLowerCase(), r = 100;
    if (/\.mpd/.test(u)) r += 40;
    if (c.referer) r += 60;
    if (/cnnindonesia\.com|detik\.com|tvri\.go\.id|kompas\.tv/.test(u)) r -= 50;
    if (primary) r -= 10;
    if (/202\.150\.172\.59|dens\.tv.*\/h2(17|07)/.test(u)) r += 80;
    return r;
  }

  /** Live channels: one entry per channel (other feeds become backups), national ones first in the usual order. */
  function arrange(channels) {
    var map = {}, list = [];
    channels.forEach(function (c, i) {
      if (c.drm || isLibrary(c)) return;
      var name = cleanName(c.name);
      if (mismatched(name, c.url)) return;
      var cat = category(name, c.group);
      var squeezed = name.toLowerCase().replace(/\b(fhd|uhd|4k|hd|sd|backup|alt|server\s*\d+)\b/g, ' ').replace(/[^a-z0-9]/g, '') || name.toLowerCase();
      var key = squeezed + '|' + cat, e = own(map, key);
      if (!e) {
        var rank = NATIONAL.indexOf(name.toLowerCase());
        e = map[key] = { key: key, name: name, cat: cat, rank: rank < 0 ? 1000 : rank, logo: null, streams: [] };
        list.push(e);
      }
      if (!e.logo && c.logo && /^https?:/i.test(c.logo)) e.logo = c.logo;
      e.streams.push({ url: c.url, referer: c.referer, order: streamRank(c, c.primary) + i / 1e7 });
    });
    list.forEach(function (e) {
      e.streams.sort(function (a, b) { return a.order - b.order; });
      e.streams = e.streams.slice(0, 8).map(function (s) { return s.url; });
    });
    list.sort(function (a, b) {
      return CATS.indexOf(a.cat) - CATS.indexOf(b.cat) || a.rank - b.rank || (a.name.toLowerCase() < b.name.toLowerCase() ? -1 : a.name.toLowerCase() > b.name.toLowerCase() ? 1 : 0);
    });
    return list;
  }

  // ---------------------------------------------------------------- posters

  function tokens(s) {
    return String(s).toLowerCase().replace(/\(.*?\)/g, ' ').split(/[^a-z0-9À-ɏ]+/).filter(function (w) { return w && w !== 'the'; });
  }
  /** Titles share (nearly) all their words; a wrong poster is worse than none. */
  function titleMatches(query, title) {
    var q = tokens(query), t = tokens(title);
    if (!q.length || !t.length) return false;
    var common = q.filter(function (w) { return t.indexOf(w) >= 0; }).length;
    return common / Math.max(q.length, t.length) >= 0.6 || (common === q.length && t.length <= q.length + 2);
  }

  var api = { CATS: CATS, parseM3u: parseM3u, cleanName: cleanName, isFree: isFree, isLibrary: isLibrary, parseTitle: parseTitle,
    library: library, arrange: arrange, category: category, titleMatches: titleMatches };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.TahoeLib = api;
})(this);
