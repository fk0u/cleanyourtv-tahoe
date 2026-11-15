import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";

const DEFAULT_UPDATE_POLICY = {
  force_modal: false,
  min_version_code: 0,
  min_version: "",
  recommended_version: "",
  modal_title: "",
  modal_message: "",
};

const DEFAULT_CHANNEL_OVERRIDES = {
  blocked_urls: [],
  blocked_hosts: [],
  announcement: "",
  featured_notice: "",
};

/**
 * Embedded SQLite data store for Tahoe TV devices, notifications, configs, and admin users.
 */
export function openDb(dbPath) {
  if (dbPath !== ":memory:") {
    mkdirSync(dirname(dbPath), { recursive: true });
  }
  const db = new DatabaseSync(dbPath);
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA busy_timeout = 5000;
    PRAGMA foreign_keys = ON;

    CREATE TABLE IF NOT EXISTS devices (
      device_id TEXT PRIMARY KEY,
      app_role TEXT NOT NULL,
      platform TEXT NOT NULL,
      os_version TEXT NOT NULL DEFAULT '',
      device_model TEXT NOT NULL DEFAULT '',
      app_version TEXT NOT NULL DEFAULT '',
      version_code INTEGER NOT NULL DEFAULT 0,
      arch TEXT NOT NULL DEFAULT '',
      country TEXT NOT NULL DEFAULT '',
      lang TEXT NOT NULL DEFAULT '',
      first_seen_at INTEGER NOT NULL,
      last_seen_at INTEGER NOT NULL,
      heartbeats INTEGER NOT NULL DEFAULT 1,
      last_ip_hash TEXT NOT NULL DEFAULT ''
    );
    CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen_at DESC);
    CREATE INDEX IF NOT EXISTS idx_devices_platform ON devices(platform);
    CREATE INDEX IF NOT EXISTS idx_devices_role ON devices(app_role);

    CREATE TABLE IF NOT EXISTS notifications (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      title TEXT NOT NULL,
      body TEXT NOT NULL,
      kind TEXT NOT NULL DEFAULT 'info',
      target_platform TEXT,
      target_role TEXT,
      target_max_version_code INTEGER,
      target_country TEXT,
      action_url TEXT NOT NULL DEFAULT '',
      action_label TEXT NOT NULL DEFAULT '',
      force_modal INTEGER NOT NULL DEFAULT 0,
      created_by TEXT NOT NULL DEFAULT 'admin',
      created_at INTEGER NOT NULL,
      expires_at INTEGER
    );
    CREATE INDEX IF NOT EXISTS idx_notif_created ON notifications(created_at DESC);

    CREATE TABLE IF NOT EXISTS notification_deliveries (
      notification_id INTEGER NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
      device_id TEXT NOT NULL,
      status TEXT NOT NULL,
      updated_at INTEGER NOT NULL,
      PRIMARY KEY (notification_id, device_id)
    );

    CREATE TABLE IF NOT EXISTS admin_config (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL,
      updated_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS admin_users (
      username TEXT PRIMARY KEY,
      salt TEXT NOT NULL,
      password_hash TEXT NOT NULL,
      token_version INTEGER NOT NULL DEFAULT 1,
      created_at INTEGER NOT NULL,
      last_login_at INTEGER
    );

    CREATE TABLE IF NOT EXISTS audit_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      actor TEXT NOT NULL,
      action TEXT NOT NULL,
      details TEXT NOT NULL DEFAULT '',
      created_at INTEGER NOT NULL
    );
  `);

  const now = Date.now();
  const seedCfg = db.prepare(
    "INSERT OR IGNORE INTO admin_config (key, value, updated_at) VALUES (?, ?, ?)"
  );
  seedCfg.run("update_policy", JSON.stringify(DEFAULT_UPDATE_POLICY), now);
  seedCfg.run("channel_overrides", JSON.stringify(DEFAULT_CHANNEL_OVERRIDES), now);

  return db;
}

export function getConfig(db) {
  const rows = db.prepare("SELECT key, value FROM admin_config").all();
  const map = {};
  for (const r of rows) {
    try {
      map[r.key] = JSON.parse(r.value);
    } catch {
      map[r.key] = null;
    }
  }
  return {
    update_policy: { ...DEFAULT_UPDATE_POLICY, ...(map.update_policy || {}) },
    channel_overrides: { ...DEFAULT_CHANNEL_OVERRIDES, ...(map.channel_overrides || {}) },
  };
}

export function setConfig(db, key, val) {
  const merged =
    key === "update_policy"
      ? { ...DEFAULT_UPDATE_POLICY, ...val }
      : { ...DEFAULT_CHANNEL_OVERRIDES, ...val };
  db.prepare(
    `INSERT INTO admin_config (key, value, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
  ).run(key, JSON.stringify(merged), Date.now());
  return merged;
}

