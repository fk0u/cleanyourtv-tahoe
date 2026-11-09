#!/usr/bin/env bash
# Start Tahoe IPTV Server with koutv.m3u and anotheriptv.m3u
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

PORT="${IPTV_PORT:-8088}"
HOST="${IPTV_HOST:-0.0.0.0}"

echo "Memulai Tahoe IPTV Server di $HOST:$PORT..."
exec python3 tools/iptv_server.py koutv.m3u anotheriptv.m3u --host "$HOST" --port "$PORT" "$@"

