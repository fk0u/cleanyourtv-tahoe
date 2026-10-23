#!/bin/sh
# Re-enables every package disabled by tools/disable.sh (backup/disabled-by-us.txt),
# or, without that log, everything in debloat-list.txt.
cd "$(dirname "$0")"
LIST=backup/disabled-by-us.txt
[ -f "$LIST" ] || LIST=debloat-list.txt
grep -v '^#' "$LIST" | while read -r p; do [ -n "$p" ] && .venv/bin/python tools/tv.py "pm enable $p"; done
