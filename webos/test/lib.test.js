const test = require('node:test');
const assert = require('node:assert');
const L = require('../app/lib.js');

const m3u = `#EXTM3U
#EXTINF:-1 tvg-logo="https://x/logo.png" group-title="General",Trans 7 TV (720p)
#EXTVLCOPT:http-referrer=https://ref.example/
https://a.example/trans7.m3u8
#EXTINF:-1 group-title="General",Trans7
https://b.example/trans7.m3u8
#EXTINF:-1 group-title="Movies",AMC Europe
https://c.example/amc.m3u8
#EXTINF:-1 group-title="Film",The.Raid.2.2014.1080p.BluRay.x264
https://pixeldrain.com/api/file/abc
#EXTINF:-1 group-title="Series",Drama X S01E02
https://d.example/2.mp4
#EXTINF:-1 group-title="Series",Drama X S01E01
https://d.example/1.mp4
#KODIPROP:inputstream.adaptive.license_key=abc:def
#EXTINF:-1,Locked TV
https://e.example/locked.mpd
`;

test('parses options and headers', () => {
  const c = L.parseM3u(m3u);
  assert.strictEqual(c.length, 7);
  assert.strictEqual(c[0].referer, 'https://ref.example/');
  assert.strictEqual(c[1].referer, null);
  assert.strictEqual(c[6].drm, true);
});

test('live list merges feeds, keeps film files and DRM out', () => {
  const live = L.arrange(L.parseM3u(m3u));
  assert.deepStrictEqual(live.map(e => e.name), ['Trans7', 'AMC Europe']);
  assert.deepStrictEqual(live[0].streams, ['https://b.example/trans7.m3u8', 'https://a.example/trans7.m3u8']); // a feed needing a Referer goes last
  assert.strictEqual(live[0].logo, 'https://x/logo.png');
});

test('films and series are grouped like on Android', () => {
  const lib = L.library(L.parseM3u(m3u));
  assert.deepStrictEqual(lib.map(t => [t.title, t.series, t.items.length]), [['Drama X', true, 2], ['The Raid 2', false, 1]]);
  assert.strictEqual(lib[1].year, 2014);
  assert.strictEqual(lib[0].items[0].episode, 1);
  assert.strictEqual(L.isLibrary({ name: 'x', url: 'https://notpixeldrain.com/a', group: null }), false);
});

test('OTT JSON with base64 fields', () => {
  const b = s => Buffer.from(s).toString('base64');
  const c = L.parseM3u(JSON.stringify({ categories: { 1: { name: b('Berita') } }, channels: [{ name: b('Kompas TV'), url: b('https://k.example/a.m3u8'), category: '1' }] }));
  assert.deepStrictEqual([c[0].name, c[0].group, c[0].url], ['Kompas TV', 'Berita', 'https://k.example/a.m3u8']);
});

test('free filter and title matching', () => {
  assert.strictEqual(L.isFree({ name: 'HBO', url: 'https://x/a.m3u8' }), false);
  assert.strictEqual(L.isFree({ name: 'TVRI', url: 'https://x/a.m3u8' }), true);
  assert.strictEqual(L.titleMatches('Agak Laen', 'Agak Laen'), true);
  assert.strictEqual(L.titleMatches('13 Bomb Di Jakarta', '13 Bombs'), false);
});
