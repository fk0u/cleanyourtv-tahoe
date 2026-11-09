#!/usr/bin/env python3
"""Tahoe IPTV Server & Streaming Relay with DRM Support

Loads and merges M3U playlists (koutv.m3u, anotheriptv.m3u), preserves headers
and DRM metadata (ClearKey, Widevine, MPEG-DASH), serves standard M3U/EPG
endpoints with #KODIPROP tags, and acts as a reverse proxy for HLS and DASH.

Usage:
    python3 tools/iptv_server.py [playlists...] [--port 8088] [--host 0.0.0.0]
"""

import argparse
import base64
import html
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import HTTPServer, SimpleHTTPRequestHandler
from socketserver import ThreadingMixIn

URI_ATTR = re.compile(r'URI="([^"]+)"')
EXTINF_ATTR = re.compile(r'([\w-]+)="([^"]*)"')

def get_lan_ip():
    """Find the best local LAN IP address."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

class Channel:
    def __init__(self, cid, name, url, logo=None, group=None, tvg_id=None, headers=None,
                 drm_type=None, drm_key=None, source=""):
        self.cid = cid
        self.name = name.strip()
        self.url = url.strip()
        self.logo = logo.strip() if logo else None
        self.group = group.strip() if group else "General"
        self.tvg_id = tvg_id.strip() if tvg_id else None
        self.headers = headers or {}
        self.drm_type = drm_type.strip() if drm_type else None
        self.drm_key = drm_key.strip() if drm_key else None
        self.is_mpd = ".mpd" in self.url.lower().split("?")[0]
        self.is_vod = "vod" in self.group.lower() or self.url.lower().split("?")[0].endswith((".mp4", ".mkv", ".avi", ".mov", ".webm"))
        self.source = source

    def to_dict(self, base_url):
        ext = ".mpd" if self.is_mpd else ".m3u8"
        return {
            "id": self.cid,
            "name": self.name,
            "stream_url": f"{base_url}/live/{self.cid}{ext}",
            "raw_url": self.url,
            "logo": self.logo,
            "group": self.group,
            "tvg_id": self.tvg_id,
            "has_headers": bool(self.headers),
            "drm_type": self.drm_type,
            "drm_key": self.drm_key,
            "is_mpd": self.is_mpd,
            "is_vod": self.is_vod,
            "source": self.source
        }

def parse_playlist(file_path, existing_ids):
    """Parse M3U/M3U8 file extracting channels, headers, and DRM license keys."""
    if not os.path.exists(file_path):
        return []

    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.read().splitlines()

    channels = []
    source_name = os.path.basename(file_path)
    info = None
    headers = {}
    group = None
    drm_type = None
    drm_key = None

    for line in lines:
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF:"):
            info = line
        elif line.startswith("#EXTVLCOPT:"):
            opt = line[len("#EXTVLCOPT:"):].strip()
            if "=" in opt:
                k, v = opt.split("=", 1)
                kl = k.strip().lower()
                if kl in ("http-referrer", "http-referer"):
                    headers["Referer"] = v.strip().strip('"')
                elif kl == "http-user-agent":
                    headers["User-Agent"] = v.strip().strip('"')
                elif kl == "http-origin":
                    headers["Origin"] = v.strip().strip('"')
        elif line.startswith("#EXTHTTP:"):
            try:
                j = json.loads(line[len("#EXTHTTP:"):].strip())
                for k, v in j.items():
                    kl = k.strip().lower()
                    if kl in ("referer", "referrer"):
                        headers["Referer"] = str(v).strip()
                    elif kl == "user-agent":
                        headers["User-Agent"] = str(v).strip()
                    elif kl == "origin":
                        headers["Origin"] = str(v).strip()
                    else:
                        headers[k] = str(v).strip()
            except Exception:
                pass
        elif line.startswith("#KODIPROP:"):
            prop = line[len("#KODIPROP:"):].strip()
            pl = prop.lower()
            if pl.startswith("inputstream.adaptive.license_type="):
                drm_type = prop.split("=", 1)[1].strip()
            elif pl.startswith("inputstream.adaptive.license_key="):
                drm_key = prop.split("=", 1)[1].strip()
            elif pl.startswith("inputstream.adaptive.stream_headers="):
                raw_h = prop.split("=", 1)[1]
                for part in re.split(r"[&|]", raw_h):
                    if "=" in part:
                        k, v = part.split("=", 1)
                        kl = k.strip().lower()
                        if kl in ("referer", "referrer"):
                            headers["Referer"] = v.strip()
                        elif kl == "user-agent":
                            headers["User-Agent"] = v.strip()
                        elif kl == "origin":
                            headers["Origin"] = v.strip()
        elif line.startswith("#EXTGRP:"):
            group = line.split(":", 1)[1].strip()
        elif line.startswith("#"):
            continue
        elif info:
            # line is stream URL
            attrs = dict(EXTINF_ATTR.findall(info[:info.rfind(",") if "," in info else len(info)]))
            if "http-referrer" in attrs and "Referer" not in headers:
                headers["Referer"] = attrs["http-referrer"]
            if "http-user-agent" in attrs and "User-Agent" not in headers:
                headers["User-Agent"] = attrs["http-user-agent"]

            name = info[info.rfind(",") + 1:].strip() if "," in info else attrs.get("tvg-name", line)
            logo = attrs.get("tvg-logo")
            grp = (attrs.get("group-title") or group or "General").split(";")[0].strip()
            tvg_id = attrs.get("tvg-id")

            slug = re.sub(r"[^a-zA-Z0-9]+", "-", name.lower()).strip("-")
            if not slug or len(slug) < 2:
                slug = "channel"
            cid = slug
            idx = 1
            while cid in existing_ids:
                idx += 1
                cid = f"{slug}-{idx}"
            existing_ids.add(cid)

            ch = Channel(
                cid=cid,
                name=name,
                url=line,
                logo=logo,
                group=grp,
                tvg_id=tvg_id,
                headers=dict(headers),
                drm_type=drm_type,
                drm_key=drm_key,
                source=source_name
            )
            channels.append(ch)

            info = None
            headers = {}
            group = None
            drm_type = None
            drm_key = None
        else:
            headers = {}
            group = None
            info = None
            drm_type = None
            drm_key = None

    return channels

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class IptvHandler(SimpleHTTPRequestHandler):
    server_version = "TahoeIPTV/2.8"
    channels = []
    by_id = {}
    sources = []
    epg_url = "https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml"
    start_time = time.time()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def do_HEAD(self):
        self.do_GET()

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Range, User-Agent, Referer, Accept, Content-Type")
        self.send_header("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")

    def get_base_url(self):
        host_header = self.headers.get("Host")
        if host_header:
            return f"http://{host_header}"
        return f"http://{get_lan_ip()}:{self.server.server_address[1]}"

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        path = url.path
        query = urllib.parse.parse_qs(url.query)

        if path in ("/", "/index.html"):
            self.serve_dashboard()
        elif path in ("/playlist.m3u", "/all.m3u", "/iptv.m3u"):
            self.serve_playlist(query)
        elif path.endswith(".m3u") and any(path == f"/{s}" for s in self.sources):
            src = path.lstrip("/")
            self.serve_playlist(query, source_filter=src)
        elif path == "/epg.xml":
            self.serve_epg()
        elif path.startswith("/live/") and (path.endswith(".m3u8") or path.endswith(".mpd")):
            if path.endswith(".mpd"):
                cid = path[len("/live/"): -len(".mpd")]
                self.serve_live_mpd(cid)
            else:
                cid = path[len("/live/"): -len(".m3u8")]
                sub_url = query.get("sub", [None])[0]
                self.serve_live_stream(cid, sub_url)
        elif path == "/segment":
            encoded_url = query.get("u", [None])[0]
            cid = query.get("cid", [None])[0]
            self.serve_segment(encoded_url, cid)
        elif path == "/api/channels":
            self.serve_api_channels(query)
        elif path == "/api/status":
            self.serve_api_status()
        else:
            self.send_error(404, "Not Found")

    def serve_dashboard(self):
        base_url = self.get_base_url()
        groups = sorted(list({c.group for c in self.channels if c.group}))
        total_channels = len(self.channels)
        with_headers = sum(1 for c in self.channels if c.headers)
        drm_count = sum(1 for c in self.channels if c.drm_type or c.drm_key)

        html_content = f"""<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tahoe IPTV Server</title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%230A84FF' stroke-width='2'><rect width='20' height='15' x='2' y='7' rx='2'/><polyline points='17 2 12 7 7 2'/></svg>">
