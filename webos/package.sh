#!/bin/sh
# Builds webos/dist/<id>_<version>_all.ipk (version from gradle.properties) and the over-the-air manifest.
# Needs the LG CLI: npm install -g @webos-tools/cli
set -e
cd "$(dirname "$0")"
VERSION=$(sed -n 's/^tahoe.version=//p' ../gradle.properties)
sed -i.bak "s/\"version\": \"[^\"]*\"/\"version\": \"${VERSION%%-*}\"/" app/appinfo.json && rm app/appinfo.json.bak
node tools/manifest.js
node --test test/*.test.js
ares-package --no-minify app -o dist
