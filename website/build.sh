#!/bin/sh
# Assembles the site into website/_site: the pages plus the screenshots, hero art and logo from docs/ (kept in one
# place in the repo, so the README and the site never drift apart). Used by the deploy workflow and for local preview:
#   sh website/build.sh && python3 -m http.server -d website/_site 8080
set -e
cd "$(dirname "$0")/.."
rm -rf website/_site
mkdir -p website/_site/assets/shots
cp website/index.html website/robots.txt website/sitemap.xml website/_site/
cp website/assets/*.css website/assets/*.js website/_site/assets/
cp -R docs/screenshots/v2 docs/screenshots/mobile-v4 website/_site/assets/shots/
cp docs/readme/hero.svg docs/brand/logo-mark.svg docs/brand/app-icon-1024.png website/_site/assets/
echo "website/_site ready"
