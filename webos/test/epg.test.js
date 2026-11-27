const test = require('node:test');
const assert = require('node:assert');
const E = require('../app/js/epg.js');
const S = require('../app/js/slate.js');

const xml = `<?xml version="1.0"?><tv>
<channel id="Trans7.id"><display-name lang="id">Trans 7 HD</display-name></channel>
<channel id="523"><display-name>RCTI</display-name></channel>
<programme start="20261128060000 +0700" stop="20261128070000 +0700" channel="Trans7.id"><title lang="id">Pagi &amp; Ceria</title></programme>
<programme channel="523" start="20261128000000 +0000" stop="20261128010000 +0000"><title>Seputar</title></programme>
<programme start="20261128070000 +0700" stop="20261128080000 +0700" channel="Trans7.id"><title>Jadwal belum tersedia</title></programme>
<programme start="20261128080000 +0700" stop="20261128090000 +0700" channel="Trans7.id"><title>Siang</title></programme>
<programme start="20261101080000 +0700" stop="20261101090000 +0700" channel="Trans7.id"><title>Lama</title></programme>
</tv>`;

test('times honour the offset', () => {
  assert.strictEqual(E.time('20261128070000 +0700'), Date.UTC(2026, 10, 28, 0, 0, 0));
});

test('matches by id or name, keeps the window, drops filler', () => {
  const want = E.wanted([{ key: 't', name: 'Trans7', ids: ['Trans7.id@SD'] }, { key: 'r', name: 'RCTI', ids: [] }]);
  const p = E.parser(xml, want, Date.UTC(2026, 10, 27), Date.UTC(2026, 10, 29));
  while (!p.step(1)) {}
  const g = p.result();
  assert.deepStrictEqual(g.t.map(x => x[2]), ['Pagi & Ceria', 'Siang']);
  assert.deepStrictEqual(g.r.map(x => x[2]), ['Seputar']);
  const nn = E.nowNext(g.t, Date.UTC(2026, 10, 27, 23, 30));
  assert.strictEqual(nn[0][2], 'Pagi & Ceria');
  assert.strictEqual(nn[1][2], 'Siang');
});

test('earlier guide wins', () => {
  assert.deepStrictEqual(E.merge({ a: [[1, 2, 'x']] }, { a: [[1, 2, 'y']], b: [[1, 2, 'z']] }), { a: [[1, 2, 'x']], b: [[1, 2, 'z']] });
});

test('slate texts and tone', () => {
  assert.ok(S.headline('id', 5).length > 20);
  assert.strictEqual(S.others('id').length, 2);
  const t = S.tone();
  assert.strictEqual(t.length, 22050 * 11);
  assert.ok(Math.max.apply(null, Array.from(t.slice(0, 60000))) <= 0.621);
});
