/* ============================================================
   ForestEar dashboard client — design language after
   era-residence.com (cream / ink / velvet-plum, didone +
   extended grotesk + script, hairline frames, arch sections).
   WebSocket /live + REST, Leaflet map, live spectrogram,
   model score telemetry, ranger feed, biodiversity, analytics.
   ============================================================ */
"use strict";

/* ---------------------------------------------------------- helpers */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const API = {
  nodes: "/nodes",
  events: "/events?limit=200",
  biodiv: "/biodiversity/summary?hours=48",
  analytics: "/analytics/summary",
  suppressed: "/suppressed?limit=1000",
  estimates: "/estimates?limit=50",
  health: "/health",
};

/* hairline stroke icons — no emoji, after the reference icon set */
const ICO = {
  chainsaw: `<svg viewBox="0 0 24 24"><path d="M3 11h13"/><path d="M5.5 11l-1 3M8.5 11l-1 3M11.5 11l-1 3M14.5 11l-1 3"/><circle cx="18.7" cy="11" r="2.4"/><path d="M4 8h8"/></svg>`,
  gunshot: `<svg viewBox="0 0 24 24"><path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M18.4 5.6l-2.8 2.8M8.4 15.6l-2.8 2.8"/><circle cx="12" cy="12" r="1.4"/></svg>`,
  vehicle: `<svg viewBox="0 0 24 24"><rect x="2.5" y="9.5" width="12" height="5" rx="1.4"/><path d="M14.5 11.5h4.2l1.8 3h-6z"/><circle cx="6.6" cy="16.6" r="1.6"/><circle cx="17.4" cy="16.6" r="1.6"/></svg>`,
  human: `<svg viewBox="0 0 24 24"><circle cx="12" cy="6" r="2.4"/><path d="M12 8.8v5.4M8.4 20c0-3.4 1.6-5.8 3.6-5.8s3.6 2.4 3.6 5.8M7.5 11.4h9"/></svg>`,
  anomaly: `<svg viewBox="0 0 24 24"><path d="M12 4v16M5.1 8l13.8 8M18.9 8L5.1 16"/></svg>`,
};

const CLASS_META = {
  chainsaw: { ico: ICO.chainsaw, label: "Chainsaw" },
  gunshot: { ico: ICO.gunshot, label: "Gunshot" },
  vehicle: { ico: ICO.vehicle, label: "Vehicle" },
  human: { ico: ICO.human, label: "Human presence" },
  anomaly: { ico: ICO.anomaly, label: "Anomaly" },
};

const fmtT = (ts) => {
  try { return new Date(ts).toLocaleTimeString([], { hour12: false }); }
  catch { return "--:--:--"; }
};