export function upsertDevice(db, d, ipHash = "") {
  const now = Date.now();
  db.prepare(
    `INSERT INTO devices (
      device_id, app_role, platform, os_version, device_model, app_version,
      version_code, arch, country, lang, first_seen_at, last_seen_at, heartbeats, last_ip_hash
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
    ON CONFLICT(device_id) DO UPDATE SET
      app_role = excluded.app_role,
      platform = excluded.platform,
      os_version = excluded.os_version,
      device_model = excluded.device_model,
      app_version = excluded.app_version,
      version_code = excluded.version_code,
      arch = excluded.arch,
      country = excluded.country,
      lang = excluded.lang,
      last_seen_at = excluded.last_seen_at,
      heartbeats = devices.heartbeats + 1,
      last_ip_hash = excluded.last_ip_hash`
  ).run(
    d.device_id,
    d.app_role,
    d.platform,
    d.os_version || "",
    d.device_model || "",
    d.app_version || "",
    Number(d.version_code) || 0,
    d.arch || "",
    (d.country || "").toUpperCase(),
    (d.lang || "").toLowerCase(),
    now,
    now,
    ipHash
  );
  return db.prepare("SELECT * FROM devices WHERE device_id = ?").get(d.device_id);
}

export function getFleetOverview(db, onlineSseCount = 0) {
  const now = Date.now();
  const h1 = now - 3600_000;
  const h24 = now - 86_400_000;
  const d7 = now - 7 * 86_400_000;
  const d30 = now - 30 * 86_400_000;

  const totals = db
    .prepare(
      `SELECT
        COUNT(*) AS total_installed,
        SUM(CASE WHEN last_seen_at >= ? THEN 1 ELSE 0 END) AS active_1h,
        SUM(CASE WHEN last_seen_at >= ? THEN 1 ELSE 0 END) AS active_24h,
        SUM(CASE WHEN last_seen_at >= ? THEN 1 ELSE 0 END) AS active_7d,
        SUM(CASE WHEN last_seen_at >= ? THEN 1 ELSE 0 END) AS active_30d
      FROM devices`
    )
    .get(h1, h24, d7, d30);

  const byPlatform = db
    .prepare(
      `SELECT platform, COUNT(*) AS count,
        SUM(CASE WHEN last_seen_at >= ? THEN 1 ELSE 0 END) AS active_24h
       FROM devices GROUP BY platform ORDER BY count DESC`
    )
    .all(h24);

  const byRole = db
    .prepare(
      `SELECT app_role, COUNT(*) AS count,
        SUM(CASE WHEN last_seen_at >= ? THEN 1 ELSE 0 END) AS active_24h
       FROM devices GROUP BY app_role ORDER BY count DESC`
    )
    .all(h24);

  const byVersion = db
    .prepare(
      `SELECT app_version, version_code, COUNT(*) AS count
       FROM devices GROUP BY app_version, version_code ORDER BY version_code DESC, count DESC LIMIT 15`
    )
    .all();

  const byCountry = db
    .prepare(
      `SELECT COALESCE(NULLIF(country, ''), 'UNKNOWN') AS country, COUNT(*) AS count
       FROM devices GROUP BY COALESCE(NULLIF(country, ''), 'UNKNOWN') ORDER BY count DESC LIMIT 15`
    )
    .all();

  const topModels = db
    .prepare(
      `SELECT COALESCE(NULLIF(device_model, ''), platform) AS model, platform, COUNT(*) AS count
       FROM devices GROUP BY COALESCE(NULLIF(device_model, ''), platform), platform ORDER BY count DESC LIMIT 10`
    )
    .all();

  return {
    total_installed: Number(totals?.total_installed || 0),
    active_1h: Number(totals?.active_1h || 0),
    active_24h: Number(totals?.active_24h || 0),
    active_7d: Number(totals?.active_7d || 0),
    active_30d: Number(totals?.active_30d || 0),
    online_sse: onlineSseCount,
    by_platform: byPlatform,
    by_role: byRole,
    by_version: byVersion,
    by_country: byCountry,
    top_models: topModels,
  };
}

