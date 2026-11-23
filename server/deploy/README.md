# Deploying the Tahoe server

Needs Node 22+. No npm install (no dependencies).

1. Copy `server/` to `/opt/tahoe/server`; `useradd --system tahoe`; `mkdir -p /var/lib/tahoe-server && chown tahoe: /var/lib/tahoe-server`.
2. `cp tahoe-server.env.example /etc/tahoe-server.env`, fill it (generate the key and codes as written inside), `chmod 600`.
3. `cp tahoe-server.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now tahoe-server`.
4. Put Caddy (or nginx) in front, see `Caddyfile.example`. The server listens on 127.0.0.1 only.
5. Check: `journalctl -u tahoe-server -n 5` must say `accounts: invite`; `curl https://your-server/api/account/me` must answer 401 (not 503).
6. Backups: copy `/var/lib/tahoe-server` (database + sealed files) regularly; keep `TAHOE_DATA_KEY` somewhere else.
7. Phone build: `account.url=https://your-server` in `secrets.properties`, then rebuild. Public builds: type it in the Account screen.

Updating: copy the new `server/src`, `systemctl restart tahoe-server`. New endpoints this adds: `/api/account/*`,
`/api/tunnel/*`, `/api/public/catalog`.
