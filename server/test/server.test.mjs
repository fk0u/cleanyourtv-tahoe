import test from "node:test";
import assert from "node:assert/strict";
import { openDb } from "../src/db.mjs";
import { ensureBootstrapAdmin } from "../src/auth.mjs";
import { createTahoeServer, parseM3uSummary, withPlayableStream, summarizeCatalog } from "../src/app.mjs";

async function startTestServer() {
  const db = openDb(":memory:");
  ensureBootstrapAdmin(db, "admin", "SuperSecretPass123!");
  const server = createTahoeServer({
    db,
    sessionSecret: "test-secret-0123456789abcdef0123456789abcdef",
    secureCookies: false,
    fetchRelease: async () => ({
      version: "3.4.0",
      tag_name: "v3.4.0",
      notes: "Test notes",
      page: "https://github.com/fk0u/cleanyourtv-tahoe/releases/tag/v3.4.0",
      date: "2026-11-15",
      assets: [
        {
          name: "TahoeTV-Android-TV.apk",
          url: "https://example.com/tv.apk",
          size: 12345,
          sha256: "a".repeat(64),
        },
      ],
    }),
    fetchCatalog: async () => ({ channels: 3, countries: 2 }),
    fetchChannelPreview: async () => [
      { name: "TVRI Nasional", group: "Nasional", logo: "", format: "HLS", drm: false, source: "test", stream: "https://tvri.example/live.m3u8" },
      { name: "RCTI", group: "Entertainment", logo: "", format: "DASH", drm: true, source: "test" },
    ],
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const { port } = server.address();
  return {
    base: `http://127.0.0.1:${port}`,
    close: () =>
      new Promise((r) => {
        server.closeAll();
        server.close(() => {
          db.close();
          r();
        });
      }),
  };
}

test("heartbeat registers device, counts fleet, and admin broadcasts live push over SSE", async () => {
  const srv = await startTestServer();
  try {
    // 1. Heartbeats from two distinct devices
    const r1 = await fetch(`${srv.base}/api/devices/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        device_id: "tv-stick-11111111",
        app_role: "tv_home",
        platform: "android_tv",
        app_version: "3.4.0",
        version_code: 28,
        device_model: "MiTV_AESP0",
        country: "ID",
        lang: "id",
      }),
    });
    assert.equal(r1.status, 200);
    const j1 = await r1.json();
    assert.equal(j1.ok, true);
    assert.equal(j1.latest_release.version, "3.4.0");

    const r2 = await fetch(`${srv.base}/api/devices/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        device_id: "mac-intel-22222222",
        app_role: "desktop",
        platform: "macos_intel",
        app_version: "3.3.1",
        version_code: 27,
        device_model: "MacBookPro15,2",
        country: "ID",
        lang: "en",
      }),
    });
    assert.equal(r2.status, 200);

    // 2. Public stats reflect installed count without admin auth
    const pub = await (await fetch(`${srv.base}/api/public/stats`)).json();
    assert.equal(pub.total_installed, 2);
    assert.equal(pub.active_24h, 2);

    // 3. Unauthorized admin call rejected
    const unauth = await fetch(`${srv.base}/api/admin/overview`);
    assert.equal(unauth.status, 401);

    // 4. Admin login + overview check
    const loginRes = await fetch(`${srv.base}/api/admin/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "admin", password: "SuperSecretPass123!" }),
    });
    assert.equal(loginRes.status, 200);
    const { token } = await loginRes.json();
    assert.ok(token);

    const ov = await (
      await fetch(`${srv.base}/api/admin/overview`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json();
    assert.equal(ov.overview.total_installed, 2);
    assert.equal(ov.overview.by_platform.length, 2);

    // 5. SSE stream receives live notification immediately when admin posts
    const ac = new AbortController();
    const sseRes = await fetch(
      `${srv.base}/api/push/stream?device_id=tv-stick-11111111&platform=android_tv&app_role=tv_home`,
      { signal: ac.signal }
    );
    assert.equal(sseRes.status, 200);
    const reader = sseRes.body.getReader();
    const dec = new TextDecoder();

    // Read initial hello
    const firstChunk = dec.decode((await reader.read()).value);
    assert.ok(firstChunk.includes("event: hello"));

    // Broadcast targeted push for android_tv
    const pushRes = await fetch(`${srv.base}/api/admin/notifications`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        title: "Pembaruan Siap",
        body: "Versi 3.4.0 membawa kontrol modal & channel baru.",
        kind: "update",
        target_platform: "android_tv",
        force_modal: true,
      }),
    });
    assert.equal(pushRes.status, 201);
    const pushJson = await pushRes.json();
    assert.equal(pushJson.live_sse_delivered, 1);

    const nextChunk = dec.decode((await reader.read()).value);
    assert.ok(nextChunk.includes("event: push"));
    assert.ok(nextChunk.includes("Pembaruan Siap"));
    ac.abort();

    // 6. Pending poll + ack
    const pendingBefore = await (
      await fetch(`${srv.base}/api/push/pending?device_id=tv-stick-11111111`)
    ).json();
    assert.equal(pendingBefore.notifications.length, 1);

    await fetch(`${srv.base}/api/push/ack`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        device_id: "tv-stick-11111111",
        notification_id: pushJson.notification.id,
        status: "opened",
      }),
    });

    const pendingAfter = await (
      await fetch(`${srv.base}/api/push/pending?device_id=tv-stick-11111111`)
    ).json();
    assert.equal(pendingAfter.notifications.length, 0);

    // 7. Config update policy
    const cfgRes = await fetch(`${srv.base}/api/admin/config`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        update_policy: { force_modal: true, min_version_code: 28, recommended_version: "3.4.0" },
        channel_overrides: { blocked_hosts: ["bad.example"], announcement: "Maintenance selesai" },
      }),
    });
    assert.equal(cfgRes.status, 200);
    const chPreview = await (await fetch(`${srv.base}/api/public/channels/preview?q=rcti`)).json();
    assert.equal(chPreview.filtered, 1);
    assert.equal(chPreview.items[0].stream, "");
    const tvri = await (await fetch(`${srv.base}/api/public/channels/preview?q=tvri`)).json();
    assert.equal(tvri.items[0].stream, "https://tvri.example/live.m3u8");
  } finally {
    await srv.close();
  }
});

test("web player only gets plain https HLS streams, never DRM, DASH, http or blocked hosts", () => {
  const items = parseM3uSummary(
    [
      "#EXTM3U",
      '#EXTINF:-1 group-title="News",Open HLS',
      "https://cdn.example/open/index.m3u8",
      '#EXTINF:-1 group-title="News",Plain HTTP',
      "http://cdn.example/plain/index.m3u8",
      '#EXTINF:-1 group-title="Movies",Dash',
      "https://cdn.example/dash/manifest.mpd",
      '#EXTINF:-1 group-title="Sports",Locked',
      "#KODIPROP:inputstream.adaptive.license_type=clearkey",
      "https://cdn.example/locked/index.m3u8",
    ].join("\n"),
    "test"
  );
  const byName = Object.fromEntries(items.map((c) => [c.name, c.stream]));
  assert.equal(byName["Open HLS"], "https://cdn.example/open/index.m3u8");
  assert.equal(byName["Plain HTTP"], "");
  assert.equal(byName["Dash"], "");
  assert.equal(byName["Locked"], "");
  assert.equal(withPlayableStream(items[0], ["example"]).stream, "");
  assert.equal(withPlayableStream(items[0], ["other.org"]).stream, "https://cdn.example/open/index.m3u8");
  assert.equal(withPlayableStream({ name: "x", drm: true, stream: "https://a.example/x.m3u8" }).stream, "");
});

test("tunnel relays opaque frames between a tv and its controller, rejects bad rooms, never stores", async () => {
  const srv = await startTestServer();
  const room = "a".repeat(64);
  const ac = new AbortController();
  try {
    const got = [];
    const res = await fetch(`${srv.base}/api/tunnel/stream?room=${room}&role=ctl`, { signal: ac.signal });
    assert.equal(res.status, 200);
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    const pump = (async () => { try { for (;;) { const { value, done } = await reader.read(); if (done) break; buf += dec.decode(value); } } catch {} })();
    await new Promise((r) => setTimeout(r, 100));
    const ok = await fetch(`${srv.base}/api/tunnel/send?room=${room}&role=tv`, { method: "POST", body: "ciphertext-1" });
    assert.equal(ok.status, 200);
    assert.equal((await ok.json()).delivered, 1);
    await new Promise((r) => setTimeout(r, 100));
    assert.match(buf, /data: ciphertext-1/);
    // nobody on the tv side: accepted but not delivered
    const none = await fetch(`${srv.base}/api/tunnel/send?room=${room}&role=ctl`, { method: "POST", body: "x" });
    assert.equal(none.status, 202);
    // bad room id and oversize frames are refused
    assert.equal((await fetch(`${srv.base}/api/tunnel/stream?room=nope&role=tv`)).status, 400);
    assert.equal((await fetch(`${srv.base}/api/tunnel/send?room=${room}&role=tv`, { method: "POST", body: "z".repeat(20000) })).status, 413);
    void got; void pump;
  } finally {
    ac.abort();
    await srv.close();
  }
});

test("catalogue counts are real: unique channels and the countries in their ids, served from the fetched index", async () => {
  assert.deepEqual(summarizeCatalog([{ channel: "RCTI.id" }, { channel: "RCTI.id@HD" }, { channel: "BBCOne.uk" }, { channel: null }, {}]), { channels: 2, countries: 2 });
  assert.deepEqual(summarizeCatalog(null), { channels: 0, countries: 0 });
  const srv = await startTestServer();
  try {
    const r = await fetch(`${srv.base}/api/public/catalog`);
    assert.equal(r.status, 200);
    const j = await r.json();
    assert.equal(j.channels, 3);
    assert.equal(j.countries, 2);
    assert.ok(j.updated_at);
  } finally { await srv.close(); }
});

// ------------------------------------------------------------------------------------------------ accounts
import { createAccounts, sniffImage, seal, open as unseal } from "../src/accounts.mjs";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

async function startAccountServer(extra = {}) {
  const db = openDb(":memory:");
  const accounts = createAccounts({ db, dataDir: mkdtempSync(join(tmpdir(), "tahoe-acc-")), dataKey: "ab".repeat(32), inviteCode: "let-me-in-please", allowHttp: true, ...extra });
  const server = createTahoeServer({ db, sessionSecret: "s".repeat(40), secureCookies: false, accounts, fetchCatalog: async () => ({}) });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (path, { method = "GET", body, token, raw, headers = {} } = {}) => {
    const r = await fetch(base + path, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body && !raw ? { "Content-Type": "application/json" } : {}), ...headers }, body: raw ?? (body ? JSON.stringify(body) : undefined) });
    const buf = Buffer.from(await r.arrayBuffer());
    let json = null; try { json = JSON.parse(buf.toString()); } catch { /* binary */ }
    return { status: r.status, json, buf, headers: r.headers };
  };
  return { call, db, close: () => new Promise((r) => { server.closeAll(); server.close(() => { db.close(); r(); }); }) };
}
const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(64, 7)]);

test("accounts: register needs the invite, login is generic, tokens are stored hashed", async () => {
  const s = await startAccountServer();
  try {
    const cred = { email: "Tester@Example.com", password: "correct horse battery" };
    assert.equal((await s.call("/api/account/register", { method: "POST", body: cred })).status, 403);
    const ok = await s.call("/api/account/register", { method: "POST", body: { ...cred, invite: "let-me-in-please" } });
    assert.equal(ok.status, 201);
    assert.equal(ok.json.user.email, "tester@example.com");
    assert.equal((await s.call("/api/account/register", { method: "POST", body: { ...cred, invite: "let-me-in-please" } })).status, 409);
    assert.equal((await s.call("/api/account/register", { method: "POST", body: { email: "x@y.zz", password: "short", invite: "let-me-in-please" } })).status, 400);
    const bad = await s.call("/api/account/login", { method: "POST", body: { email: cred.email, password: "nope-nope-nope" } });
    const ghost = await s.call("/api/account/login", { method: "POST", body: { email: "ghost@example.com", password: "nope-nope-nope" } });
    assert.equal(bad.status, 401); assert.deepEqual(bad.json, ghost.json);
    const login = await s.call("/api/account/login", { method: "POST", body: cred });
    assert.equal(login.status, 200);
    assert.match(login.json.token, /^tt_/);
    assert.equal((await s.call("/api/account/me", { token: login.json.token })).json.user.email, "tester@example.com");
    assert.equal((await s.call("/api/account/me")).status, 401);
    assert.equal(s.db.prepare("SELECT COUNT(*) n FROM user_sessions WHERE token_hash = ?").get(login.json.token).n, 0, "raw token never stored");
    assert.match(s.db.prepare("SELECT password FROM users").get().password, /^scrypt\$/);
    assert.equal((await s.call("/api/account/logout", { method: "POST", token: login.json.token })).status, 200);
    assert.equal((await s.call("/api/account/me", { token: login.json.token })).status, 401);
  } finally { await s.close(); }
});

test("accounts: closed registration, HTTPS rule and missing key fail closed", async () => {
  const closed = await startAccountServer({ inviteCode: "" });
  try { assert.equal((await closed.call("/api/account/register", { method: "POST", body: { email: "a@b.cc", password: "long enough pw" } })).status, 403); } finally { await closed.close(); }
  const nokey = await startAccountServer({ dataKey: "" });
  try { assert.equal((await nokey.call("/api/account/login", { method: "POST", body: {} })).status, 503); } finally { await nokey.close(); }
  const https = await startAccountServer({ allowHttp: false });
  try {
    assert.equal((await https.call("/api/account/login", { method: "POST", body: {}, headers: { "x-forwarded-proto": "http" } })).status, 403);
    assert.notEqual((await https.call("/api/account/login", { method: "POST", body: {}, headers: { "x-forwarded-proto": "https" } })).status, 403);
  } finally { await https.close(); }
});

test("accounts: login locks after repeated failures", async () => {
  const s = await startAccountServer();
  try {
    await s.call("/api/account/register", { method: "POST", body: { email: "lock@example.com", password: "long enough pw", invite: "let-me-in-please" } });
    let last = 0;
    for (let i = 0; i < 10; i++) last = (await s.call("/api/account/login", { method: "POST", body: { email: "lock@example.com", password: "wrong-wrong" } })).status;
    assert.equal(last, 429);
  } finally { await s.close(); }
});

test("accounts: profile syncs with revisions, is sealed at rest, and files are private", async () => {
  const s = await startAccountServer();
  try {
    const reg = async (email) => (await s.call("/api/account/register", { method: "POST", body: { email, password: "long enough pw", invite: "let-me-in-please" } })).json.token;
    const a = await reg("a@example.com"); const b = await reg("b@example.com");
    assert.deepEqual((await s.call("/api/account/profile", { token: a })).json, { rev: 0, settings: {} });
    const put = await s.call("/api/account/profile", { method: "PUT", token: a, body: { settings: { theme: "dark", clock: 24 }, base_rev: 0 } });
    assert.equal(put.json.rev, 1);
    assert.equal((await s.call("/api/account/profile", { method: "PUT", token: a, body: { settings: { theme: "x" }, base_rev: 0 } })).status, 409);
    assert.equal((await s.call("/api/account/profile", { token: a })).json.settings.theme, "dark");
    assert.equal(Buffer.from(s.db.prepare("SELECT blob FROM user_profiles").get().blob).includes("dark"), false, "profile is encrypted at rest");
    assert.equal((await s.call("/api/account/profile", { token: b })).json.rev, 0, "profiles are per user");

    assert.equal((await s.call("/api/account/files?kind=avatar", { method: "POST", token: a, raw: Buffer.from("<script>alert(1)</script>") })).status, 415);
    assert.equal((await s.call("/api/account/files?kind=banner", { method: "POST", token: a, raw: PNG })).status, 400);
    const up = await s.call("/api/account/files?kind=avatar&name=me.png", { method: "POST", token: a, raw: PNG });
    assert.equal(up.status, 201); assert.equal(up.json.mime, "image/png");
    const got = await s.call(`/api/account/files/${up.json.id}`, { token: a });
    assert.equal(got.status, 200); assert.ok(got.buf.equals(PNG)); assert.equal(got.headers.get("content-type"), "image/png");
    assert.equal((await s.call(`/api/account/files/${up.json.id}`, { token: b })).status, 404, "someone else's file");
    assert.equal((await s.call(`/api/account/files/${up.json.id}`)).status, 401);
    assert.equal((await s.call("/api/account/files", { token: a })).json.files.length, 1);
    assert.equal((await s.call(`/api/account/files/${up.json.id}`, { method: "DELETE", token: a })).status, 200);
    assert.equal((await s.call(`/api/account/files/${up.json.id}`, { token: a })).status, 404);
  } finally { await s.close(); }
});

test("accounts: password change signs out other devices; delete wipes everything", async () => {
  const s = await startAccountServer();
  try {
    const cred = { email: "pw@example.com", password: "long enough pw" };
    await s.call("/api/account/register", { method: "POST", body: { ...cred, invite: "let-me-in-please" } });
    const t1 = (await s.call("/api/account/login", { method: "POST", body: cred })).json.token;
    const t2 = (await s.call("/api/account/login", { method: "POST", body: cred })).json.token;
    assert.equal((await s.call("/api/account/password", { method: "POST", token: t1, body: { old: "wrong wrong wrong", new: "another long pass" } })).status, 401);
    assert.equal((await s.call("/api/account/password", { method: "POST", token: t1, body: { old: cred.password, new: "another long pass" } })).status, 200);
    assert.equal((await s.call("/api/account/me", { token: t2 })).status, 401);
    assert.equal((await s.call("/api/account/me", { token: t1 })).status, 200);
    await s.call("/api/account/files?kind=avatar", { method: "POST", token: t1, raw: PNG });
    assert.equal((await s.call("/api/account/delete", { method: "POST", token: t1, body: { password: "another long pass" } })).status, 200);
    assert.equal(s.db.prepare("SELECT COUNT(*) n FROM users").get().n, 0);
    assert.equal(s.db.prepare("SELECT COUNT(*) n FROM user_files").get().n, 0);
  } finally { await s.close(); }
});

test("accounts: sealing detects tampering and wrong owner; images are sniffed", () => {
  const key = Buffer.alloc(32, 9);
  const blob = seal(key, Buffer.from("hello"), "file:u:1");
  assert.equal(unseal(key, blob, "file:u:1").toString(), "hello");
  assert.throws(() => unseal(key, blob, "file:other:1"));
  blob[blob.length - 1] ^= 1;
  assert.throws(() => unseal(key, blob, "file:u:1"));
  assert.equal(sniffImage(PNG), "image/png");
  assert.equal(sniffImage(Buffer.from("GIF89a-not-allowed-here")), null);
});
