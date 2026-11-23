// Private accounts for Tahoe TV: e-mail + password sign-in, a synced display profile, and uploaded photos / wallpapers.
//  - passwords: scrypt (per-user salt, parameters stored with the hash), checked in constant time; unknown e-mails cost the same time
//  - sessions: random bearer tokens, only their SHA-256 is stored, 30-day sliding expiry, revocable
//  - at rest: profile JSON and every uploaded file are sealed with AES-256-GCM under TAHOE_DATA_KEY (the server refuses to run accounts without it)
//  - in transit: HTTPS only (X-Forwarded-Proto from the reverse proxy), no CORS, never cached
//  - private by default: registration is closed unless an invite code (or TAHOE_REGISTRATION=open) is set
import {
  createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID, scrypt as scryptCb, timingSafeEqual,
} from "node:crypto";
import { promisify } from "node:util";
import { mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const scrypt = promisify(scryptCb);
const SCRYPT = { N: 32768, r: 8, p: 1, keylen: 64, maxmem: 128 * 1024 * 1024 };
const SESSION_TTL_MS = 30 * 24 * 3600_000;
const MAX_SESSIONS = 20;
const MAX_PROFILE_BYTES = 64 * 1024;
const FILE_LIMITS = { avatar: 2 * 1024 * 1024, wallpaper: 8 * 1024 * 1024 };
const MAX_FILES = 40;
const MAX_TOTAL_BYTES = 120 * 1024 * 1024;
const COMMON_PASSWORDS = new Set(["password", "password1", "password123", "1234567890", "qwertyuiop", "iloveyou123", "tahoetv123", "letmein1234"]);

// ---------------------------------------------------------------------------------------------- crypto helpers

export async function hashPassword(password) {
  const salt = randomBytes(16);
  const hash = await scrypt(String(password), salt, SCRYPT.keylen, SCRYPT);
  return `scrypt$${SCRYPT.N}$${SCRYPT.r}$${SCRYPT.p}$${salt.toString("hex")}$${hash.toString("hex")}`;
}

export async function verifyPassword(password, stored) {
  try {
    const [alg, n, r, p, saltHex, hashHex] = String(stored).split("$");
    if (alg !== "scrypt") return false;
    const expected = Buffer.from(hashHex, "hex");
    const actual = await scrypt(String(password), Buffer.from(saltHex, "hex"), expected.length, { N: +n, r: +r, p: +p, maxmem: SCRYPT.maxmem });
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

/** version(1) | iv(12) | tag(16) | ciphertext; [aad] binds the blob to its owner and purpose. */
export function seal(key, plain, aad) {
  const iv = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", key, iv);
  c.setAAD(Buffer.from(aad));
  const ct = Buffer.concat([c.update(plain), c.final()]);
  return Buffer.concat([Buffer.from([1]), iv, c.getAuthTag(), ct]);
}

export function open(key, blob, aad) {
  if (blob.length < 29 || blob[0] !== 1) throw new Error("bad blob");
  const d = createDecipheriv("aes-256-gcm", key, blob.subarray(1, 13));
  d.setAAD(Buffer.from(aad));
  d.setAuthTag(blob.subarray(13, 29));
  return Buffer.concat([d.update(blob.subarray(29)), d.final()]);
}

const sha256 = (s) => createHash("sha256").update(s).digest("hex");

export function normalizeEmail(raw) {
  const e = String(raw ?? "").trim().toLowerCase();
  if (e.length < 6 || e.length > 254) return null;
  return /^[^\s@]{1,64}@[^\s@.]{1,63}(\.[^\s@.]{1,63})+$/.test(e) ? e : null;
}

export function passwordProblem(password, email) {
  const p = String(password ?? "");
  if (p.length < 10) return "Password must be at least 10 characters.";
  if (p.length > 200) return "Password is too long.";
  if (COMMON_PASSWORDS.has(p.toLowerCase()) || /^(.)\1+$/.test(p)) return "That password is too easy to guess.";
  if (email && p.toLowerCase() === email.toLowerCase()) return "Password must not be your e-mail.";
  return null;
}

/** Image kind from its first bytes (the declared type is never trusted). */
export function sniffImage(buf) {
  if (buf.length > 12 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return "image/jpeg";
  if (buf.length > 12 && buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "image/png";
  if (buf.length > 12 && buf.subarray(0, 4).toString("latin1") === "RIFF" && buf.subarray(8, 12).toString("latin1") === "WEBP") return "image/webp";
  return null;
}

/** Fixed-window counter per key. */
class Window {
  constructor(max, ms) { this.max = max; this.ms = ms; this.m = new Map(); }
  hit(key, now = Date.now()) {
    const r = this.m.get(key);
    if (!r || now - r.at >= this.ms) { this.m.set(key, { at: now, n: 1 }); return true; }
    r.n += 1;
    return r.n <= this.max;
  }
  clear(key) { this.m.delete(key); }
}

// ------------------------------------------------------------------------------------------------ the service

function ensureSchema(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password TEXT NOT NULL,
      created_at INTEGER NOT NULL, last_login INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS user_sessions (
      token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      device TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, expires_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON user_sessions(user_id);
    CREATE TABLE IF NOT EXISTS user_profiles (
      user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, blob BLOB NOT NULL, rev INTEGER NOT NULL, updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS user_files (
      id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      kind TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, created_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_user_files_user ON user_files(user_id);
  `);
}

/**
 * @param {object} o
 * @param {import("node:sqlite").DatabaseSync} o.db
 * @param {string} o.dataDir where sealed files live
 * @param {string} o.dataKey 64 hex chars (AES-256 key); without it accounts stay switched off
 * @param {string} [o.inviteCode] if set, registering needs it
 * @param {"closed"|"invite"|"open"} [o.registration] defaults to invite when a code is set, closed otherwise
 * @param {boolean} [o.allowHttp] tests only; production demands HTTPS from the proxy
 */
export function createAccounts({ db, dataDir, dataKey, inviteCode = "", registration, allowHttp = false }) {
  const enabled = /^[0-9a-f]{64}$/i.test(String(dataKey || ""));
  const mode = registration || (inviteCode ? "invite" : "closed");
  const key = enabled ? Buffer.from(dataKey, "hex") : null;
  if (enabled) { ensureSchema(db); mkdirSync(join(dataDir, "accounts"), { recursive: true }); }
  const loginIp = new Window(20, 15 * 60_000);
  const loginMail = new Window(8, 15 * 60_000);
  const registerIp = new Window(5, 3600_000);
  const dummyHash = enabled ? hashPassword("dummy-password-for-timing") : null;

  const json = (res, status, data) => {
    const body = Buffer.from(JSON.stringify(data));
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Content-Length": body.length, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer" });
    res.end(body);
  };
  const fail = (res, status, message) => json(res, status, { error: message });

  async function readBody(req, max) {
    const chunks = []; let n = 0;
    for await (const c of req) {
      n += c.length;
      if (n > max) { const e = new Error("too large"); e.status = 413; throw e; }
      chunks.push(c);
    }
    return Buffer.concat(chunks);
  }
  async function readJson(req, max = 16 * 1024) {
    const b = await readBody(req, max);
    try { const o = JSON.parse(b.toString("utf8") || "{}"); return o && typeof o === "object" && !Array.isArray(o) ? o : {}; } catch { const e = new Error("bad json"); e.status = 400; throw e; }
  }
  const ipOf = (req) => String(req.headers["x-forwarded-for"] || "").split(",")[0].trim() || req.socket?.remoteAddress || "unknown";

  function newSession(userId, device) {
    const token = "tt_" + randomBytes(32).toString("base64url");
    const now = Date.now();
    db.prepare("INSERT INTO user_sessions (token_hash, user_id, device, created_at, last_seen, expires_at) VALUES (?,?,?,?,?,?)")
      .run(sha256(token), userId, String(device || "").slice(0, 60), now, now, now + SESSION_TTL_MS);
    const old = db.prepare("SELECT token_hash FROM user_sessions WHERE user_id = ? ORDER BY last_seen DESC LIMIT -1 OFFSET ?").all(userId, MAX_SESSIONS);
    for (const o of old) db.prepare("DELETE FROM user_sessions WHERE token_hash = ?").run(o.token_hash);
    return token;
  }

  function auth(req) {
    const h = String(req.headers.authorization || "");
    if (!h.startsWith("Bearer tt_")) return null;
    const hash = sha256(h.slice(7));
    const row = db.prepare("SELECT s.token_hash, s.last_seen, s.expires_at, u.id, u.email FROM user_sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?").get(hash);
    const now = Date.now();
    if (!row || row.expires_at < now) { if (row) db.prepare("DELETE FROM user_sessions WHERE token_hash = ?").run(hash); return null; }
    if (now - row.last_seen > 24 * 3600_000) db.prepare("UPDATE user_sessions SET last_seen = ?, expires_at = ? WHERE token_hash = ?").run(now, now + SESSION_TTL_MS, hash);
    return { id: row.id, email: row.email, tokenHash: hash };
  }

  const fileDir = (uid) => join(dataDir, "accounts", uid);
  const fileAad = (uid, fid) => `file:${uid}:${fid}`;
  const profileAad = (uid) => `profile:${uid}`;
  const emptyProfile = () => ({ rev: 0, settings: {} });

  function readProfile(uid) {
    const row = db.prepare("SELECT blob, rev FROM user_profiles WHERE user_id = ?").get(uid);
    if (!row) return emptyProfile();
    return { rev: row.rev, settings: JSON.parse(open(key, Buffer.from(row.blob), profileAad(uid)).toString("utf8")) };
  }

  function wipeUser(uid) {
    db.prepare("DELETE FROM users WHERE id = ?").run(uid); // sessions, profile and file rows cascade
    try { rmSync(fileDir(uid), { recursive: true, force: true }); } catch { /* nothing to remove */ }
  }

  /** Returns true when the request was an account request (answered here). */
  async function handle(req, res, path, method, url) {
    if (!path.startsWith("/api/account")) return false;
    if (!enabled) { fail(res, 503, "Accounts are not set up on this server."); return true; }
    if (!allowHttp && String(req.headers["x-forwarded-proto"] || "https") !== "https") { fail(res, 403, "HTTPS required."); return true; }
    try {
      const ip = ipOf(req);

      if (method === "POST" && path === "/api/account/register") {
        if (mode === "closed") return fail(res, 403, "Registration is closed."), true;
        if (!registerIp.hit(ip)) return fail(res, 429, "Too many attempts. Try again later."), true;
        const b = await readJson(req);
        if (mode === "invite") {
          const given = Buffer.from(String(b.invite ?? "")); const want = Buffer.from(inviteCode);
          if (given.length !== want.length || !timingSafeEqual(given, want)) return fail(res, 403, "Invite code is not valid."), true;
        }
        const email = normalizeEmail(b.email);
        if (!email) return fail(res, 400, "Enter a valid e-mail."), true;
        const problem = passwordProblem(b.password, email);
        if (problem) return fail(res, 400, problem), true;
        if (db.prepare("SELECT 1 FROM users WHERE email = ?").get(email)) return fail(res, 409, "That e-mail is already registered."), true;
        const id = randomUUID(); const now = Date.now();
        db.prepare("INSERT INTO users (id, email, password, created_at, last_login) VALUES (?,?,?,?,?)").run(id, email, await hashPassword(b.password), now, now);
        return json(res, 201, { token: newSession(id, b.device), user: { id, email } }), true;
      }

      if (method === "POST" && path === "/api/account/login") {
        const b = await readJson(req);
        const email = normalizeEmail(b.email) || "invalid";
        if (!loginIp.hit(ip) || !loginMail.hit(email)) return fail(res, 429, "Too many attempts. Try again in 15 minutes."), true;
        const u = db.prepare("SELECT id, email, password FROM users WHERE email = ?").get(email);
        const ok = await verifyPassword(String(b.password ?? ""), u ? u.password : await dummyHash);
        if (!u || !ok) return fail(res, 401, "E-mail or password is wrong."), true;
        loginMail.clear(email);
        db.prepare("UPDATE users SET last_login = ? WHERE id = ?").run(Date.now(), u.id);
        return json(res, 200, { token: newSession(u.id, b.device), user: { id: u.id, email: u.email } }), true;
      }

      const me = auth(req);
      if (!me) return fail(res, 401, "Sign in first."), true;

      if (method === "GET" && path === "/api/account/me") {
        const sessions = db.prepare("SELECT device, created_at, last_seen FROM user_sessions WHERE user_id = ? ORDER BY last_seen DESC").all(me.id);
        return json(res, 200, { user: { id: me.id, email: me.email }, sessions }), true;
      }
      if (method === "POST" && path === "/api/account/logout") {
        db.prepare("DELETE FROM user_sessions WHERE token_hash = ?").run(me.tokenHash);
        return json(res, 200, { ok: true }), true;
      }
      if (method === "POST" && path === "/api/account/password") {
        const b = await readJson(req);
        if (!loginMail.hit("pw:" + me.id)) return fail(res, 429, "Too many attempts."), true;
        const u = db.prepare("SELECT password FROM users WHERE id = ?").get(me.id);
        if (!(await verifyPassword(String(b.old ?? ""), u.password))) return fail(res, 401, "Current password is wrong."), true;
        const problem = passwordProblem(b.new, me.email);
        if (problem) return fail(res, 400, problem), true;
        db.prepare("UPDATE users SET password = ? WHERE id = ?").run(await hashPassword(b.new), me.id);
        db.prepare("DELETE FROM user_sessions WHERE user_id = ? AND token_hash <> ?").run(me.id, me.tokenHash); // every other device signs in again
        return json(res, 200, { ok: true }), true;
      }
      if (method === "POST" && path === "/api/account/delete") {
        const b = await readJson(req);
        const u = db.prepare("SELECT password FROM users WHERE id = ?").get(me.id);
        if (!(await verifyPassword(String(b.password ?? ""), u.password))) return fail(res, 401, "Password is wrong."), true;
        wipeUser(me.id);
        return json(res, 200, { ok: true }), true;
      }

      if (path === "/api/account/profile") {
        if (method === "GET") return json(res, 200, readProfile(me.id)), true;
        if (method === "PUT") {
          const b = await readJson(req, MAX_PROFILE_BYTES + 1024);
          if (!b.settings || typeof b.settings !== "object" || Array.isArray(b.settings)) return fail(res, 400, "settings must be an object."), true;
          const text = JSON.stringify(b.settings);
          if (Buffer.byteLength(text) > MAX_PROFILE_BYTES) return fail(res, 413, "Profile is too large."), true;
          const cur = readProfile(me.id);
          if (Number.isInteger(b.base_rev) && b.base_rev !== cur.rev) return json(res, 409, { error: "Profile changed on another device.", ...cur }), true;
          const rev = cur.rev + 1;
          db.prepare("INSERT INTO user_profiles (user_id, blob, rev, updated_at) VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET blob = excluded.blob, rev = excluded.rev, updated_at = excluded.updated_at")
            .run(me.id, seal(key, Buffer.from(text), profileAad(me.id)), rev, Date.now());
          return json(res, 200, { rev }), true;
        }
      }

      if (path === "/api/account/files") {
        if (method === "GET") {
          return json(res, 200, { files: db.prepare("SELECT id, kind, name, mime, size, created_at FROM user_files WHERE user_id = ? ORDER BY created_at DESC").all(me.id) }), true;
        }
        if (method === "POST") {
          const kind = url.searchParams.get("kind");
          if (!FILE_LIMITS[kind]) return fail(res, 400, "kind must be avatar or wallpaper."), true;
          const data = await readBody(req, FILE_LIMITS[kind]);
          const mime = sniffImage(data);
          if (!mime) return fail(res, 415, "Only JPEG, PNG or WebP images."), true;
          const tot = db.prepare("SELECT COUNT(*) AS n, COALESCE(SUM(size),0) AS bytes FROM user_files WHERE user_id = ?").get(me.id);
          if (tot.n >= MAX_FILES || tot.bytes + data.length > MAX_TOTAL_BYTES) return fail(res, 413, "Storage is full: delete something first."), true;
          const id = randomUUID();
          mkdirSync(fileDir(me.id), { recursive: true });
          writeFileSync(join(fileDir(me.id), id + ".bin"), seal(key, data, fileAad(me.id, id)), { mode: 0o600 });
          const name = String(url.searchParams.get("name") || kind).replace(/[^\w .()-]/g, "").slice(0, 60) || kind;
          db.prepare("INSERT INTO user_files (id, user_id, kind, name, mime, size, created_at) VALUES (?,?,?,?,?,?,?)").run(id, me.id, kind, name, mime, data.length, Date.now());
          return json(res, 201, { id, kind, name, mime, size: data.length }), true;
        }
      }

      const m = /^\/api\/account\/files\/([0-9a-f-]{36})$/.exec(path);
      if (m) {
        const row = db.prepare("SELECT id, mime FROM user_files WHERE id = ? AND user_id = ?").get(m[1], me.id); // only your own
        if (!row) return fail(res, 404, "Not found."), true;
        if (method === "GET") {
          const plain = open(key, readFileSync(join(fileDir(me.id), row.id + ".bin")), fileAad(me.id, row.id));
          res.writeHead(200, { "Content-Type": row.mime, "Content-Length": plain.length, "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "default-src 'none'", "Referrer-Policy": "no-referrer" });
          res.end(plain);
          return true;
        }
        if (method === "DELETE") {
          db.prepare("DELETE FROM user_files WHERE id = ?").run(row.id);
          try { rmSync(join(fileDir(me.id), row.id + ".bin"), { force: true }); } catch { /* already gone */ }
          return json(res, 200, { ok: true }), true;
        }
      }

      return fail(res, 404, "Not found."), true;
    } catch (e) {
      return fail(res, e.status || 500, e.status ? e.message : "Something went wrong."), true;
    }
  }

  return { enabled, mode, handle, wipeUser };
}
