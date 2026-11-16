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
- **Playback Reliability, Adaptive Profiling & Safety (`3.4` on `main`):** Indonesian broadcast mismatch/stale source ordering ([#33](https://github.com/fk0u/cleanyourtv-tahoe/issues/33)), tight live-window stall mitigation ([#32](https://github.com/fk0u/cleanyourtv-tahoe/issues/32)), cross-platform Stats for Nerds ([#61](https://github.com/fk0u/cleanyourtv-tahoe/issues/61)), per-channel audio/subtitle preferences & subtitle sizing ([#51](https://github.com/fk0u/cleanyourtv-tahoe/issues/51)), adaptive TV hardware profiling ([#38](https://github.com/fk0u/cleanyourtv-tahoe/issues/38)), math adult gate ([#47](https://github.com/fk0u/cleanyourtv-tahoe/issues/47)), and TV → phone reminder notifications ([#52](https://github.com/fk0u/cleanyourtv-tahoe/issues/52)).
- **Phone Control Center, QR/Web Setup, Kids Policy, Free Wallpapers, Immersive Desktop & Local Recap (`3.4` on `main`):** full TV settings center & sleep timer from the phone ([#35](https://github.com/fk0u/cleanyourtv-tahoe/issues/35)), browser TV onboarding (`/setup`) & in-app Camera2/ZXing QR scanner ([#36](https://github.com/fk0u/cleanyourtv-tahoe/issues/36)), Kids daily time limits, schedule window, custom channels & reports ([#54](https://github.com/fk0u/cleanyourtv-tahoe/issues/54)), free photo API wallpapers via Wikimedia POTD, Unsplash & Pexels ([#39](https://github.com/fk0u/cleanyourtv-tahoe/issues/39)), immersive desktop mini-player, `0–9` tuning, keyboard shortcuts, `[L]` channel drawer, `[M]` mute toggle, live hover preview, trending strip, Grid & 5h EPG timeline ([#49](https://github.com/fk0u/cleanyourtv-tahoe/issues/49)), and local privacy-first monthly Watch Recap with Story export ([#62](https://github.com/fk0u/cleanyourtv-tahoe/issues/62)).
- **Multi-view, ASS/SSA Subtitles, Headless Recorder, Standalone APK & Sub-district Adhan (`3.4` on `main`):** desktop 2×2 / 1+1 multi-view with click-to-focus audio ([#50](https://github.com/fk0u/cleanyourtv-tahoe/issues/50)), ASS/SSA styled subtitle parsing & TV/desktop overlay ([#51](https://github.com/fk0u/cleanyourtv-tahoe/issues/51)), background `--recorder` scheduled DVR daemon, TV local HLS recording & recording-done alerts ([#53](https://github.com/fk0u/cleanyourtv-tahoe/issues/53), [#52](https://github.com/fk0u/cleanyourtv-tahoe/issues/52)), non-launcher `standalone` TV APK without ADB/root ([#26](https://github.com/fk0u/cleanyourtv-tahoe/issues/26)), sub-district/kelurahan search & Adhan standby screen wake ([#48](https://github.com/fk0u/cleanyourtv-tahoe/issues/48)), and modularized large launcher/server classes ([#95](https://github.com/fk0u/cleanyourtv-tahoe/issues/95)).
- **Arabic/RTL, Language Channel Packs, Community Translation & Themes (`3.4` on `main`):** Arabic with RTL layouts across phone, TV, desktop and website, iptv-org per-language channel packs, JSON translation files + [TRANSLATING.md](TRANSLATING.md) ([#71](https://github.com/fk0u/cleanyourtv-tahoe/issues/71)); dark/light/system theme, high contrast, reduced motion, phone tablet rail, swipe/pinch player gestures and TV channel details ([#41](https://github.com/fk0u/cleanyourtv-tahoe/issues/41)). Still open: full Arabic coverage of every app string, two-pane tablet layouts, TV recording from the detail dialog.
- **Engineering Health (on `main`):** single version source in `gradle.properties`, verified CI & release gating, Android lint in PRs, Gradle Dependabot ([#57](https://github.com/fk0u/cleanyourtv-tahoe/issues/57), [#100](https://github.com/fk0u/cleanyourtv-tahoe/issues/100)).
- **Remaining in `3.4`:**
  - [#90](https://github.com/fk0u/cleanyourtv-tahoe/issues/90) Native-TV controls + Simple Mode (physical TV verification)
  - [#64](https://github.com/fk0u/cleanyourtv-tahoe/issues/64) Accessibility (TalkBack, clear focus ring, 48 dp targets, font scaling)
  - [#93](https://github.com/fk0u/cleanyourtv-tahoe/issues/93) Phone↔TV protocol v2 (typed messages, versions, acks, keep-alive)
  - [#88](https://github.com/fk0u/cleanyourtv-tahoe/issues/88) **Tahoe Connect** — hand off & control TV/desktop playback from the phone
  - Physical TV verification of [#85](https://github.com/fk0u/cleanyourtv-tahoe/issues/85) onboarding and [#87](https://github.com/fk0u/cleanyourtv-tahoe/issues/87) three-language slate

## Next (`3.5`: Platform Signing & Smart TV Ecosystems)

- [#34](https://github.com/fk0u/cleanyourtv-tahoe/issues/34) Auto-hiding player controls & [#37](https://github.com/fk0u/cleanyourtv-tahoe/issues/37) Multi-TV / multi-controller physical verification
- [#55](https://github.com/fk0u/cleanyourtv-tahoe/issues/55) Signed & notarized macOS/Windows installers

## Later (Parked)

- [#40](https://github.com/fk0u/cleanyourtv-tahoe/issues/40) Google TV & webOS (Tizen later)

## What we won't do

- **No pirated pay-TV restreams or illegal IPTV aggregators.** Only official/public free-to-air streams or user-supplied local playlists (see [SOURCES.md](SOURCES.md)).
- **No DRM circumvention.** Encrypted or rights-protected commercial feeds are filtered out rather than bypassed.
- **No ads, telemetry tracking, or mandatory accounts** to watch free TV at home.

## Plan archive

- [ROADMAP-3.0.md](ROADMAP-3.0.md): the 2.9.3 → 3.0.0 plan (mostly released, Indonesian).
- [../PLAN.md](../PLAN.md): the original v2 plan (tvOS 26 style).
