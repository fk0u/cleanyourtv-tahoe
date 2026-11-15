import http from "node:http";
import { createHash } from "node:crypto";
import { URL } from "node:url";
import {
  ackNotification,
  addAudit,
  createNotification,
  getConfig,
  getFleetOverview,
  listAudit,
  listDevices,
  listNotifications,
  notificationMatchesDevice,
  pendingNotificationsForDevice,
  setConfig,
  upsertDevice,
} from "./db.mjs";
import {
  extractBearerOrCookie,
  issueAdminSession,
  LoginLimiter,
  resetAdminPassword,
  verifyAdminToken,
  verifyPassword,
} from "./auth.mjs";

const ALLOWED_ROLES = new Set(["tv_home", "phone_remote", "desktop"]);
const ALLOWED_PLATFORMS = new Set([
  "android_tv",
  "android_phone",
  "macos_intel",
  "macos_arm64",
  "windows",
  "linux",
]);
const ALLOWED_KINDS = new Set(["info", "update", "warning", "urgent"]);
const GITHUB_REPO = "fk0u/cleanyourtv-tahoe";
const MAX_BODY_BYTES = 64 * 1024;
const DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/id.m3u";
const DHANY_PLAYLIST_URL = "https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv.m3u";

function sanitizeStr(v, max = 160) {
  return String(v ?? "").replace(/[\u0000-\u001F\u007F]/g, " ").trim().slice(0, max);
}

function safeActionUrl(v) {
  const s = String(v ?? "").trim();
  if (!s) return "";
  try {
    const u = new URL(s);
    return u.protocol === "https:" || u.protocol === "http:" ? u.toString().slice(0, 500) : "";
  } catch {
    return "";
  }
}

function parseChecksums(text) {
  const map = {};
  for (const raw of String(text || "").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const m = line.match(/^([a-fA-F0-9]{64})\s+\*?(.+)$/);
    if (m) map[m[2].trim()] = m[1].toLowerCase();
  }
  return map;
}

function parseM3uSummary(text, sourceName, maxItems = 250) {
  const out = [];
  let pendingName = "";
  let pendingGroup = "Lainnya";
  let pendingLogo = "";
  let pendingDrm = false;
  for (const raw of String(text || "").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith("#EXTINF:")) {
      pendingName = sanitizeStr(line.substring(line.lastIndexOf(",") + 1), 80);
      const gm = line.match(/group-title="([^"]*)"/i);
      pendingGroup = gm ? sanitizeStr(gm[1], 48) || "Umum" : "Umum";
      const lm = line.match(/tvg-logo="([^"]*)"/i);
      pendingLogo = lm ? safeActionUrl(lm[1]) : "";
      pendingDrm = false;
    } else if (line.startsWith("#KODIPROP:") && /license_type|license_key/i.test(line)) {
      pendingDrm = true;
    } else if (line.startsWith("#EXTGRP:")) {
      pendingGroup = sanitizeStr(line.slice(8), 48) || pendingGroup;
    } else if (!line.startsWith("#")) {
      const url = safeActionUrl(line);
      if (url && pendingName) {
        out.push({
          name: pendingName,
          group: pendingGroup,
          logo: pendingLogo,
          format: /\.mpd(\?|$)/i.test(url) ? "DASH" : "HLS",
          drm: pendingDrm,
          source: sourceName,
        });
        if (out.length >= maxItems) break;
      }
      pendingName = "";
      pendingGroup = "Umum";
      pendingLogo = "";
      pendingDrm = false;
    }
  }
  return out;
}

async function defaultFetchRelease() {
  const relRes = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`, {
    headers: { Accept: "application/vnd.github+json", "User-Agent": "TahoeTV-ControlPlane" },
    signal: AbortSignal.timeout(8000),
  });
  if (!relRes.ok) throw new Error(`GitHub HTTP ${relRes.status}`);
  const rel = await relRes.json();
  const rawAssets = Array.isArray(rel.assets) ? rel.assets : [];
  let checksums = {};
  const sumsAsset = rawAssets.find((a) => a.name === "SHA256SUMS");
  if (sumsAsset?.browser_download_url) {
    try {
      const sumRes = await fetch(sumsAsset.browser_download_url, {
        headers: { "User-Agent": "TahoeTV-ControlPlane" },
        signal: AbortSignal.timeout(8000),
      });
      if (sumRes.ok) checksums = parseChecksums(await sumRes.text());
    } catch {
      /* optional checksum fetch */
    }
  }
  const assets = rawAssets.map((a) => ({
    name: a.name,
    url: a.browser_download_url,
    size: Number(a.size || 0),
    sha256: checksums[a.name] || null,
  }));
  return {
    version: String(rel.tag_name || "").replace(/^v/, ""),
    tag_name: rel.tag_name || "",
    notes: String(rel.body || ""),
    page: String(rel.html_url || `https://github.com/${GITHUB_REPO}/releases`),
    date: String(rel.published_at || "").slice(0, 10),
    assets,
  };
}

