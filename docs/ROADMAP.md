# Roadmap

[← README](../README.md) · [Bahasa Indonesia](id/ROADMAP.md) · The live status is always in the [issues labelled roadmap](https://github.com/fk0u/cleanyourtv-tahoe/issues?q=is%3Aopen+label%3Aroadmap) and [all open issues](https://github.com/fk0u/cleanyourtv-tahoe/issues).

## Released

| Version | Highlights |
|---|---|
| **3.0.1** | Kids profile + PIN, desktop recording, recorded/stale broadcast detection, Apple TV-style loader, live pause, QR pairing, FFmpeg decoder, Send log, PIN reset from the phone |
| **3.0.2** (stable) | Desktop checks every channel, gzip playlists, security fixes |
| **Next** (on `main`) | Windows Live TV through the web player, quality (Auto/1080p/720p/480p) and latency options on every device, phone app redesign |

Per-release details: [CHANGELOG.md](../CHANGELOG.md).

## Next

### 🌍 International ([#71](https://github.com/fk0u/cleanyourtv-tahoe/issues/71))
- Channels and program guides for **every country** (iptv-org country packs), Indonesia keeps its curated sources.
- **First-run setup on every device:** language first, then country/region (suggested from the system, always editable).
- Apps translated (English first, [#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56)), then community translations; RTL ready.
- Region-specific features (prayer times/adhan, "near you") become regional options.

### Broadcast fixes
- [#32](https://github.com/fk0u/cleanyourtv-tahoe/issues/32) Trans TV (sometimes Trans7) pauses for about 2 seconds, repeatedly
- [#33](https://github.com/fk0u/cleanyourtv-tahoe/issues/33) SCTV unreachable, Indosiar shows Moji TV

### Phone as the control center
- [#34](https://github.com/fk0u/cleanyourtv-tahoe/issues/34) Auto-hiding player controls
- [#35](https://github.com/fk0u/cleanyourtv-tahoe/issues/35) Full TV settings (dock, wallpaper, profiles, accounts, PIN)
- [#36](https://github.com/fk0u/cleanyourtv-tahoe/issues/36) First TV setup without a phone (local web / QR) + a QR scanner in the app
- [#37](https://github.com/fk0u/cleanyourtv-tahoe/issues/37) One phone manages many TVs, many devices to one TV

### Performance & look
- [#38](https://github.com/fk0u/cleanyourtv-tahoe/issues/38) Detect TV specs at startup, adaptive optimization and caching
- [#39](https://github.com/fk0u/cleanyourtv-tahoe/issues/39) Built-in wallpapers from a free photo API
- [#41](https://github.com/fk0u/cleanyourtv-tahoe/issues/41) Advanced UI/UX for phone, TV and desktop

### Bugs from the audit (12 Nov 2026)
- [#43](https://github.com/fk0u/cleanyourtv-tahoe/issues/43) Fajr adhan doesn't sound in TV standby; the alarm chain can break
- [#44](https://github.com/fk0u/cleanyourtv-tahoe/issues/44) CHANGELOG & documentation review findings
- [#45](https://github.com/fk0u/cleanyourtv-tahoe/issues/45) An interrupted update download is treated as complete
- [#46](https://github.com/fk0u/cleanyourtv-tahoe/issues/46) Kids profile hardening and data privacy
- Three security findings were reported privately (see [SECURITY.md](../SECURITY.md)).

### Kids profile & adhan
- [#47](https://github.com/fk0u/cleanyourtv-tahoe/issues/47) Adult check with a math question
- [#54](https://github.com/fk0u/cleanyourtv-tahoe/issues/54) Advanced Kids profile: time limits, viewing hours, reports
- [#48](https://github.com/fk0u/cleanyourtv-tahoe/issues/48) Advanced adhan: village-level location, onboarding, wake the screen

### Watching
- [#49](https://github.com/fk0u/cleanyourtv-tahoe/issues/49) Immersive desktop: integrated player, mini player, shortcuts, EPG timeline
- [#50](https://github.com/fk0u/cleanyourtv-tahoe/issues/50) Multi-view
- [#51](https://github.com/fk0u/cleanyourtv-tahoe/issues/51) Audio/subtitle track selection, ASS/SSA subtitles
- [#52](https://github.com/fk0u/cleanyourtv-tahoe/issues/52) Notifications from the TV to the phone
- [#53](https://github.com/fk0u/cleanyourtv-tahoe/issues/53) Record on the TV/phone, scheduled recording without the desktop app open

### Quality & distribution
- [#55](https://github.com/fk0u/cleanyourtv-tahoe/issues/55) Signed & notarized macOS and Windows apps
- [#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56) English inside the apps
- [#57](https://github.com/fk0u/cleanyourtv-tahoe/issues/57) Automated tests for the TV launcher and phone app

### New platforms
- [#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26) Tahoe as a standalone app (TV boxes without root/ADB)
- [#40](https://github.com/fk0u/cleanyourtv-tahoe/issues/40) Google TV and webOS (Tizen later)

## Plan archive

- [ROADMAP-3.0.md](ROADMAP-3.0.md): the 2.9.3 → 3.0.0 plan (mostly released, Indonesian).
- [../PLAN.md](../PLAN.md): the original v2 plan (tvOS 26 style).
