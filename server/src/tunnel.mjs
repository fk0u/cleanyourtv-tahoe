// Blind relay for off-LAN pairing ("tunnel"): a TV and its controllers meet in a room named by a secret
// derived from their pairing token. Frames are end-to-end encrypted by the clients (AES-GCM); this server only
// forwards opaque text between the two sides and never stores anything.
const ROOM_RE = /^[0-9a-f]{64}$/;
const MAX_ROOMS = 5000;
const MAX_LISTENERS = 4;      // per role per room
const MAX_FRAME = 16 * 1024;  // bytes
const RATE_WINDOW_MS = 10_000;
const RATE_MAX = 80;          // frames per room per window

export function createTunnel() {
  const rooms = new Map(); // id -> { tv:Set, ctl:Set, hits:number[] }

  const other = (role) => (role === "tv" ? "ctl" : "tv");
  const valid = (room, role) => ROOM_RE.test(room || "") && (role === "tv" || role === "ctl");

  function attach(room, role, req, res, cors) {
    if (!valid(room, role)) { res.writeHead(400, cors); res.end(); return; }
    let r = rooms.get(room);
    if (!r) {
      if (rooms.size >= MAX_ROOMS) { res.writeHead(503, cors); res.end(); return; }
      r = { tv: new Set(), ctl: new Set(), hits: [] };
      rooms.set(room, r);
    }
    if (r[role].size >= MAX_LISTENERS) { res.writeHead(429, cors); res.end(); return; }
    res.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
      ...cors,
    });
    r[role].add(res);
    res.write(`event: hello\ndata: {"peers":${r[other(role)].size}}\n\n`);
    // tell the other side someone arrived, so it can re-announce itself
    for (const o of r[other(role)]) { try { o.write(`event: peer\ndata: {"role":"${role}"}\n\n`); } catch { /* gone */ } }
    req.on("close", () => {
      r[role].delete(res);
      if (!r.tv.size && !r.ctl.size) rooms.delete(room);
    });
  }

  /** Returns {status, delivered}. */
  function send(room, role, body) {
    if (!valid(room, role)) return { status: 400 };
    if (typeof body !== "string" || !body.length || Buffer.byteLength(body) > MAX_FRAME) return { status: 413 };
    const r = rooms.get(room);
    if (!r) return { status: 202, delivered: 0 };
    const now = Date.now();
    r.hits = r.hits.filter((t) => now - t < RATE_WINDOW_MS);
    if (r.hits.length >= RATE_MAX) return { status: 429 };
    r.hits.push(now);
    let delivered = 0;
    const line = `data: ${body.replace(/[\r\n]/g, "")}\n\n`;
    for (const o of r[other(role)]) { try { o.write(line); delivered++; } catch { /* gone */ } }
    return { status: delivered ? 200 : 202, delivered };
  }

  function ping() {
    for (const r of rooms.values()) for (const set of [r.tv, r.ctl]) for (const o of set) { try { o.write(": ping\n\n"); } catch { set.delete(o); } }
  }

  function closeAll() { for (const r of rooms.values()) for (const set of [r.tv, r.ctl]) for (const o of set) { try { o.end(); } catch { /* ignore */ } } rooms.clear(); }

  return { attach, send, ping, closeAll, size: () => rooms.size };
}
