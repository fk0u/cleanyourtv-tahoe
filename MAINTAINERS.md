# Maintainers

[← README](README.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md)

## Governance model

Tahoe TV follows a **benevolent maintainer** model under the [MIT License](LICENSE). Anyone is free to use, fork, redistribute, or open pull requests. Pull requests and issues are reviewed openly on GitHub.

- **Creator & Lead Maintainer:** Al-Ghani ([@fk0u](https://github.com/fk0u))

## Module ownership

| Module / Area | Scope | Primary contact |
|---|---|---|
| `:core` | Pure-JVM playlist parser, EPG streaming parser, HLS probe & recorder, region/i18n/onboarding data, security helpers | [@fk0u](https://github.com/fk0u) |
| `:launcher` | Android TV launcher (`minSdk 26`), ExoPlayer + FFmpeg live TV, Profiles/Kids, Control Center, ADB/WebSocket server | [@fk0u](https://github.com/fk0u) |
| `:companion` | Android phone app, Wi-Fi ADB installer, TV remote, QR pairing, mobile live TV player | [@fk0u](https://github.com/fk0u) |
| `:desktop` | Compose Multiplatform desktop app (macOS & Windows), JavaFX/Edge web player, LAN IPTV server & DVR | [@fk0u](https://github.com/fk0u) |
| `website/` | Landing page (`tahoe.kousozo.tech`), localization (`EN`, `ID`, `中文`), accessibility | [@fk0u](https://github.com/fk0u) |
| `docs/` | Multilingual documentation, architecture, channel source & takedown policy | [@fk0u](https://github.com/fk0u) |

## Review & release cadence

- All PRs must pass CI checks (`:core:test`, `:desktop:test`, Android debug/lint compilation, and website build where relevant).
- Channel source reports and takedown requests follow the 7-day policy in [docs/SOURCES.md](docs/SOURCES.md).
- Security vulnerability reports are handled privately through [GitHub Security Advisories](SECURITY.md).

## Becoming a maintainer

Contributors who repeatedly send high-quality pull requests, help triage issues/channel sources, or maintain a language pack or platform port (e.g., Google TV, webOS) may be invited by the lead maintainer to join module maintainership.
