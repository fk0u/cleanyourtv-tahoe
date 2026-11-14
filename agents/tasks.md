# tasks.md — Tahoe TV

> `[STATE] 0x05 · release=v3.3.0 (code 26) · updated=2026-11-14`
> One list, ordered by what unblocks the most. Every line links to its issue; tick it when the issue closes.
> Format: `[ ] #N  title  ⟨roles⟩  {size: S/M/L}`

## 🔴 NOW — trust patch `3.2.x` (ship before any international push)

- [x] #102 CH+ goes down a channel (inverted on TV, phone, desktop)  ⟨UI/UX⟩ {S} · merged in #116
- [x] #103 Red colour key = "Exit" in the legend but marks the stream "not live"  ⟨UI/UX⟩ {S} · merged in #119
- [x] #78 Owner's name/photo/city as public defaults (+ no personal photos in screenshots → #99)  ⟨CEO·Creative⟩ {S} · merged in #120
- [x] #92 Desktop installers bundle a debug-signed launcher  ⟨DevOps⟩ {S} · merged in #121
- [x] #104 EPG loaded whole into memory → OOM with big guides  ⟨SWE⟩ {M} · merged in #117
- [x] #114 Slate follow-ups: OSD covers slate on TV, wrong reason, calmer layout, one tested state machine  ⟨UI/UX·Creative·QA⟩ {S} · merged in #118
- [x] #45 Truncated update accepted as complete (+ checksums)  ⟨DevOps·QA⟩ {S} · merged in #123
- [x] Private advisories GHSA-34j8 (phone↔TV link, #125), GHSA-x8v7 (desktop server, #124), GHSA-cp6v (release key, #126)  ⟨SWE·CTO·DevOps⟩ {M}
- [ ] Real-device pass on the Mi TV Stick: TV onboarding (#85) and TV slate (#87) have never run on a TV  ⟨QA⟩ {S}

## 🟠 NEXT — `3.3 International`

- [ ] #71 epic · #77 onboarding (TV real-device test pending)
- [x] #80 De-Indonesianise `:core` (`Epg.norm`, `Epg.byId`/`tvg-id`, `Web` `lang`/`watchRegion`, `LocalTv.arrange(..., countries)`)  ⟨CTO·SWE⟩ {M} · merged in #137
- [x] #79 Prayer method & time zone per region (`TvViewer.zone` across desktop/companion/launcher `Prayer`)  ⟨UI/UX·SWE⟩ {S} · merged in #137
- [x] #56 Trilingual EN/ID/中文 strings (`I18n` in `:core`, `PlayPrefs`/`Scan`/`TvLink`/`HlsRecorder`, desktop & companion main UI)  ⟨UI/UX·Frontend⟩ {M} · merged in #138
- [x] #94 Stable channel IDs (favourites, numbers, handoff, EPG)  ⟨CTO⟩ {M} · merged in #132 (`tvg-id`/slug `channelId`, `Viewer` schema v2)
- [x] #105 http↔https redirects counted as dead  ⟨SWE⟩ {S} · merged in #131 (`Web.openFollowingRedirects`)
- [x] #81 Bundle hls.js/Shaka (no CDN), headless JS smoke test  ⟨Frontend·QA⟩ {S} · merged in #133 (`/assets/*` in `desktop`)
- [x] #75 Desktop player resilience (`HlsProxy.open` redirect delegation, `trimDiscontinuity`, and `WebPlayer` fallback on verified live/unsupported failures)  ⟨CTO⟩ {L} · merged in #140
- [x] #101 SOURCES.md + takedown policy, no DRM bypass, app-store stance  ⟨CEO⟩ {S} · merged in #134 (`docs/SOURCES.md`)
- [x] #91 Brand wording without third-party trademarks, tagline localization (`Brand.tagline`), CSS motion & Dusk/Cyan-Deep tokens  ⟨Creative⟩ {M} · merged in #139
- [x] #99 Demo mode (`Demo` in `:core`, `--demo`/`-Dtahoe.demo=true` in desktop), `og:image` & contributing screenshot privacy rules  ⟨Creative⟩ {M} · merged in #139
- [x] #110 Website a11y + 中文 button  ⟨Frontend⟩ {S} `good first issue` · merged in #135
- [x] #97 Community plumbing: Weblate, MAINTAINERS, FUNDING, English issues  ⟨CEO·DevOps⟩ {S} · merged in #134 (`MAINTAINERS.md`, `FUNDING.yml`, English PR template)

## 🟡 NEXT — `3.4 Simple & Connect`

- [x] #90 Native-TV controls + Simple Mode (numbers, CH±, last channel, big text, #34)  ⟨CEO·UI/UX⟩ {M} · merged in #156
- [x] #64 Accessibility (TalkBack, focus ring, 48 dp, font scale) · merged in #156
- [x] #65 Plain-language UI pass (glossary + desktop/TV/phone jargon removal, #145)
- [x] #93 Phone↔TV protocol v2 (typed, versioned, acks, keep-alive)  ⟨CTO⟩ {M} · merged in #155
- [x] #88 **Tahoe Connect** — control the TV/desktop from the phone (Spotify Connect / AirPlay style)  ⟨CTO·CEO⟩ {L} · P0–P1 merged in #155; P2–P3 (mDNS discovery, multi-TV #37, Continue-on-Phone handoff #63, desktop `/info` & `/api/connect` receiver) merged in #158, #159, #160
- [x] #37 Multi-TV management (`PairedDevices`, TV `/devices` & `/devices/revoke`, `NsdManager` mDNS discovery, `TvDevicesSheet`) · merged in #158
- [x] #63 Mobile PiP, double-tap seek/zoom gestures, system channel sharing & Continue-on-Phone handoff · merged in #159
- [x] #111 Phone→TV onboarding sync: merge, not overwrite (#143)  ⟨QA⟩ {S}
- [x] #96 Thread-safety in link/players · #107 Sync reconnect loop · #106 SourceHealth save race (#142, #143)
- [x] #108 TV guide performance (windowed inflate + single-row refresh) · #109 phone/desktop recomposition + image caches (#144)

## 🔵 ENGINEERING HEALTH (continuous)

- [x] #100 Release pipeline: one version source, tests before publish, Dependabot (#146)  ⟨DevOps·QA⟩
- [x] #57 Android lint in CI + website bundle check on PRs & main (#146)  ⟨QA·CTO⟩
- [x] #95 Split `Home.java` / `IptvServer.kt` (`DockGrid` & `ServerPages` in #160; focus navigation math, `RemoteController.java` & `ServerRelay.kt` in #162)  ⟨CTO⟩
- [x] #98 Now/Next/Later EN/ID roadmap & release cadence (#146)  ⟨CEO⟩
- [x] #44 Docs & issue template audit (`config.yml`, `channel.yml`, `feature_request.yml`, `README`, `SECURITY.md`) · merged in #161
- [x] Debian / Ubuntu (`.deb`) desktop packaging (`TahoeTV-linux-amd64.deb`), Linux OTA asset, Chromium/Chrome `--app` & `xdg-open` launch, and web/docs (#163)  ⟨DevOps·UI/UX⟩

## ⚪ LATER (parked, not closed)

#62 · #61 · #50 · #39 · #38 · #40 · #26 · #49 · #52 · #53 · #54 · #47 · #48 · #51 · #36 · #32/#33 (`help wanted`, upstream)

## ✅ Done this session (for context)

- `#37 #44 #63 #88 #95` closed · `#158 #159 #160 #161 #162 #163` merged · **`v3.3.0`** (code `26`) prepared with Debian/Ubuntu `.deb` desktop support
- Open PRs: none (besides the release prep PR)
