const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const T = require('../app/js/i18n.js');

const keys = (o, p = '') => Object.entries(o).flatMap(([k, v]) => typeof v === 'object' ? keys(v, p + k + '.') : [p + k]).sort();

test('all languages have the same texts', () => {
  for (const l of ['en', 'zh']) assert.deepStrictEqual(keys(T.strings(l)), keys(T.strings('id')), l);
});

test('every text the code asks for exists', () => {
  const src = ['app.js', 'player.js', 'screens.js', 'setup.js'].map(f => fs.readFileSync(__dirname + '/../app/js/' + f, 'utf8')).join('\n');
  const used = new Set([...src.matchAll(/\b(?:S|app\.S|S\(\))\.([a-zA-Z_]+)/g)].map(m => m[1]));
  const missing = [...used].filter(k => !(k in T.strings('en')));
  assert.deepStrictEqual(missing, []);
});

test('language from the TV', () => {
  assert.strictEqual(T.pick('id-ID'), 'id');
  assert.strictEqual(T.pick('in'), 'id');
  assert.strictEqual(T.pick('fr-FR'), 'en');
});

test('the app stays ES5 (webOS 4 is Chromium 53)', () => {
  for (const f of fs.readdirSync(__dirname + '/../app/js')) {
    const code = fs.readFileSync(__dirname + '/../app/js/' + f, 'utf8').replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|'(?:\\.|[^'\\\n])*'|"(?:\\.|[^"\\\n])*"|\/(?:\\.|\[[^\]]*\]|[^\/\\\n])+\/[gimu]*/g, "''");
    assert.ok(!/=>|`|\b(let|const|class|async|await)\b/.test(code), f);
  }
});
