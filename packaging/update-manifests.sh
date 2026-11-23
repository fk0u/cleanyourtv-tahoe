#!/bin/sh
# Usage: sh packaging/update-manifests.sh v3.8.0   (needs gh, run in the repo)
set -e
tag="${1:?usage: update-manifests.sh vX.Y.Z}"; ver="${tag#v}"
cd "$(dirname "$0")"; rm -rf out; mkdir -p out
# GitHub records each asset's SHA-256 itself; read it from the release instead of downloading 400 MB
sums=$(gh api "repos/fk0u/tahoe-tv/releases/tags/$tag" --jq '.assets[] | "\(.digest) \(.name)"' | sed 's/^sha256://')
sha() { echo "$sums" | awk -v f="$1" '$2==f {print $1}'; }
arm=$(sha TahoeTV-macos-arm64.dmg); intel=$(sha TahoeTV-macos-intel.dmg); msi=$(sha TahoeTV-windows.msi)
[ -n "$arm" ] && [ -n "$intel" ] && [ -n "$msi" ] || { echo "a checksum is missing from the release" >&2; exit 1; }
sed "s/@VERSION@/$ver/; s/@SHA_ARM@/$arm/; s/@SHA_INTEL@/$intel/" homebrew/tahoe-tv.rb.tmpl > out/tahoe-tv.rb
for t in winget/*.tmpl; do sed "s/@VERSION@/$ver/; s/@SHA_MSI@/$msi/" "$t" > "out/$(basename "${t%.tmpl}")"; done
echo "wrote packaging/out/ for $tag"
