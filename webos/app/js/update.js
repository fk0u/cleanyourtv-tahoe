/* Updates over the air: the newest release's code (scripts and styles listed in its webos/app/manifest.json) is fetched from
 * the official repository at that release's tag, every file checked against the SHA-256 in the manifest, and kept in storage
 * for boot.js to start next time. Images, fonts and boot.js itself only change with a new package from a computer. ES5. */
(function (root) {
  'use strict';
  var D = root.TahoeData;
  var RAW = 'https://raw.githubusercontent.com/fk0u/tahoe-tv/';
  var PREFIX = 'tahoe.ota.f.';

  // ---------------------------------------------------------------- SHA-256 of a string's UTF-8 bytes

  var K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
  function sha256(text) {
    var s = unescape(encodeURIComponent(text)), n = s.length, words = [], i;
    for (i = 0; i < n; i++) words[i >> 2] |= (s.charCodeAt(i) & 0xff) << (24 - (i % 4) * 8);
    words[n >> 2] |= 0x80 << (24 - (n % 4) * 8);
    var total = ((n + 8) >> 6) * 16 + 16;
    for (i = words.length; i < total; i++) words[i] = words[i] | 0;
    words[total - 1] = n * 8;
    var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19], w = [];
    function rot(x, r) { return (x >>> r) | (x << (32 - r)); }
    for (var b = 0; b < total; b += 16) {
      var a = h[0], c1 = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
      for (i = 0; i < 64; i++) {
        if (i < 16) w[i] = words[b + i] | 0;
        else {
          var x = w[i - 15], y = w[i - 2];
          w[i] = (((rot(x, 7) ^ rot(x, 18) ^ (x >>> 3)) + w[i - 7]) | 0) + (((rot(y, 17) ^ rot(y, 19) ^ (y >>> 10)) + w[i - 16]) | 0) | 0;
        }
        var t1 = (hh + (rot(e, 6) ^ rot(e, 11) ^ rot(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
        var t2 = ((rot(a, 2) ^ rot(a, 13) ^ rot(a, 22)) + ((a & c1) ^ (a & c) ^ (c1 & c))) | 0;
        hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = c1; c1 = a; a = (t1 + t2) | 0;
      }
      h[0] = (h[0] + a) | 0; h[1] = (h[1] + c1) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
      h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
    }
    return h.map(function (v) { return ('00000000' + (v >>> 0).toString(16)).slice(-8); }).join('');
  }

  // ---------------------------------------------------------------- check, download

  function boot() { return root.TahoeBoot || { boot: 1, version: '0.0.0', source: 'package' }; }
  function current() { return boot().version; }
  function newer(a, b) { return D.newer(a, b); }

  /** done(error, info): info is null when up to date, else { version, notes, manifest, needsPackage }. */
  function check(done) {
    D.latest(function (rel) {
      if (!rel) return done('offline', null);
      if (!newer(rel.version, current())) return done(null, null);
      D.get(RAW + 'v' + rel.version + '/webos/app/manifest.json', function (text) {
        var m = null;
        try { m = JSON.parse(text); } catch (e) { m = null; }
        if (!m || !m.scripts || !m.files) return done(null, { version: rel.version, notes: rel.notes, manifest: null, needsPackage: true });
        done(null, { version: rel.version, notes: rel.notes, manifest: m, needsPackage: (m.boot || 1) > boot().boot });
      }, 30000);
    });
  }

  /** Downloads and checks every file, then stores them for the next start. progress(n, total); done(error). */
  function download(info, progress, done) {
    var m = info.manifest, list = m.styles.concat(m.scripts), got = {}, i = 0;
    function next() {
      if (i >= list.length) return save();
      var p = list[i];
      progress(i, list.length);
      D.get(RAW + 'v' + info.version + '/webos/app/' + p, function (text) {
        if (text === null) return done('network');
        if (sha256(text) !== m.files[p]) return done('checksum ' + p);
        got[p] = text; i++; next();
      }, 30000);
    }
    function save() {
      try {
        for (var k = localStorage.length - 1; k >= 0; k--) { var key = localStorage.key(k); if (key && key.indexOf(PREFIX) === 0) localStorage.removeItem(key); }
        localStorage.removeItem('tahoe.ota.meta');
        list.forEach(function (p) { localStorage.setItem(PREFIX + p, got[p]); });
        localStorage.setItem('tahoe.ota.meta', JSON.stringify({ version: info.version, boot: m.boot || 1, styles: m.styles, scripts: m.scripts, at: new Date().getTime() }));
        localStorage.removeItem('tahoe.ota.pending');
      } catch (e) {
        for (var j = localStorage.length - 1; j >= 0; j--) { var key2 = localStorage.key(j); if (key2 && key2.indexOf(PREFIX) === 0) localStorage.removeItem(key2); }
        localStorage.removeItem('tahoe.ota.meta');
        return done('storage');
      }
      progress(list.length, list.length);
      done(null);
    }
    next();
  }

  /** The version that will start next time, if a downloaded one is waiting. */
  function waiting() {
    var meta = null;
    try { meta = JSON.parse(localStorage.getItem('tahoe.ota.meta')); } catch (e) { }
    return meta && meta.version !== current() ? meta.version : null;
  }
  function bad() { try { return localStorage.getItem('tahoe.ota.bad'); } catch (e) { return null; } }

  var api = { sha256: sha256, check: check, download: download, waiting: waiting, bad: bad, current: current, source: function () { return boot().source; }, restart: function () { location.reload(); }, reset: function () { boot().reset ? boot().reset() : location.reload(); } };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.TahoeUpdate = api;
})(this);
