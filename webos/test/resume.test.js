const test = require('node:test');
const assert = require('node:assert');
const R = require('../app/js/resume.js');

test('picks up only a film that is part way through', () => {
  assert.strictEqual(R.seek(null), 0);
  assert.strictEqual(R.seek({ t: 20, d: 6000 }), 0);     // barely started
  assert.strictEqual(R.seek({ t: 600, d: 6000 }), 600);
  assert.strictEqual(R.seek({ t: 5950, d: 6000 }), 0);   // watched
  assert.strictEqual(R.done({ t: 5950, d: 6000 }), true);
});

test('the first seconds never wipe a real position, and old entries go first', () => {
  const list = R.remember({}, 'a', 600, 6000, 1);
  assert.strictEqual(R.remember(list, 'a', 3, 6000, 2).a.t, 600);
  assert.strictEqual(R.remember(list, 'a', 700, 6000, 3).a.t, 700);
  assert.deepStrictEqual(R.remember({}, 'x', 100, Infinity, 1), {}); // live streams have no position
  let many = {};
  for (let i = 0; i < 205; i++) many = R.remember(many, 'u' + i, 100, 1000, i);
  assert.strictEqual(Object.keys(many).length, 200);
  assert.ok(!many.u0 && many.u204);
});
