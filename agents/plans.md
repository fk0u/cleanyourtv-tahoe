# plans.md — where Tahoe TV is going

> `[PLAN] horizon=3mo · north_star="TV anyone can use, on a $20 stick, controlled like a real TV" · audience=worldwide`

## 0 · Why (origin, keep it in every decision)

Tahoe TV began as a side project out of boredom and real frustration. Stock TV home screens were dull, full of ads,
and hard for older family members to use. Live TV should just work like TV: turn on, ▲ ▼, done. Everything below
serves three promises:

1. **Easy for everyone**, grandparents included.
2. **Light**: smooth on a 1 GB TV stick.
3. **Free and open, worldwide**: any country's free channels, in your language, MIT licensed, PRs from anywhere.

## 1 · Milestones

| Milestone | Goal | Exit criteria |
|---|---|---|
| **3.2.0** ✅ | Onboarding everywhere, channels for every country, 3-language slate | released 2026-11-13 |
| **3.2.x Trust patch** | A stranger can trust the APK | #102 #103 #78 #92 #104 #45 fixed; advisories GHSA-34j8/x8v7/cp6v fixed and published; slate follow-ups (#114) |
| **3.3 International** | A viewer in Taipei, Lagos or Lima gets a fully local first run | #71 #77 #56 (EN/ID/中文 strings) #80 #79 #94 #105 #81 #101 #99 done; website in 3 languages |
| **3.4 Simple & Connect** | "Works like a real TV" + "control it from your phone" | #90 Simple Mode + numbers; #93 protocol v2; #88 Tahoe Connect P0–P2; elderly test passes |
| **Later** | Nice-to-haves | parked list in tasks.md |

Release cadence: monthly, gated on green CI (#100, #57).

## 2 · Tahoe Connect (#88)

Spotify-Connect / AirPlay-style control of TV and desktop from the phone.

```
roles        Receiver (TV, desktop, later phone) · Controller (phone, desktop, web remote)
discovery    mDNS _tahoetv._tcp  TXT{id, name, proto=2, roles, caps}   (fallback: /24 scan, QR)
envelope     {v:2, id, type, from, ts, body}  → ack/error by id
messages     hello · device.state · session.load{channelId} · session.play|pause|stop|seek|volume
             session.state{rev,…} (full snapshot, receiver = source of truth) · session.transfer{to}
security     per-device tokens (not in URL), role checks (Kids), pairing approved on the TV
```

| Phase | Scope | Depends on |
|---|---|---|
| P0 | protocol v2 + hello/version (v1 still accepted) | #93 |
| P1 | TV receiver, `session.load/state`, "now on TV" bar on phone | #94 stable ids |
| P2 | mDNS device picker, many TVs (#37) | P1 |
| P3 | desktop receiver, handoff both ways, resume position | #75 Player interface |
| P4 | optional remote access, queue, multiview (#50) | — |

## 3 · Architecture moves (CTO)

- **Pure-Kotlin `core` as the shared brain:** the protocol codec (#93), the onboarding flow (#77), the dock grid model, and the slate retry state machine (#76). All of it gets JVM-tested.
- **Typed errors in `core`, localised in apps.** No UI literals in `core` (#56).
- **Desktop `Player` interface** with libmpv/libVLC (#75), which also serves as the Connect receiver state.
- **Break up the god objects** (#95): no file over ~400 lines.

## 4 · Ops (DevOps + QA)

- **One version source; the tag must match** (#100).
- **Tests and selftest before publishing; checksums verified by OTA** (#45).
- **Signing:**
  - the release key lives in a protected Environment, with SHA-pinned actions and Dependabot (GHSA-cp6v);
  - the launcher inside desktop installers is release-signed (#92).
- **Release checklist on real devices:** Mi TV Stick (Android 9, 1 GB), an Android 14 phone, macOS, Windows 10/11, plus the "elderly test".

## 5 · Community (CEO)

- **Translations:** Weblate, with EN / ID / zh-Hans seeded.
- **People and money:** MAINTAINERS.md, FUNDING.yml, English issue titles, 10+ `good first issue`s (#97).
- **Trust and naming:**
  - SOURCES.md and a takedown process (#101);
  - brand wording without Apple vocabulary (#91).
  - Decide on the repo rename `cleanyourtv-tahoe` → `tahoe-tv` (the owner's call).

## 6 · What we won't do

Paid channels, DRM circumvention, pirated sources, ads, tracking.
