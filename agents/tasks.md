# tasks.md — Tahoe TV

> `[STATE] 0x04 · release=v3.2.0 · board=8 · issues=+24 · advisories=3 (private, patched) · updated=2026-11-14`
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

- [ ] #71 epic · #77 onboarding (TV real-device test pending) · #56 strings EN/ID/中文 · #80 de-Indonesianise core · #79 prayer/time zone per region
- [x] #94 Stable channel IDs (favourites, numbers, handoff, EPG)  ⟨CTO⟩ {M} · merged in #132 (`tvg-id`/slug `channelId`, `Viewer` schema v2)
- [x] #105 http↔https redirects counted as dead  ⟨SWE⟩ {S} · merged in #131 (`Web.openFollowingRedirects`)
- [x] #81 Bundle hls.js/Shaka (no CDN), headless JS smoke test  ⟨Frontend·QA⟩ {S} · merged in #133 (`/assets/*` in `desktop`)
- [ ] #75 Desktop playback engine (libmpv/libVLC behind a `Player` interface)  ⟨CTO⟩ {L}
- [x] #101 SOURCES.md + takedown policy, no DRM bypass, app-store stance  ⟨CEO⟩ {S} · merged in #134 (`docs/SOURCES.md`)
- [ ] #91 Brand wording without Apple vocabulary, palette, icon  ⟨Creative⟩ {M}
- [ ] #99 Demo mode + clean screenshot set in EN/ID/中文  ⟨Creative⟩ {M}
- [x] #110 Website a11y + 中文 button  ⟨Frontend⟩ {S} `good first issue` · merged in #135
- [x] #97 Community plumbing: Weblate, MAINTAINERS, FUNDING, English issues  ⟨CEO·DevOps⟩ {S} · merged in #134 (`MAINTAINERS.md`, `FUNDING.yml`, English PR template)

## 🟡 NEXT — `3.4 Simple & Connect`

- [ ] #90 Native-TV controls + Simple Mode (numbers, CH±, last channel, big text)  ⟨CEO·UI/UX⟩ {M}
- [ ] #64 Accessibility (TalkBack, focus ring, 48 dp, font scale) · #65 plain language
- [ ] #93 Phone↔TV protocol v2 (typed, versioned, acks, keep-alive)  ⟨CTO⟩ {M}
- [ ] #88 **Tahoe Connect** — control the TV/desktop from the phone (Spotify Connect / AirPlay style)  ⟨CTO·CEO⟩ {L}
- [ ] #111 Phone→TV onboarding sync: merge, not overwrite  ⟨QA⟩ {S}
- [ ] #96 Thread-safety in link/players · #107 Sync reconnect loop · #106 SourceHealth save race
- [ ] #108 TV guide performance (RecyclerView) · #109 phone/desktop recomposition + image caches

## 🔵 ENGINEERING HEALTH (continuous)

- [ ] #100 Release pipeline: one version source, tests before publish, branch protection, Dependabot  ⟨DevOps·QA⟩
- [ ] #57 Android tests + lint in CI, move logic into `core`  ⟨QA·CTO⟩
- [ ] #95 Split `Home.java` / `IptvServer.kt`  ⟨CTO⟩ `help wanted`
- [ ] #98 Milestones + Now/Next/Later roadmap  ⟨CEO⟩

## ⚪ LATER (parked, not closed)

#62 · #61 · #50 · #39 · #38 · #40 · #26 · #49 · #52 · #53 · #54 · #47 · #48 · #51 · #36 · #32/#33 (`help wanted`, upstream)

## ✅ Done this session (for context)

- `#69 #70 #72 #73 #74 #82 #83 #84 #85 #86 #87 #89 #112 #113` merged · `v3.1.0` and **`v3.2.0`** released · history rewritten (no co-author lines to Claude)
- #76 (slate) closed by #87; its follow-ups live in #114
- Review-board notes posted on merged PRs #82 #84 #85 #87 #89
- Open PRs: none (besides the one updating this file)
