/* Where a film or episode is picked up again. Pure rules, shared by the app and its tests. ES5. */
(function (root) {
  'use strict';
  var START = 30, END = 60, DONE = 0.92, KEEP = 200;
  function done(saved) { return !!saved && saved.d > 0 && saved.t / saved.d > DONE; }
  /** Seconds to seek to: not for something barely started, nearly over, or already watched. */
  function seek(saved, duration) {
    if (!saved || done(saved) || saved.t <= START) return 0;
    var d = duration || saved.d;
    return saved.t < d - END ? saved.t : 0;
  }
  /** [list] with [url] at [t] of [d] seconds; the first moments never overwrite a real position, and only the newest are kept. */
  function remember(list, url, t, d, now) {
    if (!(d > 0) || !isFinite(d) || (t < START && list[url] && list[url].t > t)) return list;
    list[url] = { t: t, d: d, at: now };
    var keys = Object.keys(list);
    if (keys.length > KEEP) { keys.sort(function (a, b) { return list[a].at - list[b].at; }); delete list[keys[0]]; }
    return list;
  }
  var api = { done: done, seek: seek, remember: remember };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.TahoeResume = api;
})(this);
