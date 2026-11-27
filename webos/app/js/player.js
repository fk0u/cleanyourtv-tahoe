/* The full-screen player: backup feeds, the no-signal slate with its tone, channel list and options panels,
 * previous channel, film resume. ES5. */
(function (root) {
  'use strict';
  var U = root.TahoeUi, E = root.TahoeEpg, Slate = root.TahoeSlate, Text = root.TahoeText;
  var KEY = U.KEY, $ = U.$, el = U.el, add = U.add, clear = U.clear;

  var app, video, playing = null, list = [], at = 0, source = 0, previous = null, open = false;
  var timer = 0, osdTimer = 0, watchdog = 0, slateTimer = 0, recentTimer = 0, lastTime = -1, stuck = 0;
  var failed = false, tries = 0, seconds = 0, panel = null, resumeAt = 0, position = 0, savedAt = 0, sound = null, audio = null, toneAt = {};

  function S() { return app.S; }

  function start(entry, entries, index) {
    saveResume(true); // leaving one episode for another keeps its place
    if (playing && !playing.vod && entry.key !== playing.key) previous = { entry: playing, list: list, at: at };
    list = entries; at = index; playing = entry; open = true; closePanel();
    document.body.className = 'playing';
    var good = U.store.get('good', {});
    source = Math.max(0, entry.streams.indexOf(good[entry.key])); // the feed that worked last time first
    tries = 0; position = 0; resumeAt = entry.vod ? app.resumeAt(entry) : 0;
    delete entry.fromStart;
    clearTimeout(recentTimer);
    if (!entry.vod) { U.store.set('last', entry.key); recentTimer = setTimeout(function () { if (open && playing === entry) app.watched(entry.key); }, 6000); }
    tune();
  }

  var attempts = 0;
  function tune() {
    failed = false; stuck = 0; lastTime = -1; clearTimeout(timer); clearInterval(slateTimer); silence();
    if (playing.vod && position > resumeAt) resumeAt = position; // a reconnected film goes on where it was
    $('slate').hidden = true;
    status(attempts ? Text.fill(S().backup, attempts + 1, playing.streams.length) : S().tune);
    showOsd();
    video.src = playing.streams[source];
    video.load(); play();
    timer = setTimeout(next, playing.vod ? 40000 : 15000);
  }
  function play() { var p = video.play(); if (p && p['catch']) p['catch'](function () { }); }

  function next() {
    if (!open || failed) return;
    if (attempts + 1 < playing.streams.length) { attempts++; source = (source + 1) % playing.streams.length; return tune(); }
    fail();
  }

  function fail() {
    failed = true; clearTimeout(timer); release(); status(''); closePanel();
    seconds = Slate.RETRY_SECONDS;
    $('slate').hidden = false;
    drawSlate(); $('osd').className = ''; $('osdTop').className = ''; chime(); // the slate names the channel itself
    clearInterval(slateTimer);
    slateTimer = setInterval(function () {
      if (!open || !failed || tries >= Slate.MAX_RETRIES) return clearInterval(slateTimer);
      if (--seconds <= 0) { tries++; if (tries >= Slate.MAX_RETRIES) { drawSlate(); return; } attempts = 0; return tune(); }
      drawSlate();
    }, 1000);
  }
  function drawSlate() {
    $('slateTitle').textContent = playing.name;
    $('slateText').textContent = Slate.headline(app.cfg.lang, tries);
    $('slateOthers').textContent = Slate.others(app.cfg.lang).join('   ·   ');
    $('slateRetry').textContent = tries >= Slate.MAX_RETRIES ? S().waiting : Text.fill(S().retryIn, seconds);
    $('slateFollow').textContent = Slate.follow(app.cfg.lang) + ' · ' + Slate.CHANNEL;
    $('slateKeys').textContent = S().slateKeys;
  }

  /** The test tone: once per channel per 10 minutes, a few cycles, silent as soon as the picture is back. */
  function chime() {
    var now = new Date().getTime(), Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx || (toneAt[playing.key] && now - toneAt[playing.key] < 600000)) return;
    toneAt[playing.key] = now;
    try {
      if (!audio) audio = new Ctx();
      var pcm = Slate.tone(), buffer = audio.createBuffer(1, pcm.length, Slate.RATE);
      buffer.getChannelData(0).set(pcm);
      sound = audio.createBufferSource(); sound.buffer = buffer; sound.loop = true; sound.connect(audio.destination);
      sound.start(0); sound.stop(audio.currentTime + 11 * Slate.REPEATS);
    } catch (e) { sound = null; }
  }
  function silence() { if (sound) { try { sound.stop(0); } catch (e) { } sound = null; } }

  function release() { video.removeAttribute('src'); video.load(); }
  function status(t) { $('status').textContent = t; }

  var osdFor = null;
  function showOsd() {
    if (!playing) return;
    $('osd').className = 'show'; $('osdTop').className = 'show';
    $('osdBadge').textContent = playing.vod ? S().film : 'LIVE';
    $('osdBadge').className = playing.vod ? 'film' : 'live';
    $('osdClock').textContent = U.clock(new Date().getTime());
    if (osdFor !== playing.key) { // the channel's logo, or its initials
      osdFor = playing.key;
      var box = clear($('osdLogo')), mono = el('span', 'mono', U.monogram(playing.name));
      box.appendChild(mono); U.picture(playing.logos || (playing.poster ? [playing.poster] : []), box, mono);
    }
    $('osdNum').textContent = playing.vod ? '' : String(at + 1);
    $('osdName').textContent = playing.name;
    var nn = playing.vod ? [null, null] : E.nowNext(app.guide[playing.key], new Date().getTime());
    $('osdNow').textContent = nn[0] ? U.clock(nn[0][0]) + '  ' + nn[0][2] : (playing.cat ? S().cats[playing.cat] : S().film);
    $('osdNext').textContent = nn[1] ? S().next + ' ' + U.clock(nn[1][0]) + '  ' + nn[1][2] : '';
    var part = 0;
    if (nn[0]) part = (new Date().getTime() - nn[0][0]) / (nn[0][1] - nn[0][0]);
    if (playing.vod && video.duration) { part = video.currentTime / video.duration; $('osdNext').textContent = U.span(video.currentTime) + ' / ' + U.span(video.duration); }
    $('osdBar').hidden = !part;
    $('osdFill').style.width = Math.round(Math.max(0, Math.min(1, part)) * 100) + '%';
    $('osdHint').textContent = playing.vod ? '' : S().playerHint;
    clearTimeout(osdTimer); osdTimer = setTimeout(function () { if (!failed) $('osd').className = ''; $('osdTop').className = ''; }, 5500);
  }

  function zap(d) { if (list.length < 2) return showOsd(); var i = (at + d + list.length) % list.length; attempts = 0; start(list[i], list, i); }
  function recall() { if (previous) { var p = previous; attempts = 0; start(p.entry, p.list, p.at); } }

  function saveResume(force) {
    if (!open || !playing || !playing.vod || !video.duration || !isFinite(video.duration)) return;
    var now = new Date().getTime();
    if (video.currentTime > 0) position = video.currentTime;
    if (!(video.currentTime > 0) || (!force && now - savedAt < 5000)) return;
    savedAt = now; app.progress(playing, video.currentTime, video.duration);
  }

  function stop() {
    saveResume(true);
    clearTimeout(timer); clearTimeout(recentTimer); clearInterval(slateTimer); silence(); closePanel();
    var last = playing; open = false; failed = false; playing = null; release();
    document.body.className = ''; $('osdTop').className = ''; osdFor = null;
    app.stopped(last);
  }

  // ---------------------------------------------------------------- panels

  function closePanel() { panel = null; if ($('panel')) $('panel').hidden = true; }
  function openPanel(title, rows, index, side) {
    panel = { rows: rows, at: index, side: side }; $('panel').hidden = false; $('panel').className = side; $('panelTitle').textContent = title; drawPanel();
  }
  function drawPanel() {
    var box = clear($('panelList')), from = Math.max(0, Math.min(panel.at - 5, panel.rows.length - 11));
    panel.rows.slice(from, from + 11).forEach(function (r, k) {
      var i = from + k, d = el('div', 'item' + (i === panel.at ? ' focus' : ''));
      add(d, el('span', 'title', r.label), r.sub ? el('span', 'sub', r.sub) : null);
      d.onclick = function () { panel.at = i; r.ok(); };
      box.appendChild(d);
    });
  }
  function channelPanel() {
    var now = new Date().getTime();
    openPanel(S().channels, list.map(function (e, i) {
      var nn = E.nowNext(app.guide[e.key], now);
      return { label: (i + 1) + '  ' + e.name, sub: nn[0] ? nn[0][2] : '', ok: function () { attempts = 0; start(list[i], list, i); } };
    }), at, 'left');
  }
  function optionPanel(index) {
    var rows = [];
    if (playing.streams.length > 1) rows.push({ label: Text.fill(S().source, source + 1, playing.streams.length), ok: function () { source = (source + 1) % playing.streams.length; attempts = 0; var keep = panel.at; tune(); optionPanel(keep); } });
    if (!playing.vod) rows.push({ label: app.isFav(playing.key) ? S().favDel : S().favAdd, ok: function () { app.toggleFav(playing.key); optionPanel(panel.at); } });
    rows.push({ label: Text.fill(S().aspect, S()['aspect_' + (app.cfg.aspect || 'fit')]), ok: function () { cycleAspect(); optionPanel(panel.at); } });
    var audioTracks = video.audioTracks, texts = video.textTracks;
    if (audioTracks && audioTracks.length > 1) rows.push({ label: Text.fill(S().audio, trackName(audioTracks, enabledAudio(audioTracks))), ok: function () { nextAudio(audioTracks); optionPanel(panel.at); } });
    if (texts && texts.length) rows.push({ label: Text.fill(S().subtitles, shownText(texts) < 0 ? S().off : trackName(texts, shownText(texts))), ok: function () { nextText(texts); optionPanel(panel.at); } });
    rows.push({ label: Text.fill(S().sleep, app.sleepLabel()), ok: function () { app.cycleSleep(); optionPanel(panel.at); } });
    if (!playing.vod && app.guide[playing.key]) rows.push({ label: S().schedule, ok: function () { schedulePanel(); } });
    rows.push({ label: S().exitPlayer, ok: stop });
    openPanel(S().options, rows, Math.min(index || 0, rows.length - 1), 'right');
  }
  function schedulePanel() {
    var now = new Date().getTime();
    var rows = (app.guide[playing.key] || []).filter(function (p) { return p[1] > now; }).map(function (p) {
      return { label: U.clock(p[0]) + '  ' + p[2], sub: app.isReminded(playing.key, p[0]) ? '⏰ ' + S().reminded : '', ok: function () { if (p[0] > now) { app.toggleReminder(playing, p); var keep = panel.at; schedulePanel(); panel.at = keep; drawPanel(); } } };
    });
    openPanel(S().schedule, rows, 0, 'right');
  }

  // ---------------------------------------------------------------- picture shape, audio and subtitles

  var FIT = { fit: 'contain', fill: 'fill', zoom: 'cover' };
  function applyAspect() { video.style.objectFit = FIT[app.cfg.aspect] || 'contain'; }
  function cycleAspect() {
    var order = ['fit', 'fill', 'zoom'];
    app.cfg.aspect = order[(order.indexOf(app.cfg.aspect) + 1) % order.length];
    app.saveCfg(); applyAspect();
  }
  function trackName(tracks, i) { var t = tracks[i]; return t ? (t.label || t.language || Text.fill(S().track, i + 1)) : '-'; }
  function enabledAudio(tracks) { for (var i = 0; i < tracks.length; i++) if (tracks[i].enabled) return i; return 0; }
  function nextAudio(tracks) { var n = (enabledAudio(tracks) + 1) % tracks.length; for (var i = 0; i < tracks.length; i++) tracks[i].enabled = i === n; }
  function shownText(tracks) { for (var i = 0; i < tracks.length; i++) if (tracks[i].mode === 'showing') return i; return -1; }
  function nextText(tracks) { var n = shownText(tracks) + 1; for (var i = 0; i < tracks.length; i++) tracks[i].mode = i === n ? 'showing' : 'disabled'; } // past the last one: off

  var digits = '', digitTimer = 0;
  function number(d) {
    if (d === 0 && !digits) return recall();
    digits = (digits + d).slice(-4); app.toast(digits); clearTimeout(digitTimer);
    digitTimer = setTimeout(function () { var n = +digits - 1; digits = ''; if (open && n >= 0 && n < list.length) { attempts = 0; start(list[n], list, n); } }, 1400);
  }

  function key(k) {
    if (panel) {
      if (k === KEY.BACK || (panel.side === 'left' && k === KEY.RIGHT) || (panel.side === 'right' && k === KEY.LEFT)) return closePanel();
      if (k === KEY.UP || k === KEY.DOWN) { panel.at = (panel.at + (k === KEY.UP ? -1 : 1) + panel.rows.length) % panel.rows.length; return drawPanel(); }
      if (k === KEY.CH_UP || k === KEY.CH_DOWN) { panel.at = Math.max(0, Math.min(panel.rows.length - 1, panel.at + (k === KEY.CH_UP ? -11 : 11))); return drawPanel(); }
      if (k === KEY.OK && panel.rows[panel.at]) return panel.rows[panel.at].ok();
      return;
    }
    if (k === KEY.BACK || k === KEY.STOP) return stop();
    if (k === KEY.CH_UP || k === KEY.UP) return zap(playing.vod ? -1 : 1);
    if (k === KEY.CH_DOWN || k === KEY.DOWN) return zap(playing.vod ? 1 : -1);
    if (k === KEY.OK) {
      if (failed) { tries = 0; attempts = 0; delete toneAt[playing.key]; return tune(); }
      if (playing.vod) { if (video.paused) play(); else video.pause(); }
      return showOsd();
    }
    if (playing.vod && (k === KEY.LEFT || k === KEY.RIGHT)) { video.currentTime = Math.max(0, video.currentTime + (k === KEY.LEFT ? -10 : 10)); return showOsd(); }
    if (k === KEY.LEFT) return channelPanel();
    if (k === KEY.RIGHT || k === KEY.GREEN) return optionPanel(0);
    if (k === KEY.YELLOW) return recall();
    if (k === KEY.RED && !playing.vod) return app.toggleFav(playing.key);
    if (k === KEY.PLAY) return play();
    if (k === KEY.PAUSE) return video.pause();
    if (k >= 48 && k <= 57) return number(k - 48);
    showOsd();
  }

  function init(a) {
    app = a; video = $('video'); applyAspect();
    video.addEventListener('playing', function () {
      clearTimeout(timer); clearInterval(slateTimer); silence(); status(''); $('slate').hidden = true; failed = false; attempts = 0; tries = 0;
      if (playing) { var good = U.store.get('good', {}); good[playing.key] = playing.streams[source]; U.store.set('good', good); }
    });
    video.addEventListener('loadedmetadata', function () { if (resumeAt > 0 && resumeAt < video.duration - 5) video.currentTime = resumeAt; resumeAt = 0; });
    video.addEventListener('timeupdate', function () { saveResume(false); });
    video.addEventListener('waiting', function () { if (open && !failed) status('…'); });
    video.addEventListener('error', function () { if (video.getAttribute('src')) next(); });
    video.addEventListener('ended', function () {
      if (!open) return;
      if (!playing.vod) return next();
      saveResume(true);
      if (at + 1 < list.length) zap(1); else stop();
    });
    watchdog = setInterval(function () { // a live picture that stops moving for 20 s is reconnected on the next feed
      if (!open || failed || video.paused || !video.currentTime) return;
      if (video.currentTime === lastTime) { if (++stuck >= 4) { stuck = 0; if (!playing.vod && playing.streams.length > 1) source = (source + 1) % playing.streams.length; tune(); } }
      else stuck = 0;
      lastTime = video.currentTime;
    }, 5000);
    document.addEventListener('visibilitychange', function () { // another app on top: let go of the stream
      if (!open) return;
      if (document.hidden) { saveResume(true); clearTimeout(timer); clearInterval(slateTimer); silence(); if (playing.vod) video.pause(); else release(); }
      else if (playing.vod) play(); else { attempts = 0; tune(); }
    });
    video.onclick = function () { if (open) showOsd(); };
  }

  root.TahoePlayer = { init: init, start: function (e, l, i) { attempts = 0; start(e, l, i); }, stop: stop, key: key, isOpen: function () { return open; }, current: function () { return playing; }, playingPicture: function () { return open && !failed && !video.paused; } };
})(this);
