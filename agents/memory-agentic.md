# memory-agentic.md

```
01010100 01000001 01001000 01001111 01000101   ; "TAHOE"
⟦MEM v1 · scope=repo:fk0u/cleanyourtv-tahoe · ts=2026-11-13 · ttl=∞ · trust=verified-in-code⟧
```

> Dense, agent-readable memory. One fact per line. `⊢` = verified fact, `⚑` = rule, `△` = open risk, `→` = pointer.
> Agents: read this first, then `agents/handsoff.md`. Humans: it's the same facts as handsoff.md, just shorter.

## ⟦IDENTITY⟧
```
⊢ product      = Tahoe TV · MIT · open to PRs worldwide · langs{en,id,zh}
⊢ origin       = owner's side project from boredom + frustration: boring stock TV UI, hard for elderly; live TV must feel native
⊢ north_star   = "TV anyone can use, on a $20 stick, controlled like a real TV"
⊢ owner        = @fk0u  · site = tahoe.kousozo.tech (deploy kit ready, secrets not set)
⊢ release      = v3.1.0 (2026-11-12) · next = 3.1.x trust patch
```

## ⟦TOPOLOGY⟧
```
core/       Kotlin JVM  → Sources · LocalTv · Epg · HlsProbe · SourceHealth · Region · Viewer · Prayer · OnboardingText · Slate · TvLink · Web
launcher/   Java Views  → tv.clean.home.* · Home(1076L) · LiveTv · Onboarding · TvViewer · Companion(ws:8765, mDNS _tahoetv._tcp)
companion/  Compose     → tech.kousozo.tahoetv.* · Playback · Live · Welcome(onboarding) · Sync · debug pkg = *.dev
desktop/    Compose+FX  → IptvServer(:8088) · HlsProxy · PlayerWindow(JavaFX) · WebPlayer(Edge+hls.js on Windows) · Setup
website/    static      → index.html · assets/{app,i18n,style} · build.sh · website.yml (rsync over SSH)
```

## ⟦RULES⟧ (hard constraints)
```
⚑ ATTR      commit trailer = "Co-Authored-By: bensoncloud <bensoncloud@bot.cue.im>" ; NEVER mention Claude in commits/PRs/merges
⚑ MERGE     only after owner says "merge #N" ; squash with custom body
⚑ RELEASE   never unless owner asks
⚑ SECURITY  → private GitHub advisory, never public issue
⚑ LICENSE   Mochy Music = GPL-3.0 → inspiration only, copy nothing
⚑ PRIVACY   no owner name/city/photo in public builds or shared logs
⚑ FORCE     no force-push without explicit owner OK
⚑ LANG      talk to the owner in Bahasa Indonesia
```

## ⟦DECISIONS⟧
```
⊢ D1  channels per country = iptv-org countries/<cc>.m3u ; ID keeps curated extra sources
⊢ D2  onboarding = 10 steps, language first ; one Viewer JSON per device ; phone can push it to TV
⊢ D3  slate = apology in id+en+zh, viewer lang first, retry 30s × 20
⊢ D4  desktop HLS streams without headers play direct (proxying all broke 17 FAST channels)
⊢ D5  HlsProbe.DEAD = HTML-200 or all variants dead
⊢ D6  Tahoe Connect (#88) = receiver/controller model, protocol v2 (#93), stable channel ids (#94) first
```

## ⟦OPEN RISKS⟧
```
△ R1  CH+ inverted                         → #102
△ R2  EPG OOM on big guides                → #104
△ R3  launcher in desktop installers is debug-signed → #92
△ R4  security drafts                      → GHSA-34j8 · GHSA-x8v7 · GHSA-cp6v
△ R5  channel number = list position       → #90 · #94
△ R6  desktop can't play MP2/LATM/HEVC     → #75
△ R7  TV onboarding + TV slate never run on real TV
```

## ⟦POINTERS⟧
```
→ priorities   agents/tasks.md
→ direction    agents/plans.md
→ how-to       agents/handsoff.md
→ epics        #71 international · #77 onboarding · #88 Connect · #90 native TV
→ open PRs     #87 slate · #89 README worldwide
```

```
⟦EOF⟧ checksum: 0xC0FFEE · "nonton TV, mudah lagi · TV, made easy again · 看电视，再次变简单"
```