async function jget(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url} -> ${r.status}`);
  return r.json();
}

/* ---------------------------------------------------------- state */
const state = {
  nodes: {},        // node_id -> {marker, lat, lon, health, status, spect: []}
  order: [],        // node ids in arrival order
  selected: null,
  events: [],       // full event objects (newest first)
  suppressed: [],
  feedFilter: { cls: "", sev: "" },
  charts: {},
  bioTotal: 0,
};

/* ---------------------------------------------------------- preloader */
const BOOT_AT = performance.now();
function bootDone() {
  const p = $("#preloader");
  if (!p || p.classList.contains("done")) return;
  // let the progress hairline finish before the curtain lifts
  const wait = Math.max(0, 1750 - (performance.now() - BOOT_AT));
  setTimeout(() => p.classList.add("done"), wait);
}
window.addEventListener("load", () => setTimeout(bootDone, 400));
setTimeout(bootDone, 5000); // safety net

/* ---------------------------------------------------------- clock */
setInterval(() => {
  $("#utc-clock").textContent = new Date().toISOString().slice(11, 19) + " UTC";
}, 1000);

/* ---------------------------------------------------------- reveal on scroll */
const revealIO = new IntersectionObserver((entries) => {
  for (const en of entries) {
    if (en.isIntersecting) { en.target.classList.add("is-in"); revealIO.unobserve(en.target); }
  }
}, { threshold: 0.12 });
$$(".reveal").forEach((el) => revealIO.observe(el));

/* ---------------------------------------------------------- tabs */
function resizeCharts() {
  for (const c of Object.values(state.charts)) { try { c && c.resize(); } catch {} }
}
$$(".tab").forEach((t) => {
  t.addEventListener("click", () => {
    $$(".tab").forEach((x) => x.classList.remove("is-active"));
    $$(".tab-panel").forEach((x) => x.classList.remove("is-active"));
    t.classList.add("is-active");
    $("#panel-" + t.dataset.tab).classList.add("is-active");
    requestAnimationFrame(() => {
      resizeCharts();
      // Leaflet caches the container size; re-measure after a tab switch so the
      // map is not left blank when returning to the live panel.
      if (t.dataset.tab === "live") { try { map.invalidateSize(); } catch {} }
    });
    updateHeader();
  });
});

/* ---------------------------------------------------------- header theme
   Transparent over the velvet masthead (cream text), solid cream on scroll —
   after the reference header behaviour. */
function updateHeader() {
  const h = $(".header");
  const mast = $(".masthead");
  const overVelvet = mast && $("#panel-live").classList.contains("is-active")
    && window.scrollY < mast.offsetTop + mast.offsetHeight - h.offsetHeight - 8;
  h.classList.toggle("theme_on-color", overVelvet);
  h.classList.toggle("is-solid", !overVelvet);
}
window.addEventListener("scroll", updateHeader, { passive: true });
updateHeader();

/* ---------------------------------------------------------- toasts */
function toast(title, sub, sev) {
  const t = document.createElement("div");
  t.className = "toast " + (sev || "");
  t.innerHTML = `<span class="dg dg-tl" aria-hidden="true"></span><span class="dg dg-tr" aria-hidden="true"></span>
                 <span class="dg dg-bl" aria-hidden="true"></span><span class="dg dg-br" aria-hidden="true"></span>
                 <div class="t-title"></div><div class="t-sub"></div>`;
  t.querySelector(".t-title").textContent = title;
  t.querySelector(".t-sub").textContent = sub || "";
  $("#toasts").appendChild(t);
  setTimeout(() => t.classList.add("fadeout"), 5200);
  setTimeout(() => t.remove(), 5900);
  const keep = 5;
  const all = $$("#toasts .toast");
  if (all.length > keep) all.slice(0, all.length - keep).forEach((x) => x.remove());
}

/* ---------------------------------------------------------- map */
const map = L.map("map", {
  zoomControl: false, attributionControl: true,
  center: [12.9716, 77.5946], zoom: 15,
});
L.control.zoom({ position: "bottomleft" }).addTo(map);
L.tileLayer("https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png", {
  attribution: "&copy; OpenStreetMap &copy; CARTO", maxZoom: 19,
}).addTo(map);
let mapFit = false;

function nodeMarkerId(nid) { return `nm-${nid}`; }

function createNodeMarker(rec, nid) {
  const icon = L.divIcon({
    className: "",
    html: `<div class="pin" id="${nodeMarkerId(nid)}">
             <div class="pin_bg"></div>
             <div class="pin_pulse"></div><div class="pin_pulse p2"></div>
             <div class="pin_dot"></div>
             <div class="pin_label l2">${nid}</div>
           </div>`,
    iconSize: [34, 34], iconAnchor: [17, 17],
  });
  rec.marker = L.marker([rec.lat, rec.lon], { icon }).addTo(map);
  rec.marker.on("click", () => selectNode(nid));
}

function upsertNode(n) {
  if (!n || !n.node_id) return null;
  let rec = state.nodes[n.node_id];
  const hasCoords = Number.isFinite(n.lat) && Number.isFinite(n.lon);
  if (!rec) {
    rec = state.nodes[n.node_id] = {
      lat: hasCoords ? n.lat : null, lon: hasCoords ? n.lon : null,
      status: "unknown", health: {}, spect: [],
    };
    state.order.push(n.node_id);
    if (hasCoords) createNodeMarker(rec, n.node_id);
    rebuildNodeSelect();   // populate dropdown + auto-select first node
    if (hasCoords && !mapFit) { map.fitBounds(allNodeBounds().pad(0.25)); mapFit = true; }
  } else if (hasCoords && (n.lat !== rec.lat || n.lon !== rec.lon)) {
    rec.lat = n.lat; rec.lon = n.lon;
    if (rec.marker) rec.marker.setLatLng([n.lat, n.lon]);
    else createNodeMarker(rec, n.node_id);
    if (!mapFit) { map.fitBounds(allNodeBounds().pad(0.25)); mapFit = true; }
  }
  if (n.status) rec.status = n.status;
  if (n.health) rec.health = n.health;
  const el = document.getElementById(nodeMarkerId(n.node_id));
  if (el) {
    el.classList.remove("degraded", "offline");
    if (rec.status === "degraded") el.classList.add("degraded");
    if (rec.status === "offline") el.classList.add("offline");
    el.classList.toggle("selected", state.selected === n.node_id);
  }
  return rec;
}

function allNodeBounds() {
  const pts = state.order
    .map((id) => state.nodes[id])
    .filter((n) => Number.isFinite(n.lat) && Number.isFinite(n.lon))
    .map((n) => [n.lat, n.lon]);
  return L.latLngBounds(pts.length ? pts : [[12.9716, 77.5946]]);
}

function alertPulse(lat, lon, sev) {
  const cls = sev === "CRITICAL" ? "" : (sev === "WARNING" ? "warn" : "info");
  const icon = L.divIcon({
    className: "",
    html: `<div style="position:relative;width:34px;height:34px">
             <div class="alert-pulse ${cls}"></div>
             <div class="alert-pulse core ${cls}"></div>
           </div>`,
    iconSize: [34, 34], iconAnchor: [17, 17],
  });
  const m = L.marker([lat, lon], { icon, interactive: false }).addTo(map);
  setTimeout(() => map.removeLayer(m), 2500);
}

function sourceEstimateCircle(est) {
  const c = L.circle([est.lat, est.lon], {
    radius: Math.max(est.radius_m || 60, 30),
    className: "estimate-path",
    interactive: false,
  }).addTo(map);
  map.fitBounds(c.getBounds().pad(0.2));
  toast("Source estimate", `${est.method} · ±${Math.round(est.radius_m || 0)} m`, "WARNING");
  setTimeout(() => map.removeLayer(c), 20000);
}

/* ---------------------------------------------------------- node select */
function selectNode(nid) {
  state.selected = nid;
  $("#scores-node").textContent = nid;
  $("#node-select").value = nid;
  $$(".health-cell").forEach((c) => c.classList.toggle("selected", c.dataset.node === nid));
  state.order.forEach((id) => {
    const el = document.getElementById(nodeMarkerId(id));
    if (el) el.classList.toggle("selected", id === nid);
  });
  drawSpectrogram();
}

function rebuildNodeSelect() {
  const sel = $("#node-select");
  sel.innerHTML = "";
  state.order.forEach((id) => {
    const o = document.createElement("option");
    o.value = id; o.textContent = id;
    sel.appendChild(o);
  });
  if (!state.selected && state.order.length) selectNode(state.order[0]);
}
$("#node-select").addEventListener("change", (e) => selectNode(e.target.value));

/* ---------------------------------------------------------- scores UI */
const SCORE_ROWS = [
  ["m2", "#b-m2", "#v-m2", 0, 1],
  ["m3", "#b-m3", "#v-m3", 0, 1],
  ["m4", "#b-m4", "#v-m4", 0, 1],
  ["m5_rain", "#b-m5r", "#v-m5r", 0, 1],
  ["m5_wind", "#b-m5w", "#v-m5w", 0, 1],
  ["m5_thunder", "#b-m5t", "#v-m5t", 0, 1],
  ["m6_z", "#b-m6", "#v-m6", 0, 6, 1],
  ["m7", "#b-m7", "#v-m7", 0, 1],
];

function renderScores(sc) {
  for (const [key, barSel, valSel, , max, dec] of SCORE_ROWS) {
    const v = Math.max(0, Number(sc[key] ?? 0));
    $(barSel).style.width = `${Math.min(100, (v / (max || 1)) * 100)}%`;
    $(valSel).textContent = v.toFixed(dec ?? 2);
  }
  const m9 = sc.m9 || {};
  const threat = Math.max(m9.chainsaw || 0, m9.gunshot || 0, m9.vehicle || 0, m9.human || 0);
  $("#b-m9").style.width = `${Math.min(100, threat * 100)}%`;
  $("#v-m9").textContent = threat.toFixed(2);
}

function showVeto(reason, cls) {
  const b = $("#veto-banner");
  $("#veto-text").textContent = `${reason} → ${cls || "alert"} suppressed`;
  b.hidden = false;
  clearTimeout(state.vetoTimer);
  state.vetoTimer = setTimeout(() => { b.hidden = true; }, 6000);
}

/* ---------------------------------------------------------- spectrogram */
const SPECT_MAX_COLS = 280;

function pushSpect(nid, col) {
  const rec = state.nodes[nid];
  if (!rec) return;
  rec.spect = rec.spect || [];
  rec.spect.push(col);
  if (rec.spect.length > SPECT_MAX_COLS) rec.spect.shift();
  if (nid === state.selected) drawSpectrogram();
}

function spectColor(v) {  // v in 0..1 — cream → powder-sky → ink (reference palette)
  const stops = [
    [181, 206, 219], [143, 179, 201], [97, 129, 155], [52, 74, 98], [23, 35, 59],
  ];
  const x = Math.max(0, Math.min(1, v)) * (stops.length - 1);
  const i = Math.floor(x), f = x - i;
  const a = stops[i], b = stops[Math.min(i + 1, stops.length - 1)];
  return `rgb(${Math.round(a[0] + (b[0] - a[0]) * f)},${Math.round(a[1] + (b[1] - a[1]) * f)},${Math.round(a[2] + (b[2] - a[2]) * f)})`;
}

function drawSpectrogram() {
  const cv = $("#spectrogram");
  const ctx = cv.getContext("2d");
  const W = cv.width, H = cv.height;
  ctx.fillStyle = "#f3f3ec";
  ctx.fillRect(0, 0, W, H);
  const rec = state.selected && state.nodes[state.selected];
  const cols = (rec && rec.spect) || [];
  if (!cols.length) {
    ctx.fillStyle = "rgba(23,35,59,.4)";
    ctx.font = "10px Archivo, sans-serif";
    ctx.fillText("AWAITING TELEMETRY…", 14, H / 2);
    return;
  }
  const cw = W / SPECT_MAX_COLS;       // column width px
  const bh = H / 64;                  // mel-bin height px
  const start = Math.max(0, cols.length - SPECT_MAX_COLS);
  for (let c = start; c < cols.length; c++) {
    const col = cols[c];
    const x = W - (cols.length - c) * cw;
    for (let b = 0; b < 64 && b < col.length; b++) {
      const v = col[b] / 255;
      if (v < 0.04) continue;
      ctx.fillStyle = spectColor(v);
      ctx.fillRect(x, H - (b + 1) * bh, Math.ceil(cw) + 0.5, Math.ceil(bh) + 0.5);
    }
  }
}

/* ---------------------------------------------------------- health grid */
function renderHealth() {
  const g = $("#health-grid");
  g.innerHTML = "";
  const okN = state.order.filter((id) => state.nodes[id].status === "healthy").length;
  $("#map-foot").textContent =
    `${state.order.length} nodes · ${okN} healthy · ${new Date().toISOString().slice(11, 19)} UTC`;
  for (const id of state.order) {
    const n = state.nodes[id];
    const h = n.health || {};
    const st = n.status || "unknown";
    const cls = st === "healthy" ? "ok" : (st === "degraded" ? "warn" : "bad");
    const cell = document.createElement("div");
    cell.className = "health-cell" + (id === state.selected ? " selected" : "");
    cell.dataset.node = id;
    cell.innerHTML = `
      <div class="hc-node"></div>
      <div class="hc-status l2 ${cls}"><span class="dot"></span><span class="st-t"></span></div>
      <div class="hc-metrics"></div>`;
    cell.querySelector(".hc-node").textContent = id;
    cell.querySelector(".st-t").textContent = st;
    const m = [];
    if (h.cpu_pct != null) m.push(`cpu ${Math.round(h.cpu_pct)}%`);
    if (h.ram_mb != null) m.push(`ram ${Math.round(h.ram_mb)}M`);
    if (h.battery != null) m.push(`bat ${Math.round(h.battery)}%`);
    if (h.temp_c != null) m.push(`${Math.round(h.temp_c)}°C`);
    cell.querySelector(".hc-metrics").textContent = m.join(" · ") || "—";
    cell.addEventListener("click", () => selectNode(id));
    g.appendChild(cell);
  }
  updateStats();
}

/* ---------------------------------------------------------- masthead stats + ticker */
function updateStats() {
  const total = state.order.length;
  const okN = state.order.filter((id) => state.nodes[id].status === "healthy").length;
  $("#stat-nodes").textContent = total ? `${okN} / ${total}` : "—";
  $("#stat-events").textContent = state.events.length || "—";
  $("#stat-species").textContent = state.bioTotal || "—";
  $("#stat-vetoes").textContent = state.suppressed.length || "—";
}

let tickerTimer = null;
function scheduleTicker() {
  if (tickerTimer) return;
  tickerTimer = setTimeout(() => { tickerTimer = null; updateTicker(); }, 1500);
}

function updateTicker() {
  const items = state.events.slice(0, 12);
  let html;
  if (!items.length) {
    html = `<span class="l1 marquee_list_item">the canopy is quiet — awaiting telemetry<span class="sep"></span></span>`;
  } else {
    html = items.map((e) => {
      const meta = CLASS_META[e.class] || { label: e.class };
      const c = e.severity === "CRITICAL" ? "#e23d3d"
        : e.severity === "WARNING" ? "#ffa800" : "#8fb3c9";
      return `<span class="l1 marquee_list_item"><span class="t-dot" style="background:${c}"></span>${meta.label} · ${e.node_id} · conf ${(e.confidence ?? 0).toFixed(2)} · ${fmtT(e.ts)}<span class="sep"></span></span>`;
    }).join("");
  }
  const tripled = html + html + html;   // keep the loop seamless when events are few
  $("#ticker-a").innerHTML = tripled;
  $("#ticker-b").innerHTML = tripled;
}

/* ---------------------------------------------------------- feed */
function feedItemHtml(e) {
  const meta = CLASS_META[e.class] || { ico: ICO.anomaly, label: e.class };
  const clip = e.clip_path
    ? `<button class="btn-mini fi-clip" data-clip="${e.clip_path.split("/").pop()}"><span>clip</span></button>` : "";
  const acked = e.acknowledged ? "acked" : "";
  return `
    <div class="fi-ico">${meta.ico}</div>
    <div class="fi-main">
      <div class="fi-cls"></div>
      <div class="fi-sub l2"></div>
      <div class="fi-detail"><div class="detail-grid"></div></div>
    </div>
    <span class="fi-conf l1 num"></span>
    <span class="fi-sev ${e.severity}">${e.severity}</span>
    ${clip}
    <button class="btn-mini" data-ack="${e.id}" title="acknowledge"><span>ack</span></button>`;
}

function fillFeedRow(row, e) {
  const meta = CLASS_META[e.class] || { ico: ICO.anomaly, label: e.class };
  row.querySelector(".fi-cls").textContent = meta.label;
  const sub = `${fmtT(e.ts)} · ${e.node_id} · ${e.persistence || ""} ${e.escalated ? "· escalated" : ""}`;
  row.querySelector(".fi-sub").textContent = sub;
  row.querySelector(".fi-conf").textContent = (e.confidence ?? 0).toFixed(2);
}

function renderDetail(row, e) {
  const grid = row.querySelector(".detail-grid");
  if (grid.dataset.done) return;
  const pms = e.per_model_scores || {};
  const cells = [];
  const m1 = pms.m1_selected || {};
  for (const [k, v] of Object.entries(m1)) {
    cells.push(`<span class="k">M1 ${k.slice(0, 22)}</span><span>${Number(v).toFixed(2)}</span>`);
  }
  const singles = { m2_gunshot: "M2 gunshot", m3_chainsaw: "M3 chainsaw", m4_vehicle: "M4 vehicle",
                    m6_anomaly_z: "M6 z", m7_human: "M7 human" };
  for (const [k, label] of Object.entries(singles)) {
    if (pms[k] != null) cells.push(`<span class="k">${label}</span><span>${pms[k]}</span>`);
  }
  const m5 = pms.m5_weather || {};
  for (const [k, v] of Object.entries(m5)) cells.push(`<span class="k">M5 ${k}</span><span>${v}</span>`);
  const m9 = pms.m9_fusion || {};
  for (const [k, v] of Object.entries(m9)) cells.push(`<span class="k">M9 ${k}</span><span>${v}</span>`);
  grid.innerHTML = cells.join("") || "<span class='k'>no per-model scores</span>";
  grid.dataset.done = "1";
}

function bindFeedRow(row, e) {
  row.querySelector(".fi-main").addEventListener("click", () => {
    row.classList.toggle("expanded");
    renderDetail(row, e);
  });
  const ackBtn = row.querySelector("[data-ack]");
  if (ackBtn) ackBtn.addEventListener("click", async (ev) => {
    ev.stopPropagation();
    try {
      await fetch(`/events/${e.id}/ack`, { method: "POST" });
      e.acknowledged = true;
      row.classList.add("acked");
      ackBtn.disabled = true;
    } catch { /* offline */ }
  });
  const clipBtn = row.querySelector(".fi-clip");
  if (clipBtn) clipBtn.addEventListener("click", (ev) => {
    ev.stopPropagation();
    const a = document.createElement("a");
    a.href = "/clips/" + encodeURIComponent(clipBtn.dataset.clip);
    a.download = clipBtn.dataset.clip;
    a.click();
  });
}

function renderFeed() {
  const feed = $("#feed");
  const { cls, sev } = state.feedFilter;
  const items = state.events.filter((e) =>
    (!cls || e.class === cls) && (!sev || e.severity === sev));
  feed.innerHTML = "";
  if (!items.length) {
    feed.innerHTML = `<div class="feed-empty"><span class="a2">the canopy is quiet</span><span class="l2">no events match the current filter</span></div>`;
    return;
  }
  for (const e of items) {
    const row = document.createElement("div");
    row.className = `feed-item ${e.severity} ${e.acknowledged ? "acked" : ""}`;
    row.dataset.id = e.id;
    row.innerHTML = feedItemHtml(e);
    fillFeedRow(row, e);
    bindFeedRow(row, e);
    feed.appendChild(row);
  }
}

function addEvent(e) {
  state.events.unshift(e);
  if (state.events.length > 300) state.events.pop();
  if (!state.feedFilter.cls && !state.feedFilter.sev) {
    const feed = $("#feed");
    const empty = feed.querySelector(".feed-empty");
    if (empty) empty.remove();
    const row = document.createElement("div");
    row.className = `feed-item ${e.severity}`;
    row.innerHTML = feedItemHtml(e);
    fillFeedRow(row, e);
    bindFeedRow(row, e);
    feed.prepend(row);
  }
  scheduleTicker();
  updateStats();
  if (e.severity === "CRITICAL" || e.severity === "WARNING") {
    const meta = CLASS_META[e.class] || { label: e.class };
    toast(`${meta.label} — ${e.severity}`,
          `${e.node_id} · conf ${(e.confidence ?? 0).toFixed(2)} · ${fmtT(e.ts)}`, e.severity);
  }
}

$("#filter-class").addEventListener("change", (e) => {
  state.feedFilter.cls = e.target.value; renderFeed();
});
$("#filter-sev").addEventListener("change", (e) => {
  state.feedFilter.sev = e.target.value; renderFeed();
});
$("#btn-export").addEventListener("click", () => {
  const a = document.createElement("a");
  a.href = "/export/events.csv";
  a.download = "forestear_events.csv";
  a.click();
});

/* ---------------------------------------------------------- charts */
const PAL = {
  ink: "#17233b", sky: "#8fb3c9", blush: "#e5a6b6", velvet: "#340c24",
  alert: "#ffa800", error: "#e23d3d", success: "#1ba64b",
  grid: "rgba(23,35,59,.08)", tick: "rgba(23,35,59,.55)", line: "rgba(23,35,59,.34)",
  cream: "#f3f3ec",
};
Chart.defaults.font.family = "'Archivo', sans-serif";
Chart.defaults.font.size = 10;
Chart.defaults.font.weight = "600";
Chart.defaults.color = PAL.tick;

function chartOpts(extra) {
  return Object.assign({
    responsive: true, maintainAspectRatio: false,
    animation: { duration: 800, easing: "easeInOutCubic" },
    plugins: {
      legend: { labels: { boxWidth: 8, boxHeight: 8, usePointStyle: true } },
      tooltip: {
        backgroundColor: PAL.ink, titleColor: PAL.cream, bodyColor: PAL.cream,
        cornerRadius: 0, padding: 10, displayColors: false,
      },
    },
    scales: {
      x: { grid: { color: PAL.grid, drawTicks: false }, border: { color: PAL.line } },
      y: { grid: { color: PAL.grid, drawTicks: false }, border: { display: false } },
    },
  }, extra || {});
}

function renderBioChart(summary) {
  const ctx = $("#bio-hour-chart");
  if (!ctx) return;
  const hours = [];
  for (let h = 0; h < 24; h++) hours.push(h);
  const counts = hours.map((h) => (summary.by_hour || {})[String(h)] || 0);
  if (state.charts.bio) state.charts.bio.destroy();
  state.charts.bio = new Chart(ctx, {
    type: "bar",
    data: {
      labels: hours.map((h) => `${String(h).padStart(2, "0")}`),
      datasets: [{ data: counts, backgroundColor: PAL.velvet, borderRadius: 0, barPercentage: 0.65 }],
    },
    options: chartOpts({
      plugins: { legend: { display: false },
        tooltip: { callbacks: { label: (c) => `${c.parsed.y} detections` } } },
      scales: { x: { grid: { display: false } }, y: { beginAtZero: true, ticks: { precision: 0 } } },
    }),
  });
}

function renderSpecies(summary) {
  const list = $("#species-list");
  const top = summary.top_species || [];
  state.bioTotal = summary.total || 0;
  $("#bio-total").textContent = `${summary.total || 0} detections · 48 h`;
  list.innerHTML = "";
  if (!top.length) {
    list.innerHTML = `<div class="feed-empty"><span class="a2">no bird calls yet</span><span class="l2">M8 BirdNET runs duty-cycled, 1 window in 10</span></div>`;
    return;
  }
  const maxC = top[0].count || 1;
  for (const sp of top) {
    const row = document.createElement("div");
    row.className = "species-row";
    row.innerHTML = `
      <div class="sp-name"></div>
      <span class="sp-count l1 num"></span>
      <div class="sp-bar"><div class="sp-bar-fill"></div></div>
      <span class="sp-conf"></span>`;
    row.querySelector(".sp-name").textContent = sp.species.replace(/_/g, " ");
    row.querySelector(".sp-count").textContent = sp.count;
    row.querySelector(".sp-bar-fill").style.width = `${(sp.count / maxC) * 100}%`;
    row.querySelector(".sp-conf").textContent = `conf ${sp.mean_conf?.toFixed(2) ?? "—"}`;
    list.appendChild(row);
  }
  updateStats();
}

function renderAnalytics(summary) {
  const cls = summary.class_counts || {};
  const labels = Object.keys(cls);
  if (state.charts.evClass) state.charts.evClass.destroy();
  state.charts.evClass = new Chart($("#ev-class-chart"), {
    type: "doughnut",
    data: {
      labels,
      datasets: [{
        data: labels.map((l) => cls[l]),
        backgroundColor: labels.map((l) =>
          ({ gunshot: PAL.error, chainsaw: PAL.alert, vehicle: PAL.sky,
             human: PAL.blush, anomaly: PAL.velvet }[l] || PAL.ink)),
        borderColor: PAL.cream, borderWidth: 3,
      }],
    },
    options: {
      responsive: true, maintainAspectRatio: false, cutout: "68%",
      animation: { duration: 800 },
      plugins: { legend: { position: "right", labels: { boxWidth: 8, usePointStyle: true } },
        tooltip: { backgroundColor: PAL.ink, titleColor: PAL.cream, bodyColor: PAL.cream, cornerRadius: 0, padding: 10 } },
    },
  });

  // raised vs suppressed per class
  const supByCls = {};
  for (const s of state.suppressed) supByCls[s.class] = (supByCls[s.class] || 0) + 1;
  const allCls = Array.from(new Set([...labels, ...Object.keys(supByCls)]));
  if (state.charts.evSev) state.charts.evSev.destroy();
  state.charts.evSev = new Chart($("#ev-sev-chart"), {
    type: "bar",
    data: {
      labels: allCls,
      datasets: [
        { label: "raised", data: allCls.map((c) => cls[c] || 0), backgroundColor: PAL.ink, borderRadius: 0 },
        { label: "suppressed", data: allCls.map((c) => supByCls[c] || 0), backgroundColor: PAL.sky, borderRadius: 0 },
      ],
    },
    options: chartOpts({
      scales: { x: { stacked: false, grid: { display: false } }, y: { beginAtZero: true, ticks: { precision: 0 } } },
    }),
  });
}

$("#btn-load-eval").addEventListener("click", async () => {
  const note = $("#ablation-note");
  try {
    const rep = await jget("/reports/ablation.json");
    const rows = rep.rows || [];
    if (!rows.length) throw new Error("empty");
    if (state.charts.ablation) state.charts.ablation.destroy();
    state.charts.ablation = new Chart($("#ablation-chart"), {
      type: "bar",
      data: {
        labels: rows.map((r) => r.name),
        datasets: [{
          label: "macro F1 (test)",
          data: rows.map((r) => r.f1 ?? r.macro_f1 ?? 0),
          backgroundColor: rows.map((r) =>
            /full|ensemble|m9/i.test(r.name) ? PAL.ink : PAL.sky),
          borderRadius: 0,
        }],
      },
      options: chartOpts({
        indexAxis: "y",
        scales: {
          x: { beginAtZero: true, max: 1, grid: { color: PAL.grid } },
          y: { grid: { display: false } },
        },
      }),
    });
    note.textContent = `${rows.length} ablation variants loaded from reports/ablation.json (measured on the held-out test fold).`;
  } catch {
    note.textContent = "reports/ablation.json not found — run `make eval` after training.";
  }
});

/* ---------------------------------------------------------- websocket */
function setConn(status, text) {
  const pill = $("#conn-pill");
  pill.classList.remove("live", "lost");
  if (status) pill.classList.add(status);
  $("#conn-text").textContent = text;
}

function connectWS() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/live`);
  let retry = 1000;

  ws.onopen = () => {
    setConn("live", "live");
    retry = 1000;
    bootDone();
    ws.send("hello"); // open the receive loop
  };
  ws.onclose = () => {
    setConn("lost", "reconnecting");
    setTimeout(connectWS, retry);
    retry = Math.min(retry * 1.8, 15000);
  };
  ws.onerror = () => { try { ws.close(); } catch {} };
  ws.onmessage = (m) => {
    let msg;
    try { msg = JSON.parse(m.data); } catch { return; }
    handleMsg(msg);
  };
}