export function listDevices(db, { platform = "", role = "", search = "", limit = 100, offset = 0 } = {}) {
  const where = [];
  const args = [];
  if (platform) {
    where.push("platform = ?");
    args.push(platform);
  }
  if (role) {
    where.push("app_role = ?");
    args.push(role);
  }
  if (search) {
    where.push("(device_id LIKE ? OR device_model LIKE ? OR app_version LIKE ? OR country LIKE ?)");
    const q = `%${search}%`;
    args.push(q, q, q, q);
  }
  const sqlWhere = where.length ? `WHERE ${where.join(" AND ")}` : "";
  const totalRow = db.prepare(`SELECT COUNT(*) AS c FROM devices ${sqlWhere}`).get(...args);
  const rows = db
    .prepare(`SELECT * FROM devices ${sqlWhere} ORDER BY last_seen_at DESC LIMIT ? OFFSET ?`)
    .all(...args, Math.min(250, Math.max(1, Number(limit) || 100)), Math.max(0, Number(offset) || 0));
  return { total: Number(totalRow?.c || 0), devices: rows };
}

export function notificationMatchesDevice(notif, dev) {
  const now = Date.now();
  if (notif.expires_at && notif.expires_at < now) return false;
  if (notif.target_platform && notif.target_platform !== dev.platform) return false;
  if (notif.target_role && notif.target_role !== dev.app_role) return false;
  if (
    notif.target_max_version_code != null &&
    Number(notif.target_max_version_code) > 0 &&
    Number(dev.version_code || 0) > Number(notif.target_max_version_code)
  ) {
    return false;
  }
  if (
    notif.target_country &&
    dev.country &&
    notif.target_country.toUpperCase() !== dev.country.toUpperCase()
  ) {
    return false;
  }
  return true;
}

export function createNotification(db, input, actor = "admin") {
  const now = Date.now();
  const res = db
    .prepare(
      `INSERT INTO notifications (
        title, body, kind, target_platform, target_role, target_max_version_code,
        target_country, action_url, action_label, force_modal, created_by, created_at, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run(
      input.title,
      input.body,
      input.kind || "info",
      input.target_platform || null,
      input.target_role || null,
      input.target_max_version_code ? Number(input.target_max_version_code) : null,
      input.target_country ? input.target_country.toUpperCase() : null,
      input.action_url || "",
      input.action_label || "",
      input.force_modal ? 1 : 0,
      actor,
      now,
      input.expires_at ? Number(input.expires_at) : null
    );
  return db.prepare("SELECT * FROM notifications WHERE id = ?").get(Number(res.lastInsertRowid));
}

export function listNotifications(db, limit = 50) {
  const rows = db
    .prepare(
      `SELECT n.*,
        (SELECT COUNT(*) FROM notification_deliveries d WHERE d.notification_id = n.id) AS delivered_count,
        (SELECT COUNT(*) FROM notification_deliveries d WHERE d.notification_id = n.id AND d.status = 'opened') AS opened_count
       FROM notifications n ORDER BY n.id DESC LIMIT ?`
    )
    .all(Math.min(100, Math.max(1, Number(limit) || 50)));
  return rows;
}

export function pendingNotificationsForDevice(db, dev, sinceId = 0) {
  const now = Date.now();
  const rows = db
    .prepare(
      `SELECT n.* FROM notifications n
       WHERE n.id > ?
         AND (n.expires_at IS NULL OR n.expires_at >= ?)
         AND NOT EXISTS (
           SELECT 1 FROM notification_deliveries d
           WHERE d.notification_id = n.id AND d.device_id = ? AND d.status IN ('opened', 'dismissed')
         )
       ORDER BY n.id ASC LIMIT 30`
    )
    .all(Math.max(0, Number(sinceId) || 0), now, dev.device_id || "");
  return rows.filter((n) => notificationMatchesDevice(n, dev));
}

export function ackNotification(db, notifId, deviceId, status = "delivered") {
  const safeStatus = ["delivered", "opened", "dismissed"].includes(status) ? status : "delivered";
  const exists = db.prepare("SELECT id FROM notifications WHERE id = ?").get(Number(notifId));
  if (!exists) return false;
  const now = Date.now();
  db.prepare(
    `INSERT INTO notification_deliveries (notification_id, device_id, status, updated_at)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(notification_id, device_id) DO UPDATE SET
       status = CASE
         WHEN notification_deliveries.status IN ('opened', 'dismissed') AND excluded.status = 'delivered'
         THEN notification_deliveries.status
         ELSE excluded.status
       END,
       updated_at = excluded.updated_at`
  ).run(Number(notifId), deviceId, safeStatus, now);
  return true;
}

export function addAudit(db, actor, action, details = "") {
  db.prepare("INSERT INTO audit_logs (actor, action, details, created_at) VALUES (?, ?, ?, ?)").run(
    actor,
    action,
    details,
    Date.now()
  );
}

export function listAudit(db, limit = 50) {
  return db
    .prepare("SELECT * FROM audit_logs ORDER BY id DESC LIMIT ?")
    .all(Math.min(200, Math.max(1, Number(limit) || 50)));
}
