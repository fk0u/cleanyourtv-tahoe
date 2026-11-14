# Roadmap

[← README](../README.md) · [Bahasa Indonesia](id/ROADMAP.md) · Live status lives in [milestones](https://github.com/fk0u/cleanyourtv-tahoe/milestones) and [open issues](https://github.com/fk0u/cleanyourtv-tahoe/issues).

**Release cadence:** Tahoe targets a **monthly** feature release from `main` (tracked by the single version in `gradle.properties`), with fast patch releases (`x.y.z`) for trust, broadcast, or security fixes.

## Released

| Version | Highlights |
|---|---|
| **3.0.1** | Kids profile + PIN, desktop recording, recorded/stale broadcast detection, Apple TV-style loader, live pause, QR pairing, FFmpeg decoder, Send log, PIN reset from the phone |
| **3.0.2** | Desktop checks every channel, gzip playlists, security fixes |
| **3.1.0** | Windows Live TV through the web player, quality (Auto/1080p/720p/480p) and latency options on every device, phone app redesign |
| **3.2.0** | Full phone + desktop + TV onboarding, all-country channel packs, three-language sorry slate, debug side-by-side installs, global README |

Per-release details: [CHANGELOG.md](../CHANGELOG.md).

## Now (`3.3`–`3.4`: Trust, International & Simple Connect)

- **International & Reliability (`3.3` on `main`):** multi-country EPG/channels ([#79](https://github.com/fk0u/cleanyourtv-tahoe/issues/79), [#80](https://github.com/fk0u/cleanyourtv-tahoe/issues/80)), EN/ID/中文 UI ([#56](https://github.com/fk0u/cleanyourtv-tahoe/issues/56)), neutral demo mode ([#99](https://github.com/fk0u/cleanyourtv-tahoe/issues/99)), local bundled web player assets ([#81](https://github.com/fk0u/cleanyourtv-tahoe/issues/81)), stable channel IDs ([#94](https://github.com/fk0u/cleanyourtv-tahoe/issues/94)), source policy & 7-day takedown ([#97](https://github.com/fk0u/cleanyourtv-tahoe/issues/97), [#101](https://github.com/fk0u/cleanyourtv-tahoe/issues/101)), and desktop playback hardening ([#75](https://github.com/fk0u/cleanyourtv-tahoe/issues/75), [#105](https://github.com/fk0u/cleanyourtv-tahoe/issues/105)).
- **Simple & Connect — Batch 1 (on `main`):** atomic source health cache ([#106](https://github.com/fk0u/cleanyourtv-tahoe/issues/106)), link reconnect/merge/queue safety ([#96](https://github.com/fk0u/cleanyourtv-tahoe/issues/96), [#107](https://github.com/fk0u/cleanyourtv-tahoe/issues/107), [#111](https://github.com/fk0u/cleanyourtv-tahoe/issues/111)), guide & image memory optimization ([#108](https://github.com/fk0u/cleanyourtv-tahoe/issues/108), [#109](https://github.com/fk0u/cleanyourtv-tahoe/issues/109)), and plain-language UI copy ([#65](https://github.com/fk0u/cleanyourtv-tahoe/issues/65)).
- **Engineering Health (on `main`):** single version source in `gradle.properties`, verified CI & release gating, Android lint in PRs, Gradle Dependabot ([#57](https://github.com/fk0u/cleanyourtv-tahoe/issues/57), [#100](https://github.com/fk0u/cleanyourtv-tahoe/issues/100)).
- **Remaining in `3.4`:**
  - [#90](https://github.com/fk0u/cleanyourtv-tahoe/issues/90) Native-TV controls + Simple Mode (channel numbers, CH±, last channel, big readable text)
  - [#64](https://github.com/fk0u/cleanyourtv-tahoe/issues/64) Accessibility (TalkBack, clear focus ring, 48 dp targets, font scaling)
  - [#93](https://github.com/fk0u/cleanyourtv-tahoe/issues/93) Phone↔TV protocol v2 (typed messages, versions, acks, keep-alive)
  - [#88](https://github.com/fk0u/cleanyourtv-tahoe/issues/88) **Tahoe Connect** — hand off & control TV/desktop playback from the phone
  - Physical TV verification of [#85](https://github.com/fk0u/cleanyourtv-tahoe/issues/85) onboarding and [#87](https://github.com/fk0u/cleanyourtv-tahoe/issues/87) three-language slate

## Next (`3.4`–`3.5`: Phone Control Center & Desktop Experience)

- [#34](https://github.com/fk0u/cleanyourtv-tahoe/issues/34) Auto-hiding player controls & [#35](https://github.com/fk0u/cleanyourtv-tahoe/issues/35) Full TV settings from the phone
- [#36](https://github.com/fk0u/cleanyourtv-tahoe/issues/36) First TV setup without a phone + in-app QR scanner & [#37](https://github.com/fk0u/cleanyourtv-tahoe/issues/37) multi-TV / multi-controller support
- [#49](https://github.com/fk0u/cleanyourtv-tahoe/issues/49) Immersive desktop (integrated player, mini-player, keyboard shortcuts, EPG timeline)
- [#51](https://github.com/fk0u/cleanyourtv-tahoe/issues/51) Audio/subtitle track selection (ASS/SSA) · [#52](https://github.com/fk0u/cleanyourtv-tahoe/issues/52) TV→phone alerts · [#53](https://github.com/fk0u/cleanyourtv-tahoe/issues/53) standalone/scheduled recording
- [#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26) Standalone Android TV app (no ADB/root needed) · [#55](https://github.com/fk0u/cleanyourtv-tahoe/issues/55) Signed & notarized macOS/Windows installers · [#95](https://github.com/fk0u/cleanyourtv-tahoe/issues/95) Split `Home.java` / `IptvServer.kt`

## Later (Parked)

- [#50](https://github.com/fk0u/cleanyourtv-tahoe/issues/50) Multi-view · [#38](https://github.com/fk0u/cleanyourtv-tahoe/issues/38) Adaptive TV spec optimization · [#39](https://github.com/fk0u/cleanyourtv-tahoe/issues/39) Free photo API wallpapers
- [#47](https://github.com/fk0u/cleanyourtv-tahoe/issues/47) Math adult gate · [#54](https://github.com/fk0u/cleanyourtv-tahoe/issues/54) Kids limits & reports · [#48](https://github.com/fk0u/cleanyourtv-tahoe/issues/48) Village-level adhan & screen wake
- [#40](https://github.com/fk0u/cleanyourtv-tahoe/issues/40) Google TV & webOS (Tizen later) · [#61](https://github.com/fk0u/cleanyourtv-tahoe/issues/61) · [#62](https://github.com/fk0u/cleanyourtv-tahoe/issues/62)
- Upstream broadcast reliability: [#32](https://github.com/fk0u/cleanyourtv-tahoe/issues/32) · [#33](https://github.com/fk0u/cleanyourtv-tahoe/issues/33)

## What we won't do

- **No pirated pay-TV restreams or illegal IPTV aggregators.** Only official/public free-to-air streams or user-supplied local playlists (see [SOURCES.md](SOURCES.md)).
- **No DRM circumvention.** Encrypted or rights-protected commercial feeds are filtered out rather than bypassed.
- **No ads, telemetry tracking, or mandatory accounts** to watch free TV at home.

## Plan archive

- [ROADMAP-3.0.md](ROADMAP-3.0.md): the 2.9.3 → 3.0.0 plan (mostly released, Indonesian).
- [../PLAN.md](../PLAN.md): the original v2 plan (tvOS 26 style).
