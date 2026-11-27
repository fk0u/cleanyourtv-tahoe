#!/usr/bin/env node
/* Writes webos/app/manifest.json (what an over-the-air update downloads, with SHA-256 per file) and keeps boot.js's packaged
 * version and file list the same. Run by webos/package.sh; the tests fail when it was not run after a change. */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const APP = path.join(__dirname, '..', 'app');
const STYLES = ['style.css'];
const SCRIPTS = ['js/lib.js', 'js/epg.js', 'js/slate.js', 'js/resume.js', 'js/i18n.js', 'js/ui.js', 'js/shell.js', 'js/data.js', 'js/update.js',
  'js/preview.js', 'js/player.js', 'js/screens.js', 'js/setup.js', 'js/app.js'];

function build() {
  const version = JSON.parse(fs.readFileSync(path.join(APP, 'appinfo.json'), 'utf8')).version;
  const boot = +fs.readFileSync(path.join(APP, 'boot.js'), 'utf8').match(/var BOOT = (\d+);/)[1];
  const files = {};
  for (const f of STYLES.concat(SCRIPTS)) files[f] = crypto.createHash('sha256').update(fs.readFileSync(path.join(APP, f))).digest('hex');
  return { version, boot, styles: STYLES, scripts: SCRIPTS, files };
}

function bootSource(version) {
  const src = fs.readFileSync(path.join(APP, 'boot.js'), 'utf8');
  return src.replace(/var BUNDLED = '[^']*';/, `var BUNDLED = '${version}';`)
    .replace(/var BUNDLE = \{.*\};/, 'var BUNDLE = ' + JSON.stringify({ styles: STYLES, scripts: SCRIPTS }) + ';');
}

module.exports = { build, bootSource, APP, STYLES, SCRIPTS };

if (require.main === module) {
  const m = build();
  fs.writeFileSync(path.join(APP, 'manifest.json'), JSON.stringify(m, null, 2) + '\n');
  fs.writeFileSync(path.join(APP, 'boot.js'), bootSource(m.version));
  console.log('manifest ' + m.version + ', ' + Object.keys(m.files).length + ' files');
}
