# Security

[English](#english) · [Bahasa Indonesia](#bahasa-indonesia)

## English

Found a security hole (for example access to a TV without pairing, a token leak, or running commands from the network)?
**Don't open a public issue.** Report it privately through [GitHub Security Advisories](https://github.com/fk0u/cleanyourtv-tahoe/security/advisories/new).

Include the version, platform, steps to reproduce and the impact. Reports are acknowledged within 7 days and fixed in the next release.

Design note: the phone ↔ TV server (port 8765) and the desktop IPTV server (port 8088) are meant for the local network (LAN) only.
Don't forward these ports to the internet.

Supported versions: the latest release. Fixes aren't backported.

## Bahasa Indonesia

Menemukan celah keamanan (misalnya akses ke TV tanpa pairing, kebocoran token, atau eksekusi perintah dari jaringan)?
**Jangan buka issue publik.** Laporkan secara privat lewat [GitHub Security Advisories](https://github.com/fk0u/cleanyourtv-tahoe/security/advisories/new).

Sertakan versi, platform, langkah untuk mengulang, dan dampaknya. Laporan akan dikonfirmasi maksimal dalam 7 hari dan diperbaiki di rilis berikutnya.

Catatan desain: server HP ↔ TV (port 8765) dan server IPTV desktop (port 8088) dimaksudkan hanya untuk jaringan lokal (LAN).
Jangan meneruskan port ini ke internet.

Versi yang didukung: rilis terbaru. Perbaikan tidak di-backport.