async function defaultFetchChannelPreview() {
  const results = [];
  for (const [label, u] of [
    ["iptv-org Indonesia", DEFAULT_PLAYLIST_URL],
    ["dhanytv", DHANY_PLAYLIST_URL],
  ]) {
    try {
      const r = await fetch(u, {
        headers: { "User-Agent": "TahoeTV-Directory" },
        signal: AbortSignal.timeout(10_000),
      });
      if (r.ok) {
        const text = await r.text();
        results.push(...parseM3uSummary(text, label, 200));
      }
    } catch {
      /* tolerate partial upstream unavailability */
    }
  }
  const seen = new Set();
  const deduped = [];
  for (const item of results) {
    const k = item.name.toLowerCase();
    if (seen.has(k)) continue;
    seen.add(k);
    deduped.push(item);
  }
  return deduped;
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on("data", (c) => {
      total += c.length;
      if (total > MAX_BODY_BYTES) {
        reject(Object.assign(new Error("Payload too large"), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8").trim();
      if (!raw) return resolve({});
      try {
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
          reject(Object.assign(new Error("Expected JSON object"), { statusCode: 400 }));
          return;
        }
        resolve(parsed);
      } catch {
        reject(Object.assign(new Error("Invalid JSON"), { statusCode: 400 }));
      }
    });
    req.on("error", reject);
  });
}

function clientIp(req) {
  const fwd = String(req.headers["x-forwarded-for"] || "").split(",")[0].trim();
  return fwd || req.socket?.remoteAddress || "unknown";
}

function sendJson(res, status, data, extraHeaders = {}) {
  const body = Buffer.from(JSON.stringify(data));
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    ...extraHeaders,
  });
  res.end(body);
}

/**
 * Creates the Tahoe Control Plane HTTP + SSE server.
 */
