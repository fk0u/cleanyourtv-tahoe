const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const crypto = require('crypto');
global.TahoeData = { newer: () => false };
const Up = require('../app/js/update.js');
const M = require('../tools/manifest.js');

test('SHA-256 matches Node for ASCII, Indonesian and Chinese text, and long input', () => {
  for (const s of ['', 'abc', 'Siapa yang menonton?', '电影和剧集 · 晨礼', 'x'.repeat(1000), fs.readFileSync(__dirname + '/../app/js/i18n.js', 'utf8')]) {
    assert.strictEqual(Up.sha256(s), crypto.createHash('sha256').update(s, 'utf8').digest('hex'));
  }
});

test('manifest.json and boot.js are up to date (run webos/tools/manifest.js)', () => {
  const now = M.build(), saved = JSON.parse(fs.readFileSync(M.APP + '/manifest.json', 'utf8'));
  assert.deepStrictEqual(saved, now);
  assert.strictEqual(fs.readFileSync(M.APP + '/boot.js', 'utf8'), M.bootSource(now.version));
});

test('every script of the app is in the manifest, and exists', () => {
  const listed = new Set(M.SCRIPTS);
  for (const f of fs.readdirSync(M.APP + '/js')) assert.ok(listed.has('js/' + f), f + ' missing from tools/manifest.js');
  for (const f of M.SCRIPTS.concat(M.STYLES)) assert.ok(fs.existsSync(M.APP + '/' + f), f);
});
