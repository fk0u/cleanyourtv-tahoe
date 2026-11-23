# Tahoe account (private server)

Optional. Without it every app works exactly as before. With it, one e-mail + password keeps your profile name, photo,
wallpaper and display choices (clock format, seconds, clock size, greeting, theme, text size, reduced motion) in step on the
phone and the TV. A change made on the TV goes back up to the account.

## What protects it

| Layer | How |
|---|---|
| Password | scrypt (N=32768, per-user salt), constant-time check; an unknown e-mail costs the same time as a wrong password |
| Sign-in token | random, only its SHA-256 is stored, 30 days sliding, revocable; changing the password signs the other devices out |
| Brute force | 8 tries per e-mail and 20 per IP per 15 minutes; 5 sign-ups per IP per hour |
| In transit | HTTPS only (the proxy must send `X-Forwarded-Proto: https`); the clients refuse plain `http` except to localhost |
| At rest | profile JSON and every uploaded picture sealed with AES-256-GCM under `TAHOE_DATA_KEY`, bound to their owner; the server will not run accounts without the key |
| Who can sign up | nobody, unless `TAHOE_INVITE_CODE` is set (or `TAHOE_REGISTRATION=open`) |
| Uploads | JPEG, PNG or WebP only (checked by their first bytes), 2 MB avatar / 8 MB wallpaper, 40 files and 120 MB per account |

This is **server-side** encryption: the server can read your data (the key is on it). It protects the database and the
backups, not you from the server. For end-to-end encryption between devices only, the profile would have to be sealed
on the phone/TV; that is not built.

## Running it

```bash
export TAHOE_DATA_KEY=$(openssl rand -hex 32)      # keep it; losing it loses every account's data
export TAHOE_INVITE_CODE=$(openssl rand -hex 9)    # needed to create an account
node server/src/index.mjs
```

Put it behind a reverse proxy that terminates TLS and sets `X-Forwarded-Proto`. Back up the database **and** the data
folder (`TAHOE_DATA_DIR`, default: next to the database) together with the key, kept separately.

## Keeping the server address private

The address is never in the repository:

* Phone: `account.url=https://your-server` in `secrets.properties` (gitignored; see `secrets.example.properties`), or leave
  it empty and type the address into the Account screen of a public build.
* TV: the phone hands over the address and the sign-in with *Account → Use this account on my TV* (over the paired,
  authenticated link). For setup from a computer: `adb shell am broadcast -n tech.kousozo.tahoetv.launcher/tv.clean.home.Config --es account_url … --es account_token … --es account_email …`
  (only the ADB shell may send it).

Release APKs built with `-PpublicBuild` contain no address at all.