function handleMsg(msg) {
  switch (msg.type) {
    case "event": {
      addEvent({
        id: msg.id, node_id: msg.node_id, ts: msg.ts, class: msg.class,
        severity: msg.severity, confidence: msg.confidence,
        persistence: msg.persistence, escalated: msg.escalated,
        rms_db: msg.rms_db, lat: msg.lat, lon: msg.lon,
        per_model_scores: msg.per_model_scores, clip_path: null,
        acknowledged: false,
      });
      if (msg.lat && msg.lon) alertPulse(msg.lat, msg.lon, msg.severity);
      break;
    }
    case "scores": {
      const rec = state.nodes[msg.node_id];
      if (rec) { rec.health = rec.health || {}; }
      if (msg.node_id === state.selected) renderScores(msg.scores || {});
      if (msg.spect && msg.spect.length) pushSpect(msg.node_id, msg.spect);
      break;
    }
    case "heartbeat": {
      upsertNode({
        node_id: msg.node_id,
        health: msg.health || {},
        status: (msg.health && msg.health.status) || "healthy",
      }, true);
      renderHealth();
      break;
    }
    case "suppressed": {
      state.suppressed.push(msg);
      showVeto(msg.suppressed_by || "weather veto", msg.class);
      toast("Suppressed", `${msg.class} conf ${(msg.confidence ?? 0).toFixed(2)} — veto: ${msg.suppressed_by}`, "INFO");
      break;
    }
    case "source_estimate": {
      if (msg.lat && msg.lon) sourceEstimateCircle(msg);
      break;
    }
    case "biodiversity": {
      // live increment — refresh summary lazily
      scheduleBiodivRefresh();
      break;
    }
  }
}

