import {
  createHmac,
  randomBytes,
  scryptSync,
  timingSafeEqual,
} from "node:crypto";

const TOKEN_TTL_MS = 7 * 24 * 3600_000;
const MAX_LOGIN_FAILS = 6;
const LOCKOUT_MS = 15 * 60_000;

/** Per-IP brute-force limiter for admin login. */
export class LoginLimiter {
  constructor() {
    this.map = new Map();
  }

  check(key) {
    const rec = this.map.get(key);
    if (!rec) return 0;
    const left = rec.lockUntil - Date.now();
    if (left <= 0 && rec.lockUntil > 0) {
      this.map.delete(key);
      return 0;
    }
    return left > 0 ? Math.ceil(left / 1000) : 0;
  }

  fail(key) {
    const now = Date.now();
    const rec = this.map.get(key) || { fails: 0, lockUntil: 0 };
    rec.fails += 1;
    if (rec.fails >= MAX_LOGIN_FAILS) {
      rec.lockUntil = now + LOCKOUT_MS;
    }
    this.map.set(key, rec);
    return rec.lockUntil > now ? Math.ceil((rec.lockUntil - now) / 1000) : 0;
  }

  reset(key) {
    this.map.delete(key);
  }
}

export function hashPassword(password, salt = randomBytes(16).toString("hex")) {
  const hash = scryptSync(String(password), salt, 64).toString("hex");
  return { salt, password_hash: hash };
}

export function verifyPassword(password, salt, expectedHex) {
  try {
    const actual = scryptSync(String(password), String(salt), 64);
    const expected = Buffer.from(String(expectedHex), "hex");
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

export function ensureBootstrapAdmin(db, username, password) {
  if (!username || !password) return false;
  const existing = db.prepare("SELECT username FROM admin_users WHERE username = ?").get(username);
  const { salt, password_hash } = hashPassword(password);
  const now = Date.now();
  if (!existing) {
    db.prepare(
      "INSERT INTO admin_users (username, salt, password_hash, token_version, created_at) VALUES (?, ?, ?, 1, ?)"
    ).run(username, salt, password_hash, now);
    return true;
  }
  return false;
}

export function resetAdminPassword(db, username, password) {
  const { salt, password_hash } = hashPassword(password);
  const now = Date.now();
  db.prepare(
    `INSERT INTO admin_users (username, salt, password_hash, token_version, created_at)
     VALUES (?, ?, ?, 1, ?)
     ON CONFLICT(username) DO UPDATE SET
       salt = excluded.salt,
       password_hash = excluded.password_hash,
       token_version = admin_users.token_version + 1`
  ).run(username, salt, password_hash, now);
}

export function signAdminToken(payload, secret) {
  const data = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = createHmac("sha256", secret).update(data).digest("base64url");
  return `${data}.${sig}`;
}

export function verifyAdminToken(token, secret, db) {
  if (!token || typeof token !== "string") return null;
  const parts = token.split(".");
  if (parts.length !== 2) return null;
  const [data, sig] = parts;
  const expectedSig = createHmac("sha256", secret).update(data).digest();
  let actualSig;
  try {
    actualSig = Buffer.from(sig, "base64url");
  } catch {
    return null;
  }
  if (actualSig.length !== expectedSig.length || !timingSafeEqual(actualSig, expectedSig)) {
    return null;
  }
  let payload;
  try {
    payload = JSON.parse(Buffer.from(data, "base64url").toString("utf8"));
  } catch {
    return null;
  }
  if (!payload || !payload.u || !payload.exp || Date.now() > payload.exp) return null;
  const user = db
    .prepare("SELECT username, token_version FROM admin_users WHERE username = ?")
    .get(payload.u);
  if (!user || Number(user.token_version) !== Number(payload.tv)) return null;
  return { username: user.username, exp: payload.exp };
}

export function issueAdminSession(db, username, secret) {
  const row = db
    .prepare("SELECT username, token_version FROM admin_users WHERE username = ?")
    .get(username);
  if (!row) return null;
  const exp = Date.now() + TOKEN_TTL_MS;
  db.prepare("UPDATE admin_users SET last_login_at = ? WHERE username = ?").run(Date.now(), username);
  const token = signAdminToken({ u: row.username, tv: row.token_version, exp }, secret);
  return { token, username: row.username, expires_at: exp };
}

export function parseCookies(header = "") {
  const out = {};
  for (const part of String(header).split(";")) {
    const idx = part.indexOf("=");
    if (idx <= 0) continue;
    const k = part.slice(0, idx).trim();
    const v = part.slice(idx + 1).trim();
    if (k) out[k] = decodeURIComponent(v);
  }
  return out;
}

export function extractBearerOrCookie(req) {
  const auth = req.headers.authorization || "";
  if (auth.startsWith("Bearer ")) return auth.slice(7).trim();
  const cookies = parseCookies(req.headers.cookie || "");
  return cookies.tahoe_admin || "";
}
