# Channel Sources & Takedown Policy

**English** · [Bahasa Indonesia](#bahasa-indonesia) · [中文](#中文)

[← README](../README.md) · [Architecture](ARCHITECTURE.md) · [Security](../SECURITY.md)

---

## English

### 1. Zero hosted streams

Tahoe TV is strictly an open-source launcher, remote, and media player frontend. **The Tahoe TV repository, releases, and website host, store, proxy externally, or redistribute zero video or audio streams.** All live TV playback connects directly from the user's device (or local home LAN relay) to publicly listed broadcaster or playlist endpoints.

### 2. Built-in public sources

Out of the box, `:core` reads only public, community-maintained playlists and XMLTV programme guides:

- **[iptv-org/iptv](https://github.com/iptv-org/iptv)** — country-based public playlists (`https://iptv-org.github.io/iptv/countries/<xx>.m3u`) for international and Indonesian free-to-air channels.
- **[yositapjani/iptv-indonesia](https://github.com/yositapjani/iptv-indonesia)** — community-tested Indonesian free-to-air channel backups.
- **[dhasap/dhanytv](https://github.com/dhasap/dhanytv)** — only its explicitly whitelisted free Indonesian broadcast groups (`Indonesia Channels`, `TVRI`, `Local Channels`, `Nasional`, `TV HIBURAN`).
- **XMLTV guides (schedules only, no media streams):** [epg.pw](https://epg.pw) and `dhanytv/epg.xml`.

Users can also supply their own local `.m3u`/`.m3u8` files or playlist URLs on their own device; those custom playlists are never uploaded or shared.

### 3. Free-to-air only & no DRM circumvention

Every public source passes through [`Sources.free()`](../core/src/main/kotlin/tech/kousozo/tahoetv/core/Sources.kt) before appearing in the TV, phone, or desktop channel list:

- **No DRM playback:** Channels with encryption license keys or DRM metadata are filtered out by default (`!c.drm`). Tahoe TV never strips, cracks, or circumvents DRM.
- **No pay-TV brands:** Paid cable/satellite brands (e.g., HBO, beIN, SPOTV, Disney, AXN, Fox) are filtered out regardless of playlist group labels.
- **No unauthorized restream proxies:** Known pay-TV restream domains and pirate relay hosts are excluded by filter rules.
- **No static VOD files:** Plain `.mp4`, `.mkv`, `.avi`, `.mov`, `.webm`, `.mp3`, `.m4a` entries are excluded from live sources.

### 4. Takedown & exclusion policy (7-day response)

If you are a broadcaster, rights holder, or authorized representative and believe a public stream URL included via any built-in source should not be reachable through Tahoe TV:

1. **Report here:** Open a [GitHub issue](https://github.com/fk0u/cleanyourtv-tahoe/issues/new/choose) (or email/contact the maintainer listed in [MAINTAINERS.md](../MAINTAINERS.md)) with the **channel name**, **stream URL / host**, and **proof of authority**.
2. **Removal within 7 days:** Verified exclusion requests (blocking a host/channel or updating source group filters) are applied and merged within **7 calendar days**.
3. **Upstream removal:** Because built-in lists originate from community indexers (`iptv-org`, etc.), please also file a removal request upstream (for instance at [iptv-org/iptv](https://github.com/iptv-org/iptv/issues)) so the link is removed across all players that read those public indexes.

### 5. App store & F-Droid stance

Tahoe TV releases contain **no embedded streams, no scraped API keys, no proprietary blobs for DRM circumvention, and no ad SDKs**. Every build is reproducible from source under the MIT License (`gradlew :launcher:assembleRelease`, `:companion:assembleRelease`, `:desktop:packageDistributionForCurrentOS`), with end-users able to inspect, disable, or replace every playlist source.

---

## Bahasa Indonesia

### 1. Tidak ada siaran yang di-host

Tahoe TV murni berfungsi sebagai launcher, remote, dan pemutar media open-source. **Repositori, rilis, dan situs web Tahoe TV tidak meng-host, menyimpan, me-relay ke publik, atau mendistribusikan ulang stream video maupun audio apa pun.** Pemutaran Live TV langsung terhubung dari perangkat pengguna ke endpoint publik yang sudah tersedia di daftar putar komunitas.

### 2. Sumber publik bawaan

Secara bawaan, modul `:core` hanya membaca daftar putar publik dan panduan acara XMLTV:

- **[iptv-org/iptv](https://github.com/iptv-org/iptv)** — daftar putar per negara untuk saluran gratis (free-to-air) internasional dan Indonesia.
- **[yositapjani/iptv-indonesia](https://github.com/yositapjani/iptv-indonesia)** — cadangan saluran free-to-air Indonesia yang diuji komunitas.
- **[dhasap/dhanytv](https://github.com/dhasap/dhanytv)** — hanya grup free-to-air Indonesia yang diizinkan (`Indonesia Channels`, `TVRI`, `Local Channels`, `Nasional`, `TV HIBURAN`).
- **Panduan XMLTV (hanya jadwal acara):** [epg.pw](https://epg.pw) dan `dhanytv/epg.xml`.

Pengguna juga dapat menambahkan file `.m3u`/`.m3u8` atau URL playlist pribadi di perangkat masing-masing tanpa pernah diunggah ke luar perangkat.

### 3. Khusus siaran gratis & tanpa pembobolan DRM

Setiap sumber publik disaring oleh [`Sources.free()`](../core/src/main/kotlin/tech/kousozo/tahoetv/core/Sources.kt):

- Saluran berenkripsi/DRM ditolak (`!c.drm`). Tahoe TV tidak pernah melewati atau membobol DRM.
- Merek TV berbayar dan host restream tidak sah diblokir oleh filter otomatis.
- File video/audio statis (`.mp4`, `.mkv`, dll.) tidak dimasukkan ke siaran langsung.

### 4. Kebijakan penghapusan (takedown maksimal 7 hari)

Apabila Anda pemegang hak siar atau pihak resmi stasiun TV dan menemukan tautan siaran yang perlu diblokir atau dihapus dari konfigurasi bawaan Tahoe TV:

1. Buka [GitHub issue baru](https://github.com/fk0u/cleanyourtv-tahoe/issues/new/choose) (atau hubungi maintainer di [MAINTAINERS.md](../MAINTAINERS.md)) dengan menyertakan **nama saluran**, **URL/domain sumber**, dan **bukti kewenangan**.
2. Permintaan yang terverifikasi akan ditindaklanjuti (penambahan aturan filter/penghapusan sumber) dalam **7 hari kalender**.
3. Silakan laporkan juga ke repositori hulu seperti [iptv-org/iptv](https://github.com/iptv-org/iptv/issues) agar tautan terhapus di sumber asalnya.

---

## 中文

### 1. 不托管任何视频流

Tahoe TV 仅是一个开源的 Android TV 桌面、遥控器与播放器前端。**本项目的代码库、发行版安装包和官方网站不托管、不存储、不对外分发任何音视频直播流。** 所有直播播放均由用户设备直连公开的广播流或社区播放列表地址。

### 2. 内置公开数据源

`:core` 模块默认仅读取公开的社区播放列表与 XMLTV 节目单（仅含节目时间表）：

- **[iptv-org/iptv](https://github.com/iptv-org/iptv)** — 各国家/地区的免费公共频道列表。
- **[yositapjani/iptv-indonesia](https://github.com/yositapjani/iptv-indonesia)** — 社区维护的印度尼西亚免费频道备用源。
- **[dhasap/dhanytv](https://github.com/dhasap/dhanytv)** — 仅限其免费公共印度尼西亚频道分组。
- **XMLTV 节目单（仅节目表，无视频流）：** [epg.pw](https://epg.pw) 与 `dhanytv/epg.xml`。

### 3. 仅限免费频道，绝不绕过 DRM

所有内置公开列表均通过 [`Sources.free()`](../core/src/main/kotlin/tech/kousozo/tahoetv/core/Sources.kt) 过滤：不播放任何加密/DRM 频道，不收录付费电视频道品牌，并自动屏蔽已知未授权转播域名与静态视频文件。

### 4. 版权下架与屏蔽政策（7 日内响应）

如果您是广播机构或版权方代表，希望从 Tahoe TV 的默认公开源过滤列表中添加屏蔽或移除特定频道/域名：

1. 请提交 [GitHub Issue](https://github.com/fk0u/cleanyourtv-tahoe/issues/new/choose) 或联系 [MAINTAINERS.md](../MAINTAINERS.md) 中的维护者，附带**频道名称**、**流媒体域名或 URL** 以及**权属证明**。
2. 核实后的下架请求将在 **7 个自然日**内完成屏蔽更新。
3. 同时建议向上游社区清单（例如 [iptv-org/iptv](https://github.com/iptv-org/iptv/issues)）提交移除请求，以便从源头索引中删除。
