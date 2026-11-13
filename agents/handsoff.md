# handsoff.md — pick up from here

> `[HANDOFF] from=review-board+main-agent · to=next human/agent · date=2026-11-13`

## State of the repo

- **main:** onboarding on phone, desktop and TV (#84, #85); desktop playback fixes (#82); debug build installs next to the release app (#86).
- **Released:** `v3.1.0`. Everything after it is unreleased: CHANGELOG `## Belum dirilis`.
- **Open PRs:**
  - **#87** slate in 3 languages (#76). Checked on a real phone (Android 14) and the web player. Follow-ups are in the #76 comments.
  - **#89** README worldwide: 中文, origin story, open to PRs.
  - The PR that adds this folder.
- **Private security advisories (drafts):** GHSA-34j8-5cp5-rcmv, GHSA-x8v7-r42c-7qqj, GHSA-cp6v-7p8f-hpvx. Fix in private, then publish; never move them to public issues.
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

1. Owner: review and merge #87 and #89; test #85/#87 on the Mi TV Stick.
2. The 3.1.x trust patch: #102, #103, #78, #92, #104, then the three advisories.
3. Start 3.2: #94 stable channel ids, then #56 strings in EN/ID/中文.
