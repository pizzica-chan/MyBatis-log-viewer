const statsEls = {
  summary: document.getElementById("stats-summary"),
  byType: document.getElementById("stats-by-type"),
  mappers: document.getElementById("stats-mappers"),
  slow: document.getElementById("stats-slow"),
};

let statsLoaded = false;

async function refreshStats() {
  statsEls.summary.innerHTML = '<p class="stats-loading">統計を読み込み中...</p>';
  try {
    const [summaryRes, mappersRes, slowRes] = await Promise.all([
      fetch("/api/stats/summary"),
      fetch("/api/stats/mappers?limit=20"),
      fetch("/api/stats/slow?limit=20"),
    ]);
    const summary = await summaryRes.json();
    const mappers = await mappersRes.json();
    const slow = await slowRes.json();

    if (summary.loading || mappers.loading || slow.loading) {
      statsEls.summary.innerHTML = '<p class="stats-loading">インデックス構築中...</p>';
      return;
    }
    if (summary.error) {
      statsEls.summary.innerHTML = `<p class="stats-loading">${summary.error}</p>`;
      return;
    }

    renderSummary(summary);
    renderByType(summary.by_sql_type || []);
    renderMappers(mappers.items || []);
    renderSlow(slow.items || []);
    statsLoaded = true;
  } catch (e) {
    statsEls.summary.innerHTML = `<p class="stats-loading">統計の取得に失敗しました</p>`;
  }
}

function renderSummary(s) {
  statsEls.summary.innerHTML = "";
  const cards = [
    { label: "総 SQL 数", value: (s.total || 0).toLocaleString() },
    { label: "平均 elapsed", value: s.avg_elapsed != null ? Math.round(s.avg_elapsed) + " ms" : "-" },
    { label: "最大 elapsed", value: s.max_elapsed != null ? s.max_elapsed + " ms" : "-" },
    { label: "期間", value: s.first && s.last ? formatShort(s.first) + " 〜" : "-" },
  ];
  for (const c of cards) {
    const div = document.createElement("div");
    div.className = "stat-card";
    div.innerHTML = `<p class="label">${c.label}</p><p class="value">${c.value}</p>`;
    statsEls.summary.appendChild(div);
  }
}

function formatShort(iso) {
  const p = iso.match(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/);
  return p ? `${p[1]} ${p[2]}` : iso;
}

function renderByType(items) {
  statsEls.byType.innerHTML = "";
  if (items.length === 0) {
    statsEls.byType.textContent = "データがありません";
    return;
  }
  for (const item of items) {
    const row = document.createElement("div");
    row.className = "stats-bar-row";
    row.innerHTML =
      `<span class="stats-bar-label">${item.sql_type}</span>` +
      `<div class="stats-bar-track"><div class="stats-bar-fill" style="width:${item.pct || 0}%"></div></div>` +
      `<span class="stats-bar-count">${item.count.toLocaleString()}</span>`;
    row.addEventListener("click", () => {
      window.applySearchFilter({ sqlType: item.sql_type });
    });
    statsEls.byType.appendChild(row);
  }
}

function renderMappers(items) {
  statsEls.mappers.innerHTML = "";
  for (const m of items) {
    const tr = document.createElement("tr");
    tr.innerHTML =
      `<td class="mapper" title="${escapeAttr(m.mapper)}">${escapeHtml(shortMapper(m.mapper))}</td>` +
      `<td>${m.count.toLocaleString()}</td>` +
      `<td>${m.avg_elapsed != null ? Math.round(m.avg_elapsed) + " ms" : "-"}</td>` +
      `<td>${m.max_elapsed != null ? m.max_elapsed + " ms" : "-"}</td>`;
    tr.addEventListener("click", () => {
      window.applySearchFilter({ mapper: escapeRegex(m.mapper) });
    });
    statsEls.mappers.appendChild(tr);
  }
}

function renderSlow(items) {
  statsEls.slow.innerHTML = "";
  for (const s of items) {
    const tr = document.createElement("tr");
    tr.innerHTML =
      `<td>${formatShort(s.timestamp)}</td>` +
      `<td>${s.elapsed_ms} ms</td>` +
      `<td class="mapper" title="${escapeAttr(s.mapper)}">${escapeHtml(shortMapper(s.mapper))}</td>` +
      `<td>${s.sql_type}</td>` +
      `<td class="sql" title="${escapeAttr(s.sql_preview)}">${escapeHtml(s.sql_preview)}</td>`;
    tr.addEventListener("click", () => {
      window.applySearchFilter({ mapper: escapeRegex(s.mapper), minElapsed: String(s.elapsed_ms) });
    });
    statsEls.slow.appendChild(tr);
  }
}

function shortMapper(m) {
  const parts = m.split(".");
  return parts.length > 2 ? parts.slice(-2).join(".") : m;
}

function escapeHtml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function escapeAttr(s) {
  return String(s).replace(/"/g, "&quot;");
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

window.refreshStats = refreshStats;
