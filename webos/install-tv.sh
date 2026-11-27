#!/bin/sh
# Installs Tahoe TV on an LG TV in Developer Mode:  webos/install-tv.sh <TV IP address>
# On the TV: open the "Developer Mode" app, switch Dev Mode Status and Key Server on. The first run asks for the
# passphrase shown in that app; type it at the prompt (it is never stored in this repository).
set -e
IP="$1"
[ -n "$IP" ] || { echo "usage: $0 <TV IP address>"; exit 1; }
cd "$(dirname "$0")"
command -v ares-install >/dev/null 2>&1 || { echo "Installing LG's CLI (@webos-tools/cli) from npm..."; npm install -g @webos-tools/cli; }
INFO="{\"host\":\"$IP\",\"port\":\"9922\",\"username\":\"prisoner\"}"
if ares-setup-device --list | grep -q "^tahoe "; then ares-setup-device --modify tahoe --info "$INFO" >/dev/null
else ares-setup-device --add tahoe --info "$INFO" >/dev/null; fi
if ! ares-install --device tahoe --list >/dev/null 2>&1; then ares-novacom --device tahoe --getkey; fi
rm -rf dist
./package.sh
ares-install --device tahoe dist/*.ipk
ares-launch --device tahoe tech.kousozo.tahoetv.webos
