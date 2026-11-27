/* XMLTV programme guide, matched like :core's Epg (tvg-id first, then the normalised channel name). Parsed in small steps so
 * a 7 MB guide doesn't freeze a 1 GB TV; only the channels in the list and a window around now are kept. ES5. */
(function (root) {
  'use strict';
  var PLACEHOLDER = /^\s*(jadwal belum tersedia|no (programme|program|information)( available)?|tidak ada (jadwal|informasi))\s*$/i;

  function norm(s) { return String(s).toLowerCase().replace(/\b(hd|sd|fhd|uhd|4k)\b/g, '').replace(/\(.*?\)|\[.*?\]/g, '').replace(/[^a-z0-9]/g, ''); }
  function text(s) {
    return s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;|&#39;/g, "'").replace(/&amp;/g, '&').trim();
  }
  /** "20261127080000 +0700" → epoch milliseconds (NaN when unreadable). */
  function time(s) {
    var m = /^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?\s*([+-])?(\d\d)?(\d\d)?/.exec(s || '');
    if (!m) return NaN;
    var t = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
    if (m[7]) t -= (m[7] === '+' ? 1 : -1) * ((+m[8] || 0) * 60 + (+m[9] || 0)) * 60000;
    return t;
  }

  /** What to look for: each channel's tvg-ids and name, pointing at the channel keys that want that guide. */
  function wanted(entries) {
    var map = {};
    function add(k, key) { if (!k) return; var l = Object.prototype.hasOwnProperty.call(map, k) ? map[k] : (map[k] = []); if (l.indexOf(key) < 0) l.push(key); }
    entries.forEach(function (e) {
      (e.ids || []).forEach(function (id) { add('id:' + id.toLowerCase().split('@')[0], e.key); });
      add('n:' + norm(e.name), e.key);
    });
    return map;
  }

  /** A resumable parser: call step(count) until it returns true, then result(). */
  function parser(xml, want, from, to) {
    var channels = {}, out = {}, pos = 0, phase = 0;
    function lookup(k) { return Object.prototype.hasOwnProperty.call(want, k) ? want[k] : null; }
    function keysFor(id, names) {
      var hit = lookup('id:' + id.toLowerCase().split('@')[0]), i;
      for (i = 0; !hit && i < names.length; i++) hit = lookup('n:' + norm(names[i]));
      return hit || lookup('n:' + norm(id.replace(/\.[a-z]{2}(@.*)?$/i, '')));
    }
    function attr(head, name) { var m = new RegExp('\\s' + name + '="([^"]*)"').exec(head); return m ? text(m[1]) : null; }
    function step(count) {
      var n = 0;
      while (n++ < count) {
        if (phase === 0) { // <channel> entries come before the programmes
          var c = xml.indexOf('<channel ', pos), p = xml.indexOf('<programme', pos);
          if (c < 0 || (p >= 0 && p < c)) { phase = 1; continue; }
          var end = xml.indexOf('</channel>', c); if (end < 0) { phase = 1; continue; }
          var block = xml.slice(c, end), id = attr(block, 'id'), names = [], m, re = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g;
          while ((m = re.exec(block))) names.push(text(m[1]));
          if (id) { var k = keysFor(id, names); if (k) channels[id] = k; }
          pos = end + 10;
          continue;
        }
        var s = xml.indexOf('<programme', pos);
        if (s < 0) return true;
        var close = xml.indexOf('</programme>', s);
        if (close < 0) return true;
        pos = close + 12;
        var gt = xml.indexOf('>', s), head = xml.slice(s, gt), ch = attr(head, 'channel');
        if (ch === null) continue;
        var keys = Object.prototype.hasOwnProperty.call(channels, ch) ? channels[ch] : lookup('id:' + ch.toLowerCase().split('@')[0]);
        if (!keys) continue;
        var start = time(attr(head, 'start')), stop = time(attr(head, 'stop'));
        if (!(stop > from && start < to)) continue;
        var t = /<title[^>]*>([\s\S]*?)<\/title>/.exec(xml.slice(gt, close));
        var title = t ? text(t[1]) : '';
        if (!title || PLACEHOLDER.test(title)) continue;
        for (var i = 0; i < keys.length; i++) (out[keys[i]] || (out[keys[i]] = [])).push([start, stop, title.slice(0, 120)]);
      }
      return false;
    }
    function result() {
      var r = {}, k;
      for (k in out) {
        var l = out[k].sort(function (a, b) { return a[0] - b[0]; }), clean = [];
        for (var i = 0; i < l.length && clean.length < 60; i++) if (!clean.length || l[i][0] >= clean[clean.length - 1][1]) clean.push(l[i]);
        r[k] = clean;
      }
      return r;
    }
    return { step: step, result: result };
  }

  /** Several guides: for a channel both know, the earlier guide wins. */
  function merge(target, more) { for (var k in more) if (!target[k] || !target[k].length) target[k] = more[k]; return target; }

  /** [now, next] programmes at [t]; either may be null. */
  function nowNext(list, t) {
    var now = null, next = null;
    for (var i = 0; list && i < list.length; i++) {
      if (list[i][1] <= t) continue;
      if (list[i][0] <= t) now = list[i]; else { next = list[i]; break; }
    }
    return [now, next];
  }

  var api = { norm: norm, time: time, wanted: wanted, parser: parser, merge: merge, nowNext: nowNext };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.TahoeEpg = api;
})(this);
