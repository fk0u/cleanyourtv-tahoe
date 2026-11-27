/* Starts Tahoe TV. Runs the newest code downloaded over the air (js/update.js) when there is a complete, working copy of it,
 * otherwise the code inside this package. This file is never replaced over the air, so the app can always fall back: an
 * update that does not finish starting within 20 seconds, or crashed the last time, is dropped. ES5. */
(function () {
  'use strict';
  var BOOT = 1;
  // Written by webos/tools/manifest.js from gradle.properties and the file list; do not edit by hand.
  var BUNDLED = '3.8.8';
  var BUNDLE = {"styles":["style.css"],"scripts":["js/lib.js","js/epg.js","js/slate.js","js/resume.js","js/i18n.js","js/ui.js","js/shell.js","js/data.js","js/update.js","js/preview.js","js/player.js","js/screens.js","js/setup.js","js/app.js"]};

  var PREFIX = 'tahoe.ota.f.';
  function get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function put(k, v) { try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) { } }
  function newer(a, b) {
    var x = String(a).replace(/^v/, '').split(/[.-]/), y = String(b).replace(/^v/, '').split(/[.-]/);
    for (var i = 0; i < 3; i++) { var d = (+x[i] || 0) - (+y[i] || 0); if (d) return d > 0; }
    return false;
  }
  /** Forgets the downloaded code (the packaged one stays). */
  function drop() {
    try { for (var i = localStorage.length - 1; i >= 0; i--) { var k = localStorage.key(i); if (k && k.indexOf(PREFIX) === 0) localStorage.removeItem(k); } } catch (e) { }
    put('tahoe.ota.meta', null); put('tahoe.ota.pending', null);
  }

  var meta = null;
  try { meta = JSON.parse(get('tahoe.ota.meta')); } catch (e) { meta = null; }
  if (meta && get('tahoe.ota.pending') === meta.version) { put('tahoe.ota.bad', meta.version); drop(); meta = null; } // it never finished starting
  // installed from a computer with a package at least as new, or the update needs a newer starter than this one
  if (meta && (!newer(meta.version, BUNDLED) || (meta.boot || 1) > BOOT)) { drop(); meta = null; }
  if (meta) {
    var files = (meta.styles || []).concat(meta.scripts || []);
    for (var i = 0; i < files.length; i++) if (get(PREFIX + files[i]) === null) { drop(); meta = null; break; }
  }

  var guard = 0;
  window.TahoeBoot = {
    boot: BOOT, bundled: BUNDLED, version: meta ? meta.version : BUNDLED, source: meta ? 'ota' : 'package',
    /** Called by the app once it has started: the update in use is good. */
    ready: function () { clearTimeout(guard); put('tahoe.ota.pending', null); },
    /** Back to the packaged version. */
    reset: function () { drop(); location.reload(); },
    newer: newer
  };

  var head = document.head || document.getElementsByTagName('head')[0];
  if (meta) {
    put('tahoe.ota.pending', meta.version);
    guard = setTimeout(function () { put('tahoe.ota.bad', meta.version); drop(); location.reload(); }, 20000);
    meta.styles.forEach(function (p) { var s = document.createElement('style'); s.textContent = get(PREFIX + p); head.appendChild(s); });
  } else {
    BUNDLE.styles.forEach(function (p) { var l = document.createElement('link'); l.rel = 'stylesheet'; l.href = p; head.appendChild(l); });
  }

  function scripts() {
    if (meta) {
      meta.scripts.forEach(function (p) { var s = document.createElement('script'); s.text = get(PREFIX + p) + '\n//# sourceURL=ota/' + p; document.body.appendChild(s); });
    } else {
      BUNDLE.scripts.forEach(function (p) { var s = document.createElement('script'); s.src = p; s.async = false; document.body.appendChild(s); });
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scripts); else scripts();
})();
