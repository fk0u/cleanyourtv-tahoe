import test from "node:test";
import assert from "node:assert/strict";
import { openDb } from "../src/db.mjs";
import { ensureBootstrapAdmin } from "../src/auth.mjs";
import { createTahoeServer, parseM3uSummary, withPlayableStream } from "../src/app.mjs";

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
