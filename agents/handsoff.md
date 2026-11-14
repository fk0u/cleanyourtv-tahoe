# handsoff.md — pick up from here

> `[HANDOFF] from=review-board+main-agent · to=next human/agent · date=2026-11-14`

## State of the repo

- **main = v3.2.0**, which brought:
  - onboarding on phone, desktop and TV (#84, #85);
  - channels for every country (#74);
  - the three-language slate (#87);
  - desktop playback fixes (#82);
  - debug builds installing next to the release app (#86);
  - the worldwide README with 中文 (#89);
  - this folder (#112).
- **Released:** `v3.2.0` (2026-11-13, all 5 assets). New work goes under a fresh `## Belum dirilis` heading in the CHANGELOG. The 3.2.0 notes include a "Diketahui" (known issues) list.
- **Trust Patch 3.2.x Progress:**
  - #102 (CH+ direction): merged in #116.
  - #103 (Red key legend): merged in #119.
  - #78 (Owner personal defaults clean): merged in #120.
  - #92 (Desktop installer release-signed bundled launcher): merged in #121.
  - #104 (EPG streaming parser & OOM prevention): merged in #117.
  - #114 (Slate follow-ups & calmer layout): merged in #118.
  - #45 (Truncated update verification & SHA-256): merged in #123.
  - GHSA-x8v7-r42c-7qqj (Desktop IPTV server Host/CORS/XSS/secrets): merged in #124.
  - GHSA-34j8-5cp5-rcmv (Companion pairing DoS & Authorization header): merged in #125.
  - GHSA-cp6v-7p8f-hpvx (Actions SHA pinning, Dependabot & release env): merged in #126.
- **3.3 International Progress (Batch 1):**
  - #105 (`Web.openFollowingRedirects` for `http` ↔ `https` redirects across `Web`, `HlsProbe`, `Sources`, `HlsRecorder`): merged in #131.
  - #94 (Stable `M3u.Channel.channelId`, `Viewer` schema v2 favourite migration, `IptvServer` stable IDs): merged in #132.
  - #81 (Local bundled `hls.min.js` & `shaka-player.compiled.js` under `/assets/` in `:desktop`, zero external CDN in CSP): merged in #133.
  - #101 & #97 (Trilingual `docs/SOURCES.md` 7-day takedown policy, `MAINTAINERS.md`, `.github/FUNDING.yml`, English PR template): merged in #134.
  - #110 (Website `中文` language toggle, 116 keys in `i18n.js`, `zh-Hans` `hreflang`, CJK font fallbacks, ARIA tabs keyboard navigation, slideshow hover/focus pause): merged in #135.
- **3.3 International Progress (Batch 2):**
  - #80 & #79 (Multi-region `:core` de-Indonesianisation: `Epg.norm` & `tvg-id` indexing, parameterized `Web` `lang`/`watchRegion`, `LocalTv.arrange(..., countries)`, and `TvViewer.zone` prayer/time zone wiring across launcher/desktop/companion): merged in #137.
  - #56 (`I18n` EN/ID/中文 tables in `:core`, localized `PlayPrefs`/`Scan`/`TvLink`/`HlsRecorder`, active viewer language in desktop & companion UIs, `I18nTest`): merged in #138.
  - #91 & #99 (`Brand.tagline(lang)`, removed third-party OS trademarks from headlines/copy, aligned website CSS tokens `--cyan-deep`/`--lake-deep`/`--motion-*`, replaced personal `og:image`, added `Demo` mode `--demo`/`-Dtahoe.demo=true` & contributing privacy rules): merged in #139.
  - #75 (`HlsProxy.open` redirect delegation, `HlsProxy.trimDiscontinuity`, automatic `WebPlayer` fallback on verified live/unsupported failures in `PlayerWindow`, `HlsProxyTest`): merged in #140.
- **3.4 Simple & Connect (Batch 1) & Engineering Health:**
  - #106 (`SourceHealth` per-line recovery + `@Synchronized` sibling-temp atomic writes + `probeAll` safe decrement): merged in #142.
  - #107, #96, #111 (`Viewer` revision/diff/merge patches + onboarding confirmation prompt, bounded offline queue & `TvLink.shared`, `Sync` stop guard, `Companion` `/apps` 2s timeout, desktop `Snapshot` & immutable channel snapshot): merged in #143.
  - #108, #109 (Deferred/windowed TV guide inflate + `refreshRow`, memoized live search & filters in desktop & phone, bounded `LruCache` + downsampling on desktop, phone, and TV `Avatar`): merged in #144.
  - #65 (Plain-language glossary in `BRAND.md`, EN/ID/中文 `I18n` status/badge keys, calmer desktop/TV/phone/web UI copy): merged in #145.
  - #100, #57, #98, #60 (Single `gradle.properties` version -> generated `TahoeVersion`, PR lint/website checks + `desktop.yml` `verify` gate & tag match, Gradle Dependabot, multi-stage website Docker build, Now/Next/Later EN/ID roadmaps): merged in #146.
- **3.4 Simple & Connect (Batch 2) & Hardening:**
  - #153 (Dependabot & build dependencies sync across workflows and Gradle/Compose versions): merged in #153.
  - #43, #46 (Standby Adzan today/tomorrow cache, midnight Subuh rollover, `00:05` refresh + backoff retry, `goAsync()` + wake lock; persistent exponential Kids PIN lockout `2s -> 30s -> 300s` + `android:allowBackup="false"` on launcher & companion): merged in #154.
  - #93, #88 (Phone↔TV `Protocol` v2 with typed envelopes, acks & keep-alive; Tahoe Connect P0–P1 device picker sheet, active target pill, handoff `watch`/`stop`, now-playing mirror): merged in #155.
  - #90, #64, #34 (Simple Mode `Viewer.simpleMode` toggle & larger TV layout/font scale, TalkBack `contentDescription` & touch-exploration player controls, native TV `numOverlay` pill with OK confirm & `KEYCODE_DEL`/`KEYCODE_LAST_CHANNEL`): merged in #156.
  - #157 (Docs & task sync after #153–#156): merged in #157.
  - #37, #88 (Multi-TV `PairedDevices` store & unit tests, `TvLink.pairWithInfo`, launcher `Companion` `/devices` & `/devices/revoke`, `NsdManager` `TvDiscovery`, `TvDevicesSheet`, `Remote.kt` multi-TV selector): merged in #158.
  - #63, #88 (Companion Android PiP mode, double-tap `-10s`/zoom/`+10s` gestures, Zoom/Fit & PiP top-bar controls, system channel share sheet, Continue-on-Phone handoff in `TvConnectBar` & `ChannelScreen`): merged in #159.
  - #95, #88 (Extracted testable `DockGrid` layout model from `Home.java` + `ServerPages` HTML templates from `IptvServer.kt`; added desktop `GET /info` & `GET/POST /api/connect` receiver endpoints + tests): merged in #160.
  - #44 (Issue template strictness `config.yml`/`channel.yml`/`feature_request.yml`, README update wording, SECURITY.md 7-day response SLA, and tasks/handsoff/CHANGELOG sync): docs sync PR.
- **Open PRs:** none.
- **Private security advisories:** Patches merged to main in #124, #125, #126. Ready to publish with 3.2.1 release.
- **Review board** (DevOps, UI/UX, CTO, CEO, Creative, Frontend, SWE, QA) filed #88 and #90–#111, plus comments on #43 #45 #56 #57 #64 #65 #71 #75 #76 #77 #78 #81 #90 #96 #100. Priorities are in `agents/tasks.md`; direction is in `agents/plans.md`.

## Rules of the house (don't break these)

1. **Attribution:** commits end with `Co-Authored-By: bensoncloud <bensoncloud@bot.cue.im>`. Never credit Claude in commits, PR bodies or merge commits. Squash-merge with a custom body.
2. **No release unless the owner asks.** Merges need the owner's explicit "merge #N".
3. **Security goes to private advisories** (SECURITY.md), never public issues.
4. **Mochy Music (GPL-3.0) is design inspiration only.** Don't copy its code or assets (Tahoe is MIT).
5. **Users' personal data stays private.** No owner name, city or photos in public builds (#78).
6. **No force-push** unless the owner says so explicitly (the history rewrite was a one-off, approved).

## Build & test

```bash
# macOS: git needs CLT if the Xcode license isn't accepted
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
# JDK 17 (temporary download; any JDK 17 works)
export JAVA_HOME=<jdk17>/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :core:test :desktop:test :launcher:assembleDebug :companion:assembleDebug
```

- **Real phone:** the debug app is `tech.kousozo.tahoetv.dev`, installed next to the release app (`adb install -r companion/build/outputs/apk/debug/companion-debug.apk`).
- **TV:** test on the real Mi TV Stick. TV onboarding (#85) and the TV slate (#87) **haven't run on a TV yet**.
- **Desktop:** `--selftest` in CI (`selftest.yml`). The desktop onboarding was only rendered offscreen; it hasn't been clicked through by hand.
- **Don't** toggle airplane mode on a test phone on café Wi-Fi: it may need the captive portal login again.

## Gotchas learned the hard way

- JavaFX can't play MP2/AAC-LATM/HEVC or ad-stitched streams (#75). Forcing every stream through `HlsProxy` made 17 FAST channels fail, so streams without headers stay direct.
- `HlsProbe` must treat "200 + HTML" and "all variants dead" as DEAD, or channels look LIVE but never play.
- Channel numbers are list positions today (#90, #94). Favourites and categories renumber them.
- Python edits that insert `// comments` mid-line can comment out a closing brace. Build before committing.
- The scratchpad gets wiped on app restart, so keep important artefacts in the repo or in issues.

## Next 3 concrete steps

1. Real-device testing on Mi TV Stick: TV onboarding (#85), TV slate (#87), standby Adzan (#43), Simple Mode & native TV controls (#90, #34), and phone↔TV handoff (#93, #88).
2. Finish **Tahoe Connect P2–P3 (#88)** on top of protocol v2 (#93): desktop target support, queue/remote polish, and end-to-end real-device verification.
3. Continuous engineering health: `Home.java`/`IptvServer.kt` split (#95) and remaining offline/Android unit coverage. Note locally: Gradle 8.14 needs JDK 17 (e.g. `~/.gradle/jdks/temurin-17/Contents/Home`) whenever build scripts change, as Android Studio's JBR 25 hits Groovy class version 69.