/* ---------------------------------------------------------- REST sync */
let biodivTimer = null;
function scheduleBiodivRefresh() {
  if (biodivTimer) return;
  biodivTimer = setTimeout(async () => {
    biodivTimer = null;
    try {
      const s = await jget(API.biodiv);
      renderSpecies(s);
      renderBioChart(s);
    } catch {}
  }, 3000);
}

async function refreshNodes() {
  try {
    const nodes = await jget(API.nodes);
    for (const n of nodes) upsertNode(n);
    renderHealth();
  } catch {}
}

async function initialLoad() {
  await refreshNodes();
  try {
    const evs = await jget(API.events);
    state.events = evs;   // newest first from backend
    renderFeed();
    updateTicker();
  } catch {}
  try {
    const sup = await jget(API.suppressed);
    state.suppressed = sup;
    updateStats();
  } catch {}
  try {
    const s = await jget(API.biodiv);
    renderSpecies(s);
    renderBioChart(s);
  } catch {}
  try {
    const a = await jget(API.analytics);
    renderAnalytics(a);
  } catch {}
  try {
    const ests = await jget(API.estimates);
    for (const e of ests.slice(0, 1)) sourceEstimateCircle(e);
  } catch {}
}

setInterval(refreshNodes, 20000);
setInterval(async () => {
  try { renderAnalytics(await jget(API.analytics)); } catch {}
}, 30000);

/* ---------------------------------------------------------- boot */
initialLoad();
connectWS();
