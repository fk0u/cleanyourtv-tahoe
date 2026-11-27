/* The "no signal" slate of the other Tahoe TV apps (:core Slate and SlateTone): the apology in Indonesian, English and Chinese,
 * a headline that changes between tries, and Tahoe's own test tone made from code. ES5. */
(function (root) {
  'use strict';
  var HEADLINES = {
    id: ['Siaran ini sedang istirahat sebentar. Kami mencoba sumber lain untukmu.', 'Belum ada sinyal. Tahoe TV terus mencari sumber yang berfungsi.', 'Siaran ini belum bisa dijangkau. Tunggu sebentar, kami sambungkan otomatis.', 'Mohon maaf, saluran ini sedang tidak tersedia. Kami mencoba lagi di latar belakang.'],
    en: ["This channel is taking a break. We're trying other sources for you.", 'No signal right now. Tahoe TV keeps searching for a working source.', "This broadcast can't be reached at the moment. Hang tight, we'll reconnect automatically.", "Sorry, this channel isn't available. We're retrying in the background."],
    zh: ['该频道暂时休息，我们正在为你尝试其他信号源。', '目前没有信号，Tahoe TV 会持续寻找可用的信号源。', '暂时无法连接此直播，请稍候，我们会自动重新连接。', '抱歉，该频道暂时不可用，我们正在后台重试。']
  };
  var MESSAGE = {
    id: 'Mohon maaf, siaran ini sedang tidak tersedia. Kami terus mencoba sumber lain secara otomatis.',
    en: "Sorry, this channel is currently unavailable. We'll keep trying other sources automatically.",
    zh: '抱歉，该频道暂时无法播放。我们会自动尝试其他信号源。'
  };
  var FOLLOW = { id: 'Ikuti saluran TahoeTV OFFICIAL di WhatsApp untuk kabar layanan', en: 'Follow TahoeTV OFFICIAL on WhatsApp for service updates', zh: '在 WhatsApp 关注 TahoeTV OFFICIAL 频道获取服务动态' };
  var CHANNEL = 'whatsapp.com/channel/0029VbE4YqRBvvsozUIyJ42n';
  var RETRY_SECONDS = 30, MAX_RETRIES = 20;

  var RATE = 22050, CYCLE = 11, REPEATS = 5;
  /** One cycle: three bell notes, then a long test tone that swells and breathes. Same numbers as SlateTone.kt. */
  function tone() {
    var notes = [[880, 0, 0.30, 0.9, true], [659.25, 0.20, 0.34, 0.9, true], [440, 0.42, 0.70, 1, true], [1000, 1.30, 7, 0.8, false]];
    var mix = new Float32Array(Math.floor(RATE * CYCLE)), top = 1e-9, W = 2 * Math.PI;
    notes.forEach(function (n) {
      var hz = n[0], from = Math.floor(n[1] * RATE), len = Math.floor(n[2] * RATE);
      for (var i = 0; i < len && from + i < mix.length; i++) {
        var t = i / RATE, v;
        if (n[4]) v = (Math.sin(W * hz * t) + 0.22 * Math.sin(W * 2 * hz * t) + 0.07 * Math.sin(W * 3 * hz * t)) * Math.min(t / 0.008, 1) * Math.max(0, Math.min((n[2] - t) / 0.02, 1)) * Math.exp(-t * 5.5);
        else v = (Math.sin(W * hz * t) + 0.30 * Math.sin(W * hz / 2 * t) + 0.10 * Math.sin(W * 2 * hz * t)) * Math.max(0, Math.min(t / 0.6, (n[2] - t) / 0.8, 1)) * (0.82 + 0.18 * Math.sin(W * 0.8 * t));
        mix[from + i] += v * n[3];
      }
    });
    for (var i = 0; i < mix.length; i++) top = Math.max(top, Math.abs(mix[i]));
    for (i = 0; i < mix.length; i++) mix[i] = mix[i] / top * 0.62;
    return mix;
  }

  var api = {
    RETRY_SECONDS: RETRY_SECONDS, MAX_RETRIES: MAX_RETRIES, RATE: RATE, REPEATS: REPEATS, CHANNEL: CHANNEL, tone: tone,
    headline: function (lang, n) { var l = HEADLINES[lang] || HEADLINES.en; return l[((n % l.length) + l.length) % l.length]; },
    follow: function (lang) { return FOLLOW[lang] || FOLLOW.en; },
    /** The other two languages, shown smaller under the viewer's own. */
    others: function (lang) { return ['id', 'en', 'zh'].filter(function (l) { return l !== lang; }).map(function (l) { return MESSAGE[l]; }); }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.TahoeSlate = api;
})(this);
