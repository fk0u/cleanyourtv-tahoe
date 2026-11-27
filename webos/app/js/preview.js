/* The immersive background: the focused channel plays silently behind the pages (after a short pause, so moving through a
 * list doesn't start every channel), or the focused film's poster. Only one video decoder is used at a time: the preview is
 * let go before the player starts. ES5. */
(function (root) {
  'use strict';
  var U = root.TahoeUi, $ = U.$;
  var app, video, wanted = null, current = null, timer = 0, poster = '';

  function body(name, on) { var c = document.body.classList; if (on) c.add(name); else c.remove(name); }

  function show(entry) {
    if (!app.cfg.preview || !entry || !entry.streams || entry.vod || document.hidden) return stop();
    setPoster('');
    if (current && current.key === entry.key) return;
    clearTimeout(timer); wanted = entry;
    timer = setTimeout(function () {
      if (wanted !== entry || root.TahoePlayer.isOpen()) return;
      var good = U.store.get('good', {})[entry.key];
      current = entry;
      video.src = good && entry.streams.indexOf(good) >= 0 ? good : entry.streams[0];
      video.load();
      var p = video.play(); if (p && p['catch']) p['catch'](function () { });
    }, 1300);
  }

  function stop() {
    clearTimeout(timer); wanted = null;
    if (current) { current = null; video.removeAttribute('src'); video.load(); }
    body('previewing', false);
  }

  /** A film's poster as the backdrop instead of a channel ('' = none). */
  function setPoster(url) {
    if (url) stop();
    if (url === poster) return;
    poster = url;
    var b = $('backdrop');
    if (url) { b.style.backgroundImage = 'url("' + url.replace(/"/g, '%22') + '")'; body('backdropped', true); }
    else body('backdropped', false);
  }

  function init(a) {
    app = a; video = $('preview'); video.muted = true; video.volume = 0;
    video.addEventListener('playing', function () { if (current) body('previewing', true); });
    video.addEventListener('error', function () { if (current) { current = null; body('previewing', false); } });
    document.addEventListener('visibilitychange', function () { if (document.hidden) stop(); });
  }

  root.TahoePreview = { init: init, show: show, stop: stop, poster: setPoster, clear: function () { stop(); setPoster(''); } };
})(this);
