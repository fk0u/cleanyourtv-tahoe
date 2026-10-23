#!/bin/sh
# usage: tools/disable.sh pkg...   - disables each pkg for user 0 and logs it to backup/disabled-by-us.txt
cd "$(dirname "$0")/.."
for p in "$@"; do
  case "$p" in
    android|com.android.systemui|com.android.shell|com.android.phone|com.android.settings|com.android.tv.settings|\
    *permissioncontroller*|*packageinstaller*|*ext.services*|*ext.shared*|*modulemetadata*|com.google.android.overlay.modules*|\
    com.android.vending|com.google.android.gms|com.google.android.gsf|com.google.android.inputmethod.latin|com.google.android.tv.remote.service)
      echo "REFUSED core package: $p"; continue;;
    com.android.providers.calendar|com.android.providers.contacts|com.android.providers.userdictionary|com.android.providers.blockednumber) ;;
    com.android.providers.*) echo "REFUSED core package: $p"; continue;;
  esac
  .venv/bin/python tools/tv.py "pm disable-user --user 0 $p"
  grep -qx "$p" backup/disabled-by-us.txt 2>/dev/null || echo "$p" >> backup/disabled-by-us.txt
done
