import { randomBytes } from "node:crypto";
import { openDb } from "./db.mjs";
import { ensureBootstrapAdmin, resetAdminPassword } from "./auth.mjs";
import { createTahoeServer } from "./app.mjs";

const HOST = process.env.HOST || "127.0.0.1";
const PORT = Number(process.env.PORT || 3140);
const DB_PATH = process.env.TAHOE_DB_PATH || "/var/lib/tahoe-server/tahoe.db";
const SESSION_SECRET =
  process.env.TAHOE_SESSION_SECRET || randomBytes(32).toString("hex");
const ADMIN_USER = process.env.TAHOE_ADMIN_USER || "";
const ADMIN_PASSWORD = process.env.TAHOE_ADMIN_PASSWORD || "";
const FORCE_SYNC_PASSWORD = process.env.TAHOE_ADMIN_SYNC === "1";

const db = openDb(DB_PATH);

if (ADMIN_USER && ADMIN_PASSWORD) {
  if (FORCE_SYNC_PASSWORD) {
    resetAdminPassword(db, ADMIN_USER, ADMIN_PASSWORD);
    console.log(`[tahoe-server] synced admin user: ${ADMIN_USER}`);
  } else {
    const created = ensureBootstrapAdmin(db, ADMIN_USER, ADMIN_PASSWORD);
    if (created) console.log(`[tahoe-server] bootstrapped admin user: ${ADMIN_USER}`);
  }
}

const server = createTahoeServer({
  db,
  sessionSecret: SESSION_SECRET,
  secureCookies: process.env.TAHOE_INSECURE_COOKIES !== "1",
});

server.listen(PORT, HOST, () => {
  console.log(`[tahoe-server] listening on http://${HOST}:${PORT} (db=${DB_PATH})`);
});

for (const sig of ["SIGTERM", "SIGINT"]) {
  process.on(sig, () => {
    server.closeAll();
    server.close(() => {
      db.close();
      process.exit(0);
    });
  });
}
