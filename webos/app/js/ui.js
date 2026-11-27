/* Small UI toolkit for the remote: elements, storage, pictures, row-based focus (Nav) and a virtual grid. ES5. */
(function (root) {
  'use strict';
  var KEY = { OK: 13, LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, BACK: 461, ESC: 27, BKSP: 8, CH_UP: 33, CH_DOWN: 34, RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, PLAY: 415, PAUSE: 19, STOP: 413 };

  function $(i) { return document.getElementById(i); }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined && text !== null) e.textContent = text; return e; }
  function add(parent) { for (var i = 1; i < arguments.length; i++) if (arguments[i]) parent.appendChild(arguments[i]); return parent; }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }
  function pad(n) { return n < 10 ? '0' + n : String(n); }
  function clock(ms) { var d = new Date(ms); return pad(d.getHours()) + '.' + pad(d.getMinutes()); }
  function span(seconds) { var s = Math.max(0, Math.floor(seconds)), h = Math.floor(s / 3600); return (h ? h + ':' : '') + pad(Math.floor(s % 3600 / 60)) + ':' + pad(s % 60); }

  var store = {
    get: function (k, d) { try { var v = localStorage.getItem('tahoe.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('tahoe.' + k, JSON.stringify(v)); return true; } catch (e) { return false; } }
  };

  function monogram(name) {
    var w = String(name).replace(/[^A-Za-z0-9 ]/g, ' ').trim().split(/\s+/);
    return (w.length > 1 ? w[0].charAt(0) + w[1].charAt(0) : (w[0] || '?').slice(0, 3)).toUpperCase();
  }

  var good = {}, bad = {};
  /** The first of [urls] that loads replaces the text placeholder; one already seen shows at once (no flicker while moving). */
  function picture(urls, box, fallback) {
    urls = (urls || []).filter(function (u) { return u && !bad[u]; });
    var url = urls.filter(function (u) { return good[u]; })[0] || urls[0];
    if (!url) return;
    var img = new Image();
    function show() { box.insertBefore(img, box.firstChild); if (fallback && fallback.parentNode === box) box.removeChild(fallback); }
    img.onload = function () { if (!good[url]) { good[url] = 1; show(); } };
    img.onerror = function () { bad[url] = 1; delete good[url]; if (img.parentNode) { box.removeChild(img); if (fallback) box.appendChild(fallback); } picture(urls, box, fallback); };
    img.src = url;
    if (good[url]) show();
  }

  /** Focus over rows of elements. Elements inside a ".track" scroll sideways; the [page] element scrolls vertically. */
  function Nav(page, viewHeight) { this.page = page; this.viewHeight = viewHeight || 940; this.rows = []; this.r = 0; this.c = 0; this.shown = false; }
  Nav.prototype.add = function (row, node, ok, extra) {
    var self = this;
    (this.rows[row] || (this.rows[row] = [])).push({ el: node, ok: ok, extra: extra || {} });
    node.onclick = function () {
      for (var r = 0; r < self.rows.length; r++) for (var c = 0; self.rows[r] && c < self.rows[r].length; c++) if (self.rows[r][c].el === node) { self.r = r; self.c = c; }
      if (self.onPointer) self.onPointer();
      self.focus(true); if (ok) ok();
    };
  };
  Nav.prototype.reset = function () { this.rows = []; };
  Nav.prototype.compact = function () { this.rows = this.rows.filter(function (r) { return r && r.length; }); };
  Nav.prototype.current = function () { var row = this.rows[this.r]; return row ? row[this.c] : null; };
  Nav.prototype.focusId = function (id) {
    for (var r = 0; r < this.rows.length; r++) for (var c = 0; c < this.rows[r].length; c++) if (this.rows[r][c].extra.id === id) { this.r = r; this.c = c; return true; }
    return false;
  };
  Nav.prototype.focus = function (show) {
    this.shown = show;
    this.compact();
    this.r = Math.max(0, Math.min(this.r, this.rows.length - 1));
    if (!this.rows.length) return;
    this.c = Math.max(0, Math.min(this.c, this.rows[this.r].length - 1));
    this.rows.forEach(function (row) { row.forEach(function (i) { i.el.classList.remove('focus'); }); });
    var item = this.current();
    if (show) item.el.classList.add('focus');
    this.scroll(item.el);
    if (show && item.extra.onFocus) item.extra.onFocus();
  };
  Nav.prototype.scroll = function (node) {
    var track = node.parentNode;
    if (track && track.classList.contains('track')) {
      var view = track.parentNode.clientWidth, x = node.offsetLeft, over = x + node.offsetWidth + 40 - view;
      track.style.transform = 'translateX(' + (over > 0 ? -Math.min(over + (node.offsetWidth / 2), x - 8) : 0) + 'px)';
    }
    if (!this.page) return;
    var y = 0, n = node;
    while (n && n !== this.page) { y += n.offsetTop; n = n.offsetParent; }
    var shift = this.r === 0 ? 0 : Math.max(0, y + node.offsetHeight + 60 - this.viewHeight);
    this.page.style.transform = 'translateY(' + (-shift) + 'px)';
  };
  /** true = handled; 'left' / 'up' = the focus is already at that edge. */
  Nav.prototype.key = function (k) {
    if (!this.rows.length) return k === KEY.LEFT ? 'left' : k === KEY.UP ? 'up' : false;
    if (k === KEY.UP) { if (this.r === 0) return 'up'; this.r--; this.c = Math.min(this.c, this.rows[this.r].length - 1); }
    else if (k === KEY.DOWN) { if (this.r + 1 >= this.rows.length) return true; this.r++; this.c = Math.min(this.c, this.rows[this.r].length - 1); }
    else if (k === KEY.LEFT) { if (this.c === 0) return 'left'; this.c--; }
    else if (k === KEY.RIGHT) { if (this.c + 1 >= this.rows[this.r].length) return true; this.c++; }
    else if (k === KEY.OK) { var i = this.current(); if (i && i.ok) i.ok(); return true; }
    else return false;
    this.focus(true);
    return true;
  };

  /** A grid that only builds what is visible, for hundreds of channels. */
  function Grid(box, cols, rows, tile, ok) { this.box = box; this.cols = cols; this.rows = rows; this.tile = tile; this.ok = ok; this.items = []; this.at = 0; this.top = 0; this.shown = false; }
  Grid.prototype.set = function (items, keep) {
    this.items = items;
    if (keep === undefined) { this.at = 0; this.top = 0; return; }
    for (var i = 0; i < items.length; i++) if (items[i].key === keep) { this.at = i; return; }
    this.at = Math.max(0, Math.min(this.at, items.length - 1));
  };
  Grid.prototype.draw = function (show) {
    var self = this, n = this.cols, row = Math.floor(this.at / n);
    this.shown = show;
    if (row < this.top) this.top = row;
    if (row >= this.top + this.rows) this.top = row - this.rows + 1;
    clear(this.box);
    this.items.slice(this.top * n, (this.top + this.rows) * n).forEach(function (x, k) {
      var i = self.top * n + k, t = self.tile(x, i);
      if (show && i === self.at) t.classList.add('focus');
      t.onclick = function () { self.at = i; if (self.onPointer) self.onPointer(); self.ok(x, i); };
      self.box.appendChild(t);
    });
    if (show && this.onFocus && this.items[this.at]) this.onFocus(this.items[this.at]);
  };
  Grid.prototype.current = function () { return this.items[this.at]; };
  Grid.prototype.key = function (k) {
    var n = this.cols, len = this.items.length;
    if (!len) return k === KEY.LEFT ? 'left' : k === KEY.UP ? 'up' : false;
    if (k === KEY.LEFT) { if (this.at % n === 0) return 'left'; this.at--; }
    else if (k === KEY.RIGHT) { if (this.at + 1 < len) this.at++; }
    else if (k === KEY.UP) { if (this.at < n) return 'up'; this.at -= n; }
    else if (k === KEY.DOWN) { if (this.at + n < len) this.at += n; else if (Math.floor(this.at / n) < Math.floor((len - 1) / n)) this.at = len - 1; }
    else if (k === KEY.CH_UP || k === KEY.CH_DOWN) this.at = Math.max(0, Math.min(len - 1, this.at + (k === KEY.CH_UP ? -1 : 1) * n * this.rows));
    else if (k === KEY.OK) { this.ok(this.items[this.at], this.at); return true; }
    else return false;
    this.draw(true);
    return true;
  };

  root.TahoeUi = { KEY: KEY, $: $, el: el, add: add, clear: clear, clock: clock, span: span, pad: pad, store: store, monogram: monogram, picture: picture, Nav: Nav, Grid: Grid };
})(this);
