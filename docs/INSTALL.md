# Install & configure

[← README](../README.md) · [Bahasa Indonesia](id/INSTALL.md)

Contents: [From your phone](#install-from-your-phone-no-computer) · [From a computer](#install-from-a-computer-adb) · [Desktop](#desktop-app) ·
[Remote control](#remote-control) · [Phone ↔ TV](#phone--tv) · [Configuration](#configuration) · [API keys](#api-keys-optional) ·
[Wallpaper & photos](#wallpaper--photos) · [Kids profile](#kids-profile) · [Send log](#send-log) · [Debloat](#debloat) · [Restore the TV](#restore-the-tv)

> The apps' text is in Indonesian for now (English: [#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56)). Menu
> names below give the English meaning with the Indonesian label in brackets where it helps.

## Install from your phone (no computer)

The recommended way. You need an Android phone on the same Wi-Fi as the TV.

1. On the TV: **Settings → Device Preferences → About → press "Build" 7×**, then **Developer options → USB debugging: ON**.
2. On the phone: install [`tahoe-tv.apk`](https://github.com/fk0u/cleanyourtv-tahoe/releases/latest/download/tahoe-tv.apk) and open the **Install** (*Pasang*) tab.
3. The app scans the Wi-Fi and lists the TVs it finds (ADB port 5555, and TVs already running Tahoe via `/info`). You can also type an IP.
4. The TV shows **"Allow USB debugging?"**: tick *Always allow*, then **OK**.
5. Choose which built-in apps to turn off, then fill in your name, city (searched automatically), photo, dock and API keys (optional), and tap **Install to TV**.
6. Done: the launcher is installed and set as Home, the screensaver is on and the phone is connected. Tap **Turn off debugging on the TV**.

Change settings any time by turning debugging back on and opening the app. **Restore TV** puts everything back to stock.

Under the hood: ADB over Wi-Fi ([dadb](https://github.com/mobile-dev-inc/dadb)) with the phone's own key. Settings go to the
receiver `tech.kousozo.tahoetv.launcher/tv.clean.home.Config`, which only the ADB shell can call.

> TV box without a debugging option? See [#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26) (Tahoe as a standalone app) and
> [#36](https://github.com/fk0u/cleanyourtv-tahoe/issues/36) (first setup through a local web page / QR).

## Install from a computer (ADB)

You need Python 3, JDK 17 and the Android SDK (platform 36, build-tools 34+). The Gradle wrapper downloads Gradle.
Turn on USB debugging on the TV as above.

```bash
git clone https://github.com/fk0u/cleanyourtv-tahoe.git && cd cleanyourtv-tahoe
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp secrets.example.properties secrets.properties     # optional: API keys
export TV_HOST=192.168.1.50                           # your TV's IP
./build-and-install.sh                                # build the launcher APK (Gradle) + install
```

One-time permissions:

```bash
T=".venv/bin/python tools/tv.py"
$T 'pm grant tech.kousozo.tahoetv.launcher android.permission.ACCESS_FINE_LOCATION'    # Wi-Fi name
$T 'pm grant tech.kousozo.tahoetv.launcher android.permission.WRITE_SECURE_SETTINGS'   # Apps key keeps working after reboot
$T 'pm grant tech.kousozo.tahoetv.launcher android.permission.RECORD_AUDIO'            # mic for the assistant
$T 'appops set tech.kousozo.tahoetv.launcher SYSTEM_ALERT_WINDOW allow'                # prayer banner & phone notifications
$T 'appops set tech.kousozo.tahoetv.launcher GET_USAGE_STATS allow'                    # "recently used" dot, last app
$T 'cmd package set-home-activity tech.kousozo.tahoetv.launcher/tv.clean.home.Home'
$T 'settings put secure screensaver_components tech.kousozo.tahoetv.launcher/tv.clean.home.Lock'
tools/disable.sh com.google.android.tvlauncher                         # turn off the stock launcher (reversible)
```

Public build without keys: `./gradlew -PpublicBuild assembleRelease` (keys are then set from the phone app).

## Desktop app

Download from [Releases](https://github.com/fk0u/cleanyourtv-tahoe/releases/latest): `TahoeTV-macos-arm64.dmg` (Apple Silicon),
`TahoeTV-macos-intel.dmg` or `TahoeTV-windows.msi`.

- **macOS:** the app isn't notarized by Apple yet. The first time: right-click the app → **Open** → **Open**.
- **Windows:** SmartScreen may warn; choose **More info → Run anyway**. Live TV plays in a Microsoft Edge app window (built into Windows 10/11).
- App data: `~/.tahoetv/` (custom playlists in `~/.tahoetv/playlists/`, diagnostic log `diag.log`, recording schedule). Recordings go to `~/Movies/Tahoe TV` (macOS) or `~/Videos/Tahoe TV`.
- The LAN IPTV server starts automatically (Settings → IPTV Server), or from a terminal: `./tools/start-iptv-server.sh`.

Build it yourself: `./gradlew -PpublicBuild :desktop:packageDmg` (or `packageMsi` on Windows). Run directly: `./gradlew :desktop:run`.

## Remote control

| Key (remote / gamepad) | Action |
|---|---|
| ◀ ▶ ▲ ▼ / stick | Move exactly one step: top bar → hero buttons → dock → Launchpad |
| OK / A | Open · select |
| Hold OK / X | App menu: pin/unpin, move, info, uninstall |
| Back / B | Close the panel → back to the dock |
| Apps / Start | Launchpad |
| Menu / Y | Control Center |
| Select | Assistant |
| L1 / R1 | Jump 3 rows in Launchpad |

In **Live TV**: ▲▼ change channel, 0–9 channel number, ⏪⏩ pause/skip 30 seconds, **hold OK** to mark a source "not live"
(or restore marked sources when a channel won't play); the red key is the same as holding OK. Quality and latency are in
Control Center (◀ ▶).

## Phone ↔ TV

- **Phone app** (`tahoe-tv.apk`): pair with the **QR code** on the TV (Control Center → Phone) or a 6-digit code. It has the remote, Live TV, install/set up the TV, Send log and the Kids PIN.
- **Web (any phone, nothing to install):** open the address shown on the TV for a remote, gamepad, app list, send a link, ask the assistant, send photos and upload an adhan sound.
- **Phone notifications & music on the TV:** in the phone app, allow notification access. The TV shows notifications and what's playing, with play/pause/next in Control Center.
- **Security:** LAN only (port 8765). Every phone needs a token from a one-time code. **Menu** on the TV's "Connect phone" screen forgets all phones.

## Configuration

Most of it is set from the phone app. Build defaults are in [`launcher/src/main/res/values/config.xml`](../launcher/src/main/res/values/config.xml):

| Key | Default | What it does |
|---|---|---|
| `dock_size` | `6` | Dock slots (4–7) |
| `columns` | `5` | Launchpad columns |
| `dock_apps` | YouTube, Netflix, BitTV, Spotify, TikTok, Vidio | Initial dock (package names); missing apps are skipped |
| `user_name` | `Tahoe` | Name in the greeting, profile and lock screen |
| `avatar_url` | (empty) | Profile photo (cached on the TV) |
| `city`, `latitude`, `longitude` | (empty) | Weather & prayer times (set via onboarding) |
| `wallpaper_minutes` | `10` | Wallpaper change interval |

Dock changes made with the hold-OK menu are saved on the TV per profile.

**Live TV:** channels come from [iptv-org](https://github.com/iptv-org/iptv), [dhanytv](https://github.com/dhasap/dhanytv) (free Indonesian TV part) and
[yositapjani](https://github.com/yositapjani/iptv-indonesia). Your own playlist: phone app (URL) or desktop (Settings → Add playlist file).
Channels for other countries are planned in [#71](https://github.com/fk0u/cleanyourtv-tahoe/issues/71); until then, add an
[iptv-org country playlist](https://github.com/iptv-org/iptv#grouped-by-country) (e.g. `https://iptv-org.github.io/iptv/countries/us.m3u`) as your own playlist.

## API keys (optional)

Put them in `secrets.properties` (gitignored) or set them from the phone app. Empty = the feature is hidden.

| Key | For | Get it at |
|---|---|---|
| `gemini.apiKey`, `gemini.model` | Assistant (default `gemini-3.5-flash-lite`) | [Google AI Studio](https://aistudio.google.com/apikey) |
| `youtube.apiKey` | "Trending on YouTube" hero | Google Cloud → YouTube Data API v3 |
| `tmdb.apiKey` | "Popular on Netflix" hero | [TMDB](https://www.themoviedb.org/settings/api) |
| `homeassistant.url`, `homeassistant.token` | Home panel | Home Assistant → Profile → Long-lived token |

Gemini's Google Search grounding has its own quota. When it runs out (429), the assistant answers without search.

## Wallpaper & photos

- **Personal** (owner): `files/wallpapers` + `files/photos` (photos sent from the phone). **Default**: the built-in Tahoe gradient. The Guest profile always uses Default.
- Change mode: Control Center → Wallpaper → ◀ ▶. OK = next wallpaper.
- From a computer:
  ```bash
  sips -s format jpeg -z 1080 1920 photo.jpg --out wallpapers/01.jpg
  .venv/bin/python tools/tv.py --push /sdcard/Android/data/tech.kousozo.tahoetv.launcher/files/wallpapers wallpapers/01.jpg
  ```
- Built-in wallpapers from a free photo API are planned in [#39](https://github.com/fk0u/cleanyourtv-tahoe/issues/39).

## Kids profile

1. On **Who's watching?**, choose **Kids**. The first time, create a 4-digit PIN (with the arrow keys).
2. In Kids, Live TV only shows kids' & documentary channels, and only allowed apps appear.
3. Leaving Kids (Control Center → **Switch profile**, or when the TV turns on) asks for the PIN.
4. Set the PIN and allowed apps from your own profile: Control Center → **Kids profile**.
5. **Forgot the PIN?** On a paired phone: Remote → **Kids PIN** → enter a new PIN.

## Send log

To report a channel that won't play or an app that closes by itself:
- **Phone:** Remote → **Send log** (phone + TV logs together) via WhatsApp, email and so on.
- **TV:** Control Center → **Send log** marks the moment; then send it from the phone.
- **Desktop:** Settings → Help → **Save & copy log**, then **Report on GitHub**.

Logs contain player notes and crashes; stream addresses are recorded without tokens, keys or passwords.

## Debloat

List: [`debloat-list.txt`](../debloat-list.txt). Run it in small batches and check the screen in between:

```bash
tools/disable.sh com.miui.tv.analytics tv.alphonso.alphonso_eula
```

`tools/disable.sh` **refuses** core Android packages (permission controller, package installer, providers, systemui, settings, …).
Left alone on purpose: Assistant, Chromecast, Miracast, firmware updates, Play Store/Services, setup wizard.

## Restore the TV

From the phone app: **Restore TV**. From a computer:

```bash
./restore.sh
.venv/bin/python tools/tv.py 'pm enable com.google.android.tvlauncher'
.venv/bin/python tools/tv.py 'pm uninstall tech.kousozo.tahoetv.launcher'
.venv/bin/python tools/tv.py 'settings put secure screensaver_components com.mitv.dream/com.mitv.dream.MiDream'
```