<script src="https://cdn.jsdelivr.net/npm/shaka-player@latest/dist/shaka-player.compiled.js"></script>
<style>
  :root {{
    --bg: #07080d;
    --surface: rgba(255, 255, 255, 0.06);
    --surface-hover: rgba(255, 255, 255, 0.12);
    --border: rgba(255, 255, 255, 0.1);
    --text: #f5f5f7;
    --dim: #86868b;
    --accent: #0a84ff;
    --accent-glow: rgba(10, 132, 255, 0.35);
    --green: #30d158;
    --purple: #bf5af2;
    --orange: #ff9f0a;
  }}
  * {{ box-sizing: border-box; margin: 0; padding: 0; -webkit-font-smoothing: antialiased; }}
  body {{
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, sans-serif;
    min-height: 100vh;
    padding: 24px;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }}
  header {{
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 16px;
    border-bottom: 1px solid var(--border);
    padding-bottom: 18px;
  }}
  .brand {{ display: flex; align-items: center; gap: 14px; }}
  .brand svg {{ width: 34px; height: 34px; stroke: var(--accent); }}
  .brand h1 {{ font-size: 24px; font-weight: 700; letter-spacing: -0.5px; }}
  .badge-live {{
    background: #ff453a;
    color: white;
    font-size: 11px;
    font-weight: 700;
    padding: 3px 8px;
    border-radius: 6px;
  }}
  .stats {{ display: flex; gap: 16px; color: var(--dim); font-size: 13px; }}
  .stats strong {{ color: var(--text); }}

  .quick-links {{
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 14px 18px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
  }}
  .link-group {{ display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }}
  .btn {{
    background: var(--surface);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 8px 14px;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s ease;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    text-decoration: none;
  }}
  .btn:hover {{ background: var(--surface-hover); border-color: rgba(255, 255, 255, 0.25); }}
  .btn.primary {{ background: var(--accent); color: white; border-color: var(--accent); }}
  .btn.primary:hover {{ background: #0071e3; }}

  .main-layout {{
    display: grid;
    grid-template-columns: 1fr 440px;
    gap: 20px;
    align-items: start;
  }}
  @media (max-width: 1080px) {{
    .main-layout {{ grid-template-columns: 1fr; }}
  }}

  /* Player Area */
  .player-card {{
    background: #000;
    border: 1px solid var(--border);
    border-radius: 16px;
    overflow: hidden;
    position: sticky;
    top: 20px;
  }}
  .player-box {{
    width: 100%;
    aspect-ratio: 16 / 9;
    background: #030305;
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
  }}
  video {{ width: 100%; height: 100%; object-fit: contain; }}
  .player-info {{ padding: 16px 18px; border-top: 1px solid var(--border); }}
  .player-info h3 {{ font-size: 17px; font-weight: 600; margin-bottom: 4px; }}
  .player-info p {{ font-size: 13px; color: var(--dim); }}

  /* Channels Explorer */
  .explorer {{ display: flex; flex-direction: column; gap: 14px; }}
  .search-input {{
    width: 100%;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 12px 16px;
    color: white;
    font-size: 15px;
    outline: none;
    transition: border 0.2s;
  }}
  .search-input:focus {{ border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }}

  .pills {{
    display: flex;
    gap: 8px;
    overflow-x: auto;
    padding-bottom: 4px;
    scrollbar-width: none;
  }}
  .pills::-webkit-scrollbar {{ display: none; }}
  .pill {{
    background: var(--surface);
    border: 1px solid var(--border);
    color: var(--dim);
    padding: 6px 14px;
    border-radius: 20px;
    font-size: 13px;
    white-space: nowrap;
    cursor: pointer;
    transition: all 0.2s;
  }}
  .pill:hover {{ color: var(--text); background: var(--surface-hover); }}
  .pill.active {{ background: var(--accent); color: white; border-color: var(--accent); }}

  .channel-grid {{
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 12px;
    max-height: 720px;
    overflow-y: auto;
    padding-right: 4px;
  }}
  .channel-card {{
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 12px 14px;
    display: flex;
    align-items: center;
    gap: 12px;
    cursor: pointer;
    transition: all 0.2s ease;
  }}
  .channel-card:hover {{
    background: var(--surface-hover);
    transform: translateY(-2px);
    border-color: rgba(255, 255, 255, 0.22);
  }}
  .channel-card.active {{
    border-color: var(--accent);
    background: rgba(10, 132, 255, 0.16);
  }}
  .channel-logo {{
    width: 44px;
    height: 44px;
    background: rgba(0, 0, 0, 0.5);
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    flex-shrink: 0;
    border: 1px solid rgba(255, 255, 255, 0.08);
  }}
  .channel-logo img {{ width: 100%; height: 100%; object-fit: contain; }}
  .channel-logo .placeholder {{ color: var(--dim); font-size: 15px; font-weight: 700; }}
  .channel-meta {{ flex: 1; min-width: 0; }}
  .channel-name {{
    font-size: 14px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }}
  .channel-sub {{
    font-size: 12px;
    color: var(--dim);
    margin-top: 3px;
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }}
  .tag-relay {{
    background: rgba(48, 209, 88, 0.2);
    color: #30d158;
    font-size: 10px;
    padding: 1px 5px;
    border-radius: 4px;
    font-weight: 600;
  }}
  .tag-drm {{
    background: rgba(191, 90, 242, 0.2);
    color: #bf5af2;
    font-size: 10px;
    padding: 1px 5px;
    border-radius: 4px;
    font-weight: 600;
  }}
  .tag-dash {{
    background: rgba(255, 159, 10, 0.2);
    color: #ff9f0a;
    font-size: 10px;
    padding: 1px 5px;
    border-radius: 4px;
    font-weight: 600;
  }}
  .tag-vod {{
    background: rgba(255, 45, 85, 0.2);
    color: #ff2d55;
    font-size: 10px;
    padding: 1px 5px;
    border-radius: 4px;
    font-weight: 600;
  }}
  .toast {{
    position: fixed;
    bottom: 24px;
    right: 24px;
    background: #1c1c1e;
    color: white;
    border: 1px solid var(--border);
    padding: 12px 20px;
    border-radius: 12px;
    font-size: 14px;
    box-shadow: 0 8px 24px rgba(0,0,0,0.5);
    opacity: 0;
    pointer-events: none;
    transition: opacity 0.25s;
    z-index: 999;
  }}
  .toast.show {{ opacity: 1; }}
</style>
</head>
<body>

<header>
  <div class="brand">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <rect width="20" height="15" x="2" y="7" rx="2"/>
      <polyline points="17 2 12 7 2 7"/>
    </svg>
    <div>
      <h1>Tahoe IPTV Server <span class="badge-live">ONLINE</span></h1>
      <div style="font-size: 13px; color: var(--dim); margin-top: 2px;">Streaming Relay, Injeksi Header & Pengelolaan DRM (ClearKey/Widevine)</div>
    </div>
  </div>
  <div class="stats">
    <div>Saluran: <strong>{total_channels}</strong></div>
    <div>Relay Header: <strong>{with_headers}</strong></div>
    <div>DRM Terdata: <strong>{drm_count}</strong></div>
    <div>Sumber: <strong>{len(self.sources)} file</strong></div>
  </div>
</header>

<div class="quick-links">
  <div class="link-group">
    <span style="font-size: 13px; color: var(--dim); margin-right: 6px;">URL Playlist:</span>
    <button class="btn primary" onclick="copyText('{base_url}/playlist.m3u', 'Link Playlist Master berhasil disalin!')">
      📋 Salin Playlist Master
    </button>
    <a href="{base_url}/playlist.m3u" class="btn" target="_blank">⬇️ Download .m3u</a>
    <button class="btn" onclick="copyText('{base_url}/epg.xml', 'Link EPG berhasil disalin!')">
      📅 Salin Link EPG
    </button>
  </div>
  <div class="link-group">
    <span style="font-size: 13px; color: var(--dim); margin-right: 4px;">Per File:</span>
"""
        for s in self.sources:
            html_content += f"""    <button class="btn" onclick="copyText('{base_url}/{s}', 'Link {s} berhasil disalin!')">📄 {s}</button>\n"""

        html_content += f"""  </div>
</div>

<div class="main-layout">
  <div class="explorer">
    <input type="text" id="searchInput" class="search-input" placeholder="Cari saluran (contoh: SCTV, RCTI, Trans TV, TVRI, HBO, beIN, Sports)..." oninput="filterChannels()">

    <div class="pills" id="groupPills">
      <button class="pill active" onclick="selectGroup(null, this)">Semua ({total_channels})</button>
"""
        for g in groups:
            count = sum(1 for c in self.channels if c.group == g)
            html_content += f"""      <button class="pill" onclick="selectGroup('{html.escape(g)}', this)">{html.escape(g)} ({count})</button>\n"""

        html_content += f"""    </div>

    <div class="channel-grid" id="channelGrid">
"""
        for c in self.channels:
            logo_html = f'<img src="{html.escape(c.logo)}" loading="lazy" onerror="this.style.display=\'none\'; this.nextElementSibling.style.display=\'block\';">' if c.logo else ''
            initial = (c.name[0] if c.name else '?').upper()
            placeholder_style = 'style="display:none;"' if c.logo else ''
            relay_badge = '<span class="tag-relay">Relay</span>' if c.headers else ''
            drm_badge = f'<span class="tag-drm">DRM:{c.drm_type}</span>' if c.drm_type else ('<span class="tag-drm">DRM</span>' if c.drm_key else '')
            dash_badge = '<span class="tag-dash">DASH</span>' if c.is_mpd else ''
            vod_badge = '<span class="tag-vod">VOD</span>' if c.is_vod else ''

            c_json = json.dumps(c.to_dict(base_url)).replace('"', '&quot;')

            html_content += f"""      <div class="channel-card" data-id="{c.cid}" data-name="{html.escape(c.name.lower())}" data-group="{html.escape(c.group)}" data-info="{c_json}" onclick="playChannel(this)">
        <div class="channel-logo">
          {logo_html}
          <div class="placeholder" {placeholder_style}>{initial}</div>
        </div>
        <div class="channel-meta">
          <div class="channel-name">{html.escape(c.name)}</div>
          <div class="channel-sub">
            <span>{html.escape(c.group)}</span>
            {relay_badge}
            {dash_badge}
            {drm_badge}
            {vod_badge}
          </div>
        </div>
      </div>\n"""

        html_content += """    </div>
  </div>

  <div class="player-card">
    <div class="player-box">
      <video id="videoPlayer" controls autoplay playsinline></video>
    </div>
    <div class="player-info">
      <h3 id="currentTitle">Pilih saluran untuk menonton</h3>
      <p id="currentMeta">Klik salah satu saluran di sebelah kiri.</p>
      <div id="drmInfoBox" style="display:none; margin-top:8px; font-size:12px; background:rgba(255,255,255,0.06); padding:8px 10px; border-radius:8px;"></div>
      <div style="margin-top: 14px; display: flex; gap: 8px;">
        <button id="copyStreamBtn" class="btn" style="display: none;" onclick="copyCurrentStream()">🔗 Salin Link Stream Ini</button>
      </div>
    </div>
  </div>
</div>

<div id="toast" class="toast">Disalin ke clipboard</div>

<script>
  let currentCh = null;
  let activeGroup = null;
  let shakaPlayer = null;
  const video = document.getElementById('videoPlayer');

  // Init Shaka Player
  document.addEventListener('DOMContentLoaded', async () => {
    shaka.polyfill.installAll();
    if (shaka.Player.isBrowserSupported()) {
      shakaPlayer = new shaka.Player(video);
      shakaPlayer.addEventListener('error', (e) => {
        console.error('Shaka error:', e.detail);
        document.getElementById('currentMeta').innerText = (currentCh?.group || '') + ' · Gagal memuat siaran (Error ' + e.detail.code + ')';
      });
    }

    const first = document.querySelector('.channel-card');
    if (first) { first.click(); }
  });

  function selectGroup(group, el) {
    activeGroup = group;
    document.querySelectorAll('.pill').forEach(p => p.classList.remove('active'));
    el.classList.add('active');
    filterChannels();
  }

  function filterChannels() {
    const q = document.getElementById('searchInput').value.toLowerCase().trim();
    const cards = document.querySelectorAll('.channel-card');
    cards.forEach(card => {
      const name = card.dataset.name;
      const grp = card.dataset.group;
      const matchesQ = !q || name.includes(q);
      const matchesG = !activeGroup || grp === activeGroup;
      card.style.display = (matchesQ && matchesG) ? 'flex' : 'none';
    });
  }

  async function playChannel(el) {
    const info = JSON.parse(el.dataset.info);
    currentCh = info;

    document.querySelectorAll('.channel-card').forEach(c => c.classList.toggle('active', c.dataset.id === info.id));
    document.getElementById('currentTitle').innerText = info.name;
    document.getElementById('currentMeta').innerText = info.group + ' · Memuat siaran...';
    document.getElementById('copyStreamBtn').style.display = 'inline-flex';

    const drmBox = document.getElementById('drmInfoBox');
    if (info.drm_type || info.drm_key) {
      drmBox.style.display = 'block';
      drmBox.innerHTML = `<strong>DRM Terdeteksi:</strong> ${info.drm_type || 'Custom'} ${info.drm_key ? '<br><code>' + info.drm_key + '</code>' : ''}`;
    } else {
      drmBox.style.display = 'none';
    }

    if (!shakaPlayer) return;

    // Reset player config
    shakaPlayer.resetConfiguration();

    // Set request headers if needed
    shakaPlayer.getNetworkingEngine().clearAllRequestFilters();
    if (info.has_headers) {
      shakaPlayer.getNetworkingEngine().registerRequestFilter((type, request) => {
        // Headers are already injected by Tahoe server proxy
      });
    }

    // Configure ClearKey DRM if present
    if (info.drm_type && (info.drm_type.includes('clearkey') || info.drm_type.includes('org.w3.clearkey')) && info.drm_key) {
      const cleanKey = info.drm_key.split('&')[0];
      const parts = cleanKey.split(':');
      if (parts.length === 2) {
        const keyId = parts[0].trim();
        const keyVal = parts[1].trim();
        shakaPlayer.configure({
          drm: {
            clearKeys: {
              [keyId]: keyVal
            }
          }
        });
      }
    } else if (info.drm_type && info.drm_type.includes('widevine') && info.drm_key) {
      shakaPlayer.configure({
        drm: {
          servers: {
            'com.widevine.alpha': info.drm_key
          }
        }
      });
    }

    try {
      await shakaPlayer.load(info.stream_url);
      video.play().catch(() => {});
      document.getElementById('currentMeta').innerText = info.group + ' · Sedang siaran langsung';
    } catch (e) {
      console.warn('Playback error:', e);
      document.getElementById('currentMeta').innerText = info.group + ' · Siaran offline atau butuh pemutar kompatibel (Kodi/TiviMate)';
    }
  }

  function copyCurrentStream() {
    if (!currentCh) return;
    copyText(currentCh.stream_url, 'Link stream saluran berhasil disalin!');
  }

  function copyText(text, msg) {
    navigator.clipboard.writeText(text).then(() => {
      const t = document.getElementById('toast');
      t.innerText = msg;
      t.classList.add('show');
      setTimeout(() => t.classList.remove('show'), 2500);
    });
  }
</script>
</body>
</html>
"""
        body = html_content.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def serve_playlist(self, query, source_filter=None):
        base_url = self.get_base_url()
        group_filter = query.get("group", [None])[0]
        raw_mode = query.get("raw", ["0"])[0] in ("1", "true")

        out = [f'#EXTM3U url-tvg="{base_url}/epg.xml"']

        for c in self.channels:
            if source_filter and c.source != source_filter:
                continue
            if group_filter and c.group.lower() != group_filter.lower():
                continue

            # Output DRM tags for Kodi / TiviMate / ExoPlayer
            if c.drm_type or c.drm_key or c.is_mpd:
                out.append("#KODIPROP:inputstreamaddon=inputstream.adaptive")
                if c.is_mpd:
                    out.append("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
                if c.drm_type:
                    out.append(f"#KODIPROP:inputstream.adaptive.license_type={c.drm_type}")
                if c.drm_key:
                    out.append(f"#KODIPROP:inputstream.adaptive.license_key={c.drm_key}")

            # Output EXTVLCOPT headers
            if c.headers:
                for hk, hv in c.headers.items():
                    hkl = hk.lower()
                    if hkl in ("referer", "referrer"):
                        out.append(f"#EXTVLCOPT:http-referrer={hv}")
                    elif hkl == "user-agent":
                        out.append(f"#EXTVLCOPT:http-user-agent={hv}")
                    elif hkl == "origin":
                        out.append(f"#EXTVLCOPT:http-origin={hv}")

            ext = ".mpd" if c.is_mpd else ".m3u8"
            stream_url = c.url if raw_mode else f"{base_url}/live/{c.cid}{ext}"
            logo_attr = f' tvg-logo="{c.logo}"' if c.logo else ""
            tvg_attr = f' tvg-id="{c.tvg_id}"' if c.tvg_id else ""
            grp_attr = f' group-title="{c.group}"' if c.group else ""

            out.append(f'#EXTINF:-1{tvg_attr}{logo_attr}{grp_attr},{c.name}')
            out.append(stream_url)

        body = ("\n".join(out) + "\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
        self.send_header("Content-Disposition", 'inline; filename="playlist.m3u"')
        self.send_header("Content-Length", str(len(body)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def serve_epg(self):
        try:
            req = urllib.request.Request(self.epg_url, headers={"User-Agent": "TahoeIPTV"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                body = resp.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/xml; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self.send_error(502, f"Failed to fetch upstream EPG: {e}")

    def fetch_upstream(self, url, headers=None, range_header=None):
        """Fetch URL following cross-protocol redirects and injecting headers."""
        u = url
        req_headers = {"User-Agent": "TahoeTV/2.8"}
        if headers:
            req_headers.update(headers)
        if range_header:
            req_headers["Range"] = range_header

        for _ in range(5):
            req = urllib.request.Request(u, headers=req_headers)
            try:
                resp = urllib.request.urlopen(req, timeout=12)
                return resp, resp.geturl()
            except urllib.error.HTTPError as e:
                if e.code in (301, 302, 303, 307, 308) and "Location" in e.headers:
                    u = urllib.parse.urljoin(u, e.headers["Location"])
                    continue
                raise
        raise RuntimeError("Too many redirects")

    def serve_live_mpd(self, cid):
        ch = self.by_id.get(cid)
        if not ch:
            self.send_error(404, "Channel Not Found")
            return
        try:
            resp, _ = self.fetch_upstream(ch.url, ch.headers)
            body = resp.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/dash+xml")
            self.send_header("Content-Length", str(len(body)))
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self.send_error(502, f"DASH proxy error: {e}")

    def serve_live_stream(self, cid, sub_url_b64=None):
        ch = self.by_id.get(cid)
        if not ch:
            self.send_error(404, "Channel Not Found")
            return

        target_url = ch.url
        if sub_url_b64:
            try:
                target_url = base64.urlsafe_b64decode(sub_url_b64.encode("ascii")).decode("utf-8")
            except Exception:
                pass

        try:
            resp, final_url = self.fetch_upstream(target_url, ch.headers)
            raw_body = resp.read()
            body_text = raw_body.decode("utf-8", errors="replace")

            base_url = self.get_base_url()
            rewritten_lines = []

            for line in body_text.splitlines():
                sline = line.strip()
                if not sline:
                    rewritten_lines.append(line)
                    continue
                if sline.startswith("#"):
                    if sline.startswith("#EXT-X-KEY"):
                        def rep_key(m):
                            key_uri = urllib.parse.urljoin(final_url, m.group(1))
                            enc = base64.urlsafe_b64encode(key_uri.encode("utf-8")).decode("ascii")
                            return f'URI="{base_url}/segment?cid={cid}&u={enc}"'
                        rewritten_lines.append(URI_ATTR.sub(rep_key, sline))
                    else:
                        rewritten_lines.append(line)
                else:
                    resolved = urllib.parse.urljoin(final_url, sline)
                    enc = base64.urlsafe_b64encode(resolved.encode("utf-8")).decode("ascii")
                    clean_path = resolved.split("?")[0].lower()
                    if clean_path.endswith(".m3u8"):
                        rewritten_lines.append(f"{base_url}/live/{cid}.m3u8?sub={enc}")
                    else:
                        rewritten_lines.append(f"{base_url}/segment?cid={cid}&u={enc}")

            out_bytes = "\n".join(rewritten_lines).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
            self.send_header("Content-Length", str(len(out_bytes)))
            self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(out_bytes)

        except Exception as e:
            self.send_error(502, f"Stream relay error: {e}")

    def serve_segment(self, encoded_url, cid):
        if not encoded_url:
            self.send_error(400, "Missing segment URL")
            return

        try:
            target_url = base64.urlsafe_b64decode(encoded_url.encode("ascii")).decode("utf-8")
        except Exception:
            self.send_error(400, "Invalid segment URL")
            return

        headers = {}
        if cid and cid in self.by_id:
            headers = self.by_id[cid].headers

        range_header = self.headers.get("Range")

        try:
            resp, _ = self.fetch_upstream(target_url, headers, range_header=range_header)
            code = resp.getcode() or 200

            self.send_response(code)
            content_type = resp.headers.get("Content-Type", "video/mp2t")
            self.send_header("Content-Type", content_type)
            if "Content-Length" in resp.headers:
                self.send_header("Content-Length", resp.headers["Content-Length"])
            if "Content-Range" in resp.headers:
                self.send_header("Content-Range", resp.headers["Content-Range"])
            if "Accept-Ranges" in resp.headers:
                self.send_header("Accept-Ranges", resp.headers["Accept-Ranges"])
            self.send_header("Cache-Control", "public, max-age=86400")
            self.send_cors_headers()
            self.end_headers()

            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

        except Exception:
            pass

    def serve_api_channels(self, query):
        base_url = self.get_base_url()
        group_filter = query.get("group", [None])[0]
        data = [
            c.to_dict(base_url) for c in self.channels
            if not group_filter or c.group.lower() == group_filter.lower()
        ]
        body = json.dumps({"count": len(data), "channels": data}, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def serve_api_status(self):
        base_url = self.get_base_url()
        uptime = int(time.time() - self.start_time)
        data = {
            "status": "online",
            "uptime_seconds": uptime,
            "port": self.server.server_address[1],
            "lan_ip": get_lan_ip(),
            "base_url": base_url,
            "master_playlist": f"{base_url}/playlist.m3u",
            "epg_url": f"{base_url}/epg.xml",
            "sources": self.sources,
            "total_channels": len(self.channels),
            "relay_channels": sum(1 for c in self.channels if c.headers),
            "drm_channels": sum(1 for c in self.channels if c.drm_type or c.drm_key)
        }
        body = json.dumps(data, indent=2).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        if "segment" not in args[0]:
            sys.stderr.write(f"[{time.strftime('%H:%M:%S')}] {args[0]} - {args[1]}\n")

def main():
    parser = argparse.ArgumentParser(description="Tahoe IPTV Server & Streaming Relay with DRM")
    parser.add_argument("playlists", nargs="*", default=["koutv.m3u", "anotheriptv.m3u"],
                        help="M3U files to load (default: koutv.m3u anotheriptv.m3u)")
    parser.add_argument("--port", type=int, default=8088, help="Server port (default: 8088)")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    args = parser.parse_args()

    all_channels = []
    existing_ids = set()
    loaded_sources = []

    for path in args.playlists:
        if os.path.exists(path):
            chans = parse_playlist(path, existing_ids)
            all_channels.extend(chans)
            loaded_sources.append(os.path.basename(path))
            print(f"Loaded {len(chans)} channels from {path}")
        else:
            print(f"Warning: playlist not found: {path}", file=sys.stderr)

    if not all_channels:
        print("Error: No channels loaded. Check playlist paths.", file=sys.stderr)
        sys.exit(1)

    IptvHandler.channels = all_channels
    IptvHandler.by_id = {c.cid: c for c in all_channels}
    IptvHandler.sources = loaded_sources

    server = ThreadedHTTPServer((args.host, args.port), IptvHandler)
    lan_ip = get_lan_ip()
    port = server.server_address[1]

    print("\n" + "=" * 60)
    print(f"  Tahoe IPTV Server running on http://{lan_ip}:{port}")
    print("=" * 60)
    print(f"  Web Dashboard & Player: http://{lan_ip}:{port}/")
    print(f"  Master Playlist (M3U):  http://{lan_ip}:{port}/playlist.m3u")
    print(f"  EPG Guide (XML):        http://{lan_ip}:{port}/epg.xml")
    print(f"  Total Channels:         {len(all_channels)}")
    print(f"  Header Relays:          {sum(1 for c in all_channels if c.headers)}")
    print(f"  DRM Channels (Kodi):    {sum(1 for c in all_channels if c.drm_type or c.drm_key)}")
    for s in loaded_sources:
        print(f"  Playlist per source:    http://{lan_ip}:{port}/{s}")
    print("=" * 60 + "\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping IPTV Server...")
        server.server_close()

if __name__ == "__main__":
    main()
