const statsEls = {
  summary: document.getElementById("stats-summary"),
  byType: document.getElementById("stats-by-type"),
  mapperSearch: document.getElementById("stats-mapper-search"),
  mapperSearchBtn: document.getElementById("stats-mapper-search-btn"),
  mapperSearchHint: document.getElementById("stats-mapper-search-hint"),
  mappers: document.getElementById("stats-mappers"),
  slowHint: document.getElementById("stats-slow-hint"),
  slow: document.getElementById("stats-slow"),
};

const MAPPER_SEARCH_MIN_LEN = 2;
const SLOW_HINT_DEFAULT = "Mapper を検索すると表示されます";

let statsLoaded = false;

async function refreshStats() {
  statsEls.summary.innerHTML = '<p class="stats-loading">統計を読み込み中...</p>';
  try {
    const summaryRes = await fetch("/api/stats/summary");
    const summary = await summaryRes.json();

    if (summary.loading) {
      statsEls.summary.innerHTML = '<p class="stats-loading">インデックス構築中...</p>';
      return;
    }
    if (summary.error) {
      statsEls.summary.innerHTML = `<p class="stats-loading">${summary.error}</p>`;
      return;
    }

    renderSummary(summary);
    renderByType(summary.by_sql_type || []);
    clearMapperSearchResults();
    statsLoaded = true;
  } catch (e) {
    statsEls.summary.innerHTML = `<p class="stats-loading">統計の取得に失敗しました</p>`;
  }
}

function clearMapperSearchResults() {
  statsEls.mappers.innerHTML = "";
  statsEls.slow.innerHTML = "";
  statsEls.slowHint.textContent = SLOW_HINT_DEFAULT;
}

async function searchMapperStats() {
  const q = statsEls.mapperSearch.value.trim();
  if (q.length < MAPPER_SEARCH_MIN_LEN) {
    statsEls.mapperSearchHint.textContent = `${MAPPER_SEARCH_MIN_LEN}文字以上入力してください`;
    clearMapperSearchResults();
    return;
  }
  statsEls.mapperSearchHint.textContent = "検索中...";
  statsEls.slowHint.textContent = "検索中...";
  statsEls.mappers.innerHTML = "";
  statsEls.slow.innerHTML = "";
  try {
    const res = await fetch("/api/stats/mappers?q=" + encodeURIComponent(q));
    const data = await res.json();
    if (data.loading) {
      statsEls.mapperSearchHint.textContent = "インデックス構築中...";
      statsEls.slowHint.textContent = SLOW_HINT_DEFAULT;
      return;
    }
    if (!res.ok || data.error) {
      statsEls.mapperSearchHint.textContent = data.error || "検索に失敗しました";
      statsEls.slowHint.textContent = SLOW_HINT_DEFAULT;
      return;
    }
    const items = data.items || [];
    const slowItems = data.slow_sql || [];
    if (items.length === 0) {
      statsEls.mapperSearchHint.textContent = `「${q}」に一致する Mapper はありません`;
      statsEls.slowHint.textContent = `「${q}」に一致する遅い SQL はありません`;
      return;
    }
    statsEls.mapperSearchHint.textContent = `${items.length.toLocaleString()} 件の Mapper が見つかりました`;
    renderMappers(items);
    if (slowItems.length === 0) {
      statsEls.slowHint.textContent = `「${q}」に一致する elapsed 付き SQL はありません`;
    } else {
      statsEls.slowHint.textContent = `${slowItems.length.toLocaleString()} 件の遅い SQL（検索条件内）`;
    }
    renderSlow(slowItems);
  } catch (e) {
    statsEls.mapperSearchHint.textContent = "検索に失敗しました";
    statsEls.slowHint.textContent = SLOW_HINT_DEFAULT;
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

function confirmApplySearchFilter(filter, descriptionLines) {
  const message = [
    "SQL 検索タブに移動し、次の条件を設定します。",
    "",
    ...descriptionLines,
    "",
    "よろしいですか？",
  ].join("\n");
  if (!window.confirm(message)) {
    return;
  }
  window.applySearchFilter(filter);
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
      `<span class="stats-bar-label">${escapeHtml(item.sql_type)}</span>` +
      `<div class="stats-bar-track"><div class="stats-bar-fill" style="width:${item.bar_ratio || 0}%"></div></div>` +
      `<span class="stats-bar-count">${item.count.toLocaleString()}</span>`;
    row.addEventListener("click", () => {
      confirmApplySearchFilter(
        { sqlType: item.sql_type },
        [`SQL 種別: ${item.sql_type}`],
      );
    });
    statsEls.byType.appendChild(row);
  }
}

function renderMappers(items) {
  statsEls.mappers.innerHTML = "";
  for (const m of items) {
    const tr = document.createElement("tr");
    tr.innerHTML =
      `<td class="mapper" title="${escapeHtml(m.mapper)}">${escapeHtml(shortMapper(m.mapper))}</td>` +
      `<td>${m.count.toLocaleString()}</td>` +
      `<td>${m.avg_elapsed != null ? Math.round(m.avg_elapsed) + " ms" : "-"}</td>` +
      `<td>${m.max_elapsed != null ? m.max_elapsed + " ms" : "-"}</td>`;
    tr.addEventListener("click", () => {
      confirmApplySearchFilter(
        { mapper: escapeRegex(m.mapper) },
        [`Mapper: ${m.mapper}`],
      );
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
      `<td class="mapper" title="${escapeHtml(s.mapper)}">${escapeHtml(shortMapper(s.mapper))}</td>` +
      `<td>${s.sql_type}</td>` +
      `<td class="sql" title="${escapeHtml(s.sql_preview)}">${escapeHtml(s.sql_preview)}</td>`;
    tr.addEventListener("click", () => {
      confirmApplySearchFilter(
        { mapper: escapeRegex(s.mapper), minElapsed: String(s.elapsed_ms) },
        [`Mapper: ${s.mapper}`, `最小 elapsed: ${s.elapsed_ms} ms`],
      );
    });
    statsEls.slow.appendChild(tr);
  }
}

statsEls.mapperSearchBtn.addEventListener("click", searchMapperStats);
statsEls.mapperSearch.addEventListener("keydown", (e) => {
  if (e.key === "Enter") searchMapperStats();
});

window.refreshStats = refreshStats;