export function createTahoeServer({
  db,
  sessionSecret,
  fetchRelease = defaultFetchRelease,
  fetchChannelPreview = defaultFetchChannelPreview,
  releaseTtlMs = 5 * 60_000,
  channelsTtlMs = 15 * 60_000,
  secureCookies = true,
}) {
  const limiter = new LoginLimiter();
  const sseClients = new Set();
  let releaseCache = { at: 0, value: null };
  let channelsCache = { at: 0, value: null };

  function corsHeaders(req) {
    const origin = req.headers.origin;
    if (!origin) return {};
    if (
      origin === "https://tahoe.kousozo.tech" ||
      origin.startsWith("http://localhost:") ||
      origin.startsWith("http://127.0.0.1:")
    ) {
      return {
        "Access-Control-Allow-Origin": origin,
        "Access-Control-Allow-Credentials": "true",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
        "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
        Vary: "Origin",
      };
    }
    return { Vary: "Origin" };
  }

  async function getCachedRelease() {
    const now = Date.now();
    if (releaseCache.value && now - releaseCache.at < releaseTtlMs) {
      return releaseCache.value;
    }
    try {
      const fresh = await fetchRelease();
      releaseCache = { at: now, value: fresh };
      return fresh;
    } catch {
      return releaseCache.value || null;
    }
  }

  async function getCachedChannels() {
    const now = Date.now();
    if (channelsCache.value && now - channelsCache.at < channelsTtlMs) {
      return channelsCache.value;
    }
    try {
      const fresh = await fetchChannelPreview();
      channelsCache = { at: now, value: fresh };
      return fresh;
    } catch {
      return channelsCache.value || [];
    }
  }

  function broadcastSseEvent(eventName, payload, matcher = null) {
    const serialized = `event: ${eventName}\ndata: ${JSON.stringify(payload)}\n\n`;
    for (const client of sseClients) {
      if (matcher && !matcher(client.device)) continue;
      try {
        if (payload?.id) client.res.write(`id: ${payload.id}\n${serialized}`);
        else client.res.write(serialized);
      } catch {
        sseClients.delete(client);
      }
    }
  }

  function requireAdmin(req, res, cors) {
    const token = extractBearerOrCookie(req);
    const admin = verifyAdminToken(token, sessionSecret, db);
    if (!admin) {
      sendJson(res, 401, { error: "Admin authentication required" }, cors);
      return null;
    }
    return admin;
  }

  const keepalive = setInterval(() => {
    for (const c of sseClients) {
      try {
        c.res.write(": ping\n\n");
      } catch {
        sseClients.delete(c);
      }
    }
  }, 25_000);
  keepalive.unref();

  const server = http.createServer(async (req, res) => {
    const cors = corsHeaders(req);
    if (req.method === "OPTIONS") {
      res.writeHead(204, cors);
      res.end();
      return;
    }

    const u = new URL(req.url || "/", "http://localhost");
    const path = u.pathname;
    const method = req.method || "GET";

    try {
      // ------------------------------------------------------------- Health & Public
      if (method === "GET" && path === "/api/health") {
        return sendJson(res, 200, { ok: true, time: Date.now(), online_sse: sseClients.size }, cors);
      }

      if (method === "GET" && path === "/api/releases/latest") {
        const rel = await getCachedRelease();
        const cfg = getConfig(db);
        if (!rel) return sendJson(res, 503, { error: "Release metadata unavailable", update_policy: cfg.update_policy }, cors);
        return sendJson(res, 200, { ...rel, update_policy: cfg.update_policy }, cors);
      }

      if (method === "GET" && path === "/api/public/stats") {
        const overview = getFleetOverview(db, sseClients.size);
        const cfg = getConfig(db);
        const rel = await getCachedRelease();
        return sendJson(
          res,
          200,
          {
            total_installed: overview.total_installed,
            active_24h: overview.active_24h,
            active_7d: overview.active_7d,
            online_now: overview.online_sse,
            platforms: overview.by_platform,
            roles: overview.by_role,
            latest_release: rel
              ? {
                  version: rel.version,
                  date: rel.date,
                  notes: rel.notes,
                  page: rel.page,
                  assets: rel.assets,
                }
              : null,
            update_policy: cfg.update_policy,
            announcement: cfg.channel_overrides.announcement || "",
            featured_notice: cfg.channel_overrides.featured_notice || "",
          },
          cors
        );
      }

      if (method === "GET" && path === "/api/public/channels/preview") {
        const channels = await getCachedChannels();
        const cfg = getConfig(db);
        const q = sanitizeStr(u.searchParams.get("q") || "", 60).toLowerCase();
        const group = sanitizeStr(u.searchParams.get("group") || "", 48);
        const filtered = channels.filter((c) => {
          if (q && !c.name.toLowerCase().includes(q) && !c.group.toLowerCase().includes(q)) return false;
          if (group && c.group !== group) return false;
          return true;
        });
        return sendJson(
          res,
          200,
          {
            total: channels.length,
            filtered: filtered.length,
            groups: [...new Set(channels.map((c) => c.group))].sort(),
            items: filtered.slice(0, 120),
            featured_notice: cfg.channel_overrides.featured_notice || "",
          },
          cors
        );
      }

      // ------------------------------------------------------------- Device Heartbeat & Push
      if (method === "POST" && path === "/api/devices/heartbeat") {
        const body = await readJsonBody(req);
        const deviceId = sanitizeStr(body.device_id, 80).replace(/[^a-zA-Z0-9._:-]/g, "");
        if (deviceId.length < 8) {
          return sendJson(res, 400, { error: "Valid device_id required" }, cors);
        }
        const role = ALLOWED_ROLES.has(body.app_role) ? body.app_role : "desktop";
        const platform = ALLOWED_PLATFORMS.has(body.platform) ? body.platform : "linux";
        const ip = clientIp(req);
        const ipHash = createHash("sha256").update(ip).digest("hex").slice(0, 16);

        const dev = upsertDevice(
          db,
          {
            device_id: deviceId,
            app_role: role,
            platform,
            os_version: sanitizeStr(body.os_version, 64),
            device_model: sanitizeStr(body.device_model, 80),
            app_version: sanitizeStr(body.app_version, 32),
            version_code: Math.max(0, Math.min(999_999, Number(body.version_code) || 0)),
            arch: sanitizeStr(body.arch, 24),
            country: sanitizeStr(body.country, 8),
            lang: sanitizeStr(body.lang, 12),
          },
          ipHash
        );

        const sinceId = Number(body.last_notification_id || 0);
        const notifications = pendingNotificationsForDevice(db, dev, sinceId);
        const cfg = getConfig(db);
        const rel = await getCachedRelease();

        return sendJson(
          res,
          200,
          {
            ok: true,
            server_time: Date.now(),
            update_policy: cfg.update_policy,
            channel_overrides: cfg.channel_overrides,
            latest_release: rel
              ? {
                  version: rel.version,
                  date: rel.date,
                  notes: rel.notes,
                  page: rel.page,
                  assets: rel.assets,
                }
              : null,
            notifications,
          },
          cors
        );
      }

      if (method === "GET" && path === "/api/push/pending") {
        const deviceId = sanitizeStr(u.searchParams.get("device_id"), 80);
        if (deviceId.length < 8) return sendJson(res, 400, { error: "device_id required" }, cors);
        const existing = db.prepare("SELECT * FROM devices WHERE device_id = ?").get(deviceId);
        const dev = existing || {
          device_id: deviceId,
          platform: sanitizeStr(u.searchParams.get("platform"), 32),
          app_role: sanitizeStr(u.searchParams.get("app_role"), 32),
          version_code: Number(u.searchParams.get("version_code") || 0),
          country: sanitizeStr(u.searchParams.get("country"), 8),
        };
        const since = Number(u.searchParams.get("since") || 0);
        const notifications = pendingNotificationsForDevice(db, dev, since);
        const cfg = getConfig(db);
        return sendJson(
          res,
          200,
          { ok: true, notifications, update_policy: cfg.update_policy, channel_overrides: cfg.channel_overrides },
          cors
        );
      }

      if (method === "POST" && path === "/api/push/ack") {
        const body = await readJsonBody(req);
        const deviceId = sanitizeStr(body.device_id, 80);
        const notifId = Number(body.notification_id || 0);
        const status = sanitizeStr(body.status || "delivered", 16);
        if (!deviceId || notifId <= 0) return sendJson(res, 400, { error: "device_id and notification_id required" }, cors);
        const ok = ackNotification(db, notifId, deviceId, status);
        return sendJson(res, 200, { ok }, cors);
      }

      if (method === "GET" && path === "/api/push/stream") {
        const deviceId = sanitizeStr(u.searchParams.get("device_id"), 80);
        if (deviceId.length < 8) return sendJson(res, 400, { error: "device_id required" }, cors);
        const stored = db.prepare("SELECT * FROM devices WHERE device_id = ?").get(deviceId);
        const dev = stored || {
          device_id: deviceId,
          platform: sanitizeStr(u.searchParams.get("platform"), 32),
          app_role: sanitizeStr(u.searchParams.get("app_role"), 32),
          version_code: Number(u.searchParams.get("version_code") || 0),
          country: sanitizeStr(u.searchParams.get("country"), 8),
        };
        const lastEventId = Number(req.headers["last-event-id"] || u.searchParams.get("since") || 0);

        res.writeHead(200, {
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache, no-transform",
          Connection: "keep-alive",
          "X-Accel-Buffering": "no",
          ...cors,
        });

        const client = { res, device: dev };
        sseClients.add(client);
        res.write(`event: hello\ndata: ${JSON.stringify({ ok: true, server_time: Date.now() })}\n\n`);

        const catchup = pendingNotificationsForDevice(db, dev, lastEventId);
        for (const n of catchup) {
          res.write(`id: ${n.id}\nevent: push\ndata: ${JSON.stringify(n)}\n\n`);
        }

        req.on("close", () => {
          sseClients.delete(client);
        });
        return;
      }

      // ------------------------------------------------------------- Admin Authentication
      if (method === "POST" && path === "/api/admin/login") {
        const ip = clientIp(req);
        const lockSec = limiter.check(ip);
        if (lockSec > 0) {
          return sendJson(res, 429, { error: `Too many failed attempts. Retry in ${lockSec}s` }, cors);
        }
        const body = await readJsonBody(req);
        const username = sanitizeStr(body.username, 64);
        const password = String(body.password || "");
        const row = db.prepare("SELECT * FROM admin_users WHERE username = ?").get(username);
        if (!row || !verifyPassword(password, row.salt, row.password_hash)) {
          const newLock = limiter.fail(ip);
          addAudit(db, username || "unknown", "login_failed", `ip=${ip}`);
          return sendJson(
            res,
            newLock > 0 ? 429 : 401,
            { error: newLock > 0 ? `Locked for ${newLock}s` : "Invalid credentials" },
            cors
          );
        }
        limiter.reset(ip);
        const sess = issueAdminSession(db, row.username, sessionSecret);
        addAudit(db, row.username, "login_success", `ip=${ip}`);
        const cookieFlags = [
          `tahoe_admin=${encodeURIComponent(sess.token)}`,
          "Path=/",
          "HttpOnly",
          "SameSite=Strict",
          `Max-Age=${7 * 24 * 3600}`,
        ];
        if (secureCookies) cookieFlags.push("Secure");
        return sendJson(res, 200, sess, { ...cors, "Set-Cookie": cookieFlags.join("; ") });
      }

      if (method === "POST" && path === "/api/admin/logout") {
        const cookieFlags = ["tahoe_admin=", "Path=/", "HttpOnly", "SameSite=Strict", "Max-Age=0"];
        if (secureCookies) cookieFlags.push("Secure");
        return sendJson(res, 200, { ok: true }, { ...cors, "Set-Cookie": cookieFlags.join("; ") });
      }

      if (method === "GET" && path === "/api/admin/me") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        return sendJson(res, 200, { ok: true, username: admin.username, expires_at: admin.exp }, cors);
      }

      if (method === "POST" && path === "/api/admin/password") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        const body = await readJsonBody(req);
        const cur = String(body.current_password || "");
        const next = String(body.new_password || "");
        if (next.length < 12) return sendJson(res, 400, { error: "New password must be at least 12 characters" }, cors);
        const row = db.prepare("SELECT * FROM admin_users WHERE username = ?").get(admin.username);
        if (!row || !verifyPassword(cur, row.salt, row.password_hash)) {
          return sendJson(res, 401, { error: "Current password incorrect" }, cors);
        }
        resetAdminPassword(db, admin.username, next);
        const sess = issueAdminSession(db, admin.username, sessionSecret);
        addAudit(db, admin.username, "password_changed", "Rotated admin credentials");
        return sendJson(res, 200, { ok: true, ...sess }, cors);
      }

      // ------------------------------------------------------------- Admin Fleet, Push & Config
      if (method === "GET" && path === "/api/admin/overview") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        const overview = getFleetOverview(db, sseClients.size);
        const cfg = getConfig(db);
        const rel = await getCachedRelease();
        const notifs = listNotifications(db, 10);
        return sendJson(
          res,
          200,
          {
            overview,
            config: cfg,
            latest_release: rel,
            recent_notifications: notifs,
            online_devices: [...sseClients].map((c) => ({
              device_id: c.device.device_id,
              platform: c.device.platform,
              app_role: c.device.app_role,
              country: c.device.country || "",
            })),
          },
          cors
        );
      }

      if (method === "GET" && path === "/api/admin/devices") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        const result = listDevices(db, {
          platform: sanitizeStr(u.searchParams.get("platform"), 32),
          role: sanitizeStr(u.searchParams.get("role"), 32),
          search: sanitizeStr(u.searchParams.get("q"), 80),
          limit: Number(u.searchParams.get("limit") || 100),
          offset: Number(u.searchParams.get("offset") || 0),
        });
        return sendJson(res, 200, result, cors);
      }

      if (method === "DELETE" && path.startsWith("/api/admin/devices/")) {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        const targetId = decodeURIComponent(path.slice("/api/admin/devices/".length));
        const info = db.prepare("DELETE FROM devices WHERE device_id = ?").run(targetId);
        addAudit(db, admin.username, "device_deleted", `device_id=${targetId}`);
        return sendJson(res, 200, { ok: true, deleted: Number(info.changes || 0) }, cors);
      }

      if (method === "GET" && path === "/api/admin/notifications") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        return sendJson(res, 200, { notifications: listNotifications(db, 50) }, cors);
      }

      if (method === "POST" && path === "/api/admin/notifications") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        const body = await readJsonBody(req);
        const title = sanitizeStr(body.title, 120);
        const msg = sanitizeStr(body.body, 800);
        if (!title || !msg) return sendJson(res, 400, { error: "title and body required" }, cors);
        const kind = ALLOWED_KINDS.has(body.kind) ? body.kind : "info";
        const targetPlatform = body.target_platform && ALLOWED_PLATFORMS.has(body.target_platform) ? body.target_platform : null;
        const targetRole = body.target_role && ALLOWED_ROLES.has(body.target_role) ? body.target_role : null;
        const targetCountry = body.target_country ? sanitizeStr(body.target_country, 8).toUpperCase() : null;
        const maxVc = Number(body.target_max_version_code || 0) || null;
        const expiresHours = Number(body.expires_hours || 0);
        const expiresAt = expiresHours > 0 ? Date.now() + expiresHours * 3600_000 : null;

        const created = createNotification(
          db,
          {
            title,
            body: msg,
            kind,
            target_platform: targetPlatform,
            target_role: targetRole,
            target_max_version_code: maxVc,
            target_country: targetCountry,
            action_url: safeActionUrl(body.action_url),
            action_label: sanitizeStr(body.action_label, 48),
            force_modal: Boolean(body.force_modal),
            expires_at: expiresAt,
          },
          admin.username
        );

        let pushedLive = 0;
        broadcastSseEvent("push", created, (dev) => {
          const ok = notificationMatchesDevice(created, dev);
          if (ok) pushedLive++;
          return ok;
        });

        addAudit(
          db,
          admin.username,
          "notification_sent",
          `id=${created.id} kind=${kind} live_sse=${pushedLive} title=${title}`
        );
        return sendJson(res, 201, { ok: true, notification: created, live_sse_delivered: pushedLive }, cors);
      }

      if (method === "DELETE" && path.startsWith("/api/admin/notifications/")) {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        const id = Number(path.slice("/api/admin/notifications/".length));
        const info = db.prepare("DELETE FROM notifications WHERE id = ?").run(id);
        addAudit(db, admin.username, "notification_deleted", `id=${id}`);
        return sendJson(res, 200, { ok: true, deleted: Number(info.changes || 0) }, cors);
      }

      if (method === "GET" && path === "/api/admin/config") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        return sendJson(res, 200, getConfig(db), cors);
      }

      if (method === "PUT" && path === "/api/admin/config") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        const body = await readJsonBody(req);
        if (body.update_policy && typeof body.update_policy === "object") {
          setConfig(db, "update_policy", {
            force_modal: Boolean(body.update_policy.force_modal),
            min_version_code: Math.max(0, Number(body.update_policy.min_version_code || 0)),
            min_version: sanitizeStr(body.update_policy.min_version, 24),
            recommended_version: sanitizeStr(body.update_policy.recommended_version, 24),
            modal_title: sanitizeStr(body.update_policy.modal_title, 120),
            modal_message: sanitizeStr(body.update_policy.modal_message, 600),
          });
        }
        if (body.channel_overrides && typeof body.channel_overrides === "object") {
          const blockedUrls = Array.isArray(body.channel_overrides.blocked_urls)
            ? body.channel_overrides.blocked_urls.map((x) => safeActionUrl(x)).filter(Boolean).slice(0, 100)
            : [];
          const blockedHosts = Array.isArray(body.channel_overrides.blocked_hosts)
            ? body.channel_overrides.blocked_hosts.map((x) => sanitizeStr(x, 120).toLowerCase()).filter(Boolean).slice(0, 100)
            : [];
          setConfig(db, "channel_overrides", {
            blocked_urls: blockedUrls,
            blocked_hosts: blockedHosts,
            announcement: sanitizeStr(body.channel_overrides.announcement, 300),
            featured_notice: sanitizeStr(body.channel_overrides.featured_notice, 300),
          });
        }
        const cfg = getConfig(db);
        broadcastSseEvent("config", cfg);
        addAudit(db, admin.username, "config_updated", JSON.stringify(cfg).slice(0, 300));
        return sendJson(res, 200, { ok: true, ...cfg }, cors);
      }

      if (method === "GET" && path === "/api/admin/audit") {
        const admin = requireAdmin(req, res, cors);
        if (!admin) return;
        return sendJson(res, 200, { logs: listAudit(db, 100) }, cors);
      }

      return sendJson(res, 404, { error: "Not found" }, cors);
    } catch (err) {
      const code = Number(err?.statusCode || 500);
      return sendJson(res, code, { error: err?.message || "Internal error" }, cors);
    }
  });

  server.closeAll = () => {
    clearInterval(keepalive);
    for (const c of sseClients) {
      try {
        c.res.end();
      } catch {
        /* ignore */
      }
    }
    sseClients.clear();
  };

  return server;
}
