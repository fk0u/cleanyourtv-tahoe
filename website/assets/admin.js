(function () {
  const $ = s => document.querySelector(s);
  const esc = s => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      credentials: "include",
      headers: { "Content-Type": "application/json", ...(opts.headers || {}) },
      ...opts,
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }

  function renderBars(el, items, labelFn) {
    if (!el) return;
    if (!items || !items.length) { el.innerHTML = '<p class="dl-sub">No data yet.</p>'; return; }
    const max = Math.max(1, ...items.map(i => Number(i.count || 0)));
    el.innerHTML = items.map(i => {
      const pct = Math.round((Number(i.count || 0) / max) * 100);
      return `<div class="bar-row"><div class="top"><span>${esc(labelFn(i))}</span><b>${i.count}</b></div><div class="progress-track"><div class="progress-fill" style="width:${pct}%"></div></div></div>`;
    }).join("");
  }

  async function loadAll() {
    const ov = await api("/api/admin/overview");
    $("#kpi-total").textContent = ov.overview.total_installed;
    $("#kpi-24h").textContent = ov.overview.active_24h;
    $("#kpi-7d").textContent = ov.overview.active_7d;
    $("#kpi-sse").textContent = ov.overview.online_sse;
    $("#live-sse-pill").textContent = `${ov.overview.online_sse} online SSE`;

    renderBars($("#bars-platform"), ov.overview.by_platform, i => `${i.platform} (${i.active_24h || 0} active 24h)`);
    renderBars($("#bars-role"), ov.overview.by_role, i => i.app_role);
    renderBars($("#bars-version"), ov.overview.by_version, i => `v${i.app_version || "?"} (code ${i.version_code})`);

    const pol = ov.config.update_policy || {};
    const ch = ov.config.channel_overrides || {};
    $("#c-force").checked = Boolean(pol.force_modal);
    $("#c-minvc").value = pol.min_version_code || 0;
    $("#c-recver").value = pol.recommended_version || "";
    $("#c-mtitle").value = pol.modal_title || "";
    $("#c-mbody").value = pol.modal_message || "";
    $("#c-announce").value = ch.announcement || "";
    $("#c-blocked").value = (ch.blocked_hosts || []).join("\n");

    const nt = $("#notif-tbody");
    nt.innerHTML = (ov.recent_notifications || []).map(n => `
      <tr>
        <td>#${n.id}</td>
        <td><b>${esc(n.title)}</b><br><small class="dl-sub">${esc(n.kind)}</small></td>
        <td>${esc(n.target_platform || "All")} / ${esc(n.target_role || "All")}</td>
        <td>${n.delivered_count || 0} / ${n.opened_count || 0}</td>
        <td><button class="btn danger small" data-del-notif="${n.id}" type="button">Delete</button></td>
      </tr>
    `).join("") || '<tr><td colspan="5" class="dl-sub">No notifications sent yet.</td></tr>';
    nt.querySelectorAll("[data-del-notif]").forEach(b => b.addEventListener("click", async () => {
      await api(`/api/admin/notifications/${b.dataset.delNotif}`, { method: "DELETE" });
      await loadAll();
    }));

    await loadDevices();
    const audit = await api("/api/admin/audit");
    $("#audit-tbody").innerHTML = (audit.logs || []).slice(0, 25).map(a =>
      `<tr><td>${new Date(a.created_at).toLocaleString()}</td><td>${esc(a.actor)}</td><td>${esc(a.action)}</td><td class="dl-sub">${esc(a.details)}</td></tr>`
    ).join("");
  }

  async function loadDevices() {
    const q = encodeURIComponent($("#dev-search").value.trim());
    const p = encodeURIComponent($("#dev-platform").value);
    const data = await api(`/api/admin/devices?q=${q}&platform=${p}`);
    const tb = $("#dev-tbody");
    tb.innerHTML = (data.devices || []).map(d => `
      <tr>
        <td><code>${esc(d.device_id)}</code></td>
        <td>${esc(d.platform)}</td>
        <td>${esc(d.app_role)}</td>
        <td>${esc(d.device_model || "—")}</td>
        <td>${esc(d.app_version)} (${d.version_code})</td>
        <td>${esc(d.country || "—")}</td>
        <td>${new Date(d.last_seen_at).toLocaleString()}</td>
        <td><button class="btn danger small" data-del-dev="${esc(d.device_id)}" type="button">Remove</button></td>
      </tr>
    `).join("") || '<tr><td colspan="8" class="dl-sub">No matching devices.</td></tr>';
    tb.querySelectorAll("[data-del-dev]").forEach(b => b.addEventListener("click", async () => {
      await api(`/api/admin/devices/${encodeURIComponent(b.dataset.delDev)}`, { method: "DELETE" });
      await loadAll();
    }));
  }

  function setSignedIn(me) {
    $("#login-card").hidden = true;
    $("#dash").hidden = false;
    $("#admin-logout").hidden = false;
    $("#admin-refresh").hidden = false;
    $("#admin-user-label").textContent = `Signed in as ${me.username} · Valid until ${new Date(me.expires_at).toLocaleDateString()}`;
    loadAll();
  }

  $("#login-form").addEventListener("submit", async e => {
    e.preventDefault();
    $("#login-err").textContent = "";
    try {
      const me = await api("/api/admin/login", {
        method: "POST",
        body: JSON.stringify({ username: $("#adm-user").value, password: $("#adm-pass").value }),
      });
      setSignedIn(me);
    } catch (err) {
      $("#login-err").textContent = err.message;
    }
  });

  $("#admin-logout").addEventListener("click", async () => {
    await api("/api/admin/logout", { method: "POST" });
    location.reload();
  });
  $("#admin-refresh").addEventListener("click", () => loadAll());
  $("#dev-search").addEventListener("input", () => loadDevices());
  $("#dev-platform").addEventListener("change", () => loadDevices());

  $("#push-form").addEventListener("submit", async e => {
    e.preventDefault();
    const st = $("#push-status");
    st.textContent = "Sending…";
    try {
      const res = await api("/api/admin/notifications", {
        method: "POST",
        body: JSON.stringify({
          title: $("#p-title").value,
          body: $("#p-body").value,
          kind: $("#p-kind").value,
          target_platform: $("#p-platform").value || null,
          target_role: $("#p-role").value || null,
          action_url: $("#p-url").value,
          force_modal: $("#p-modal").checked,
        }),
      });
      st.textContent = `Sent (#${res.notification.id}, ${res.live_sse_delivered} live SSE delivered).`;
      $("#push-form").reset();
      await loadAll();
    } catch (err) {
      st.textContent = "Error: " + err.message;
    }
  });

  $("#cfg-form").addEventListener("submit", async e => {
    e.preventDefault();
    const st = $("#cfg-status");
    st.textContent = "Saving…";
    try {
      await api("/api/admin/config", {
        method: "PUT",
        body: JSON.stringify({
          update_policy: {
            force_modal: $("#c-force").checked,
            min_version_code: Number($("#c-minvc").value || 0),
            recommended_version: $("#c-recver").value,
            modal_title: $("#c-mtitle").value,
            modal_message: $("#c-mbody").value,
          },
          channel_overrides: {
            announcement: $("#c-announce").value,
            blocked_hosts: $("#c-blocked").value.split(/\r?\n/).map(x => x.trim()).filter(Boolean),
          },
        }),
      });
      st.textContent = "Saved & pushed to online clients.";
      await loadAll();
    } catch (err) {
      st.textContent = "Error: " + err.message;
    }
  });

  api("/api/admin/me").then(setSignedIn).catch(() => {});
})();
