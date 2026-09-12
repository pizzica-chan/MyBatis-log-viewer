const DEFAULT_PAGE_LIMIT = 200;
const MAX_PAGE_LIMIT = 1000;
let offset = 0;
let lastTotal = 0;
let browsePath = "";
/** ディレクトリ参照の親パス。「上へ」で再問い合わせしないよう一覧取得時に控える。 */
let browseParent = null;
let metaRange = { first: null, last: null };
let exactQueryRange = null;
/** 詳細ダイアログ表示中の SQL 時刻（ISO）。 */
let detailTimestamp = null;
/** 詳細ダイアログ表示中のログファイル / スレッド。 */
let detailContext = null;

const els = {
  meta: document.getElementById("meta"),
  parseWarning: document.getElementById("parse-warning"),
  parseWarningText: document.getElementById("parse-warning-text"),
  parseWarningSamples: document.getElementById("parse-warning-samples"),
  logDir: document.getElementById("log-dir"),
  browse: document.getElementById("browse"),
  loadDir: document.getElementById("load-dir"),
  fileList: document.getElementById("file-list"),
  mapper: document.getElementById("mapper"),
  sqlType: document.getElementById("sql-type"),
  sql: document.getElementById("sql"),
  parameters: document.getElementById("parameters"),
  thread: document.getElementById("thread"),
  complete: document.getElementById("complete"),
  minElapsed: document.getElementById("min-elapsed"),
  maxElapsed: document.getElementById("max-elapsed"),
  minRowCount: document.getElementById("min-row-count"),
  maxRowCount: document.getElementById("max-row-count"),
  sinceDate: document.getElementById("since-date"),
  sinceTime: document.getElementById("since-time"),
  untilDate: document.getElementById("until-date"),
  untilTime: document.getElementById("until-time"),
  rangeFirst1h: document.getElementById("range-first-1h"),
  rangeFirst24h: document.getElementById("range-first-24h"),
  rangeLast1h: document.getElementById("range-last-1h"),
  rangeLast24h: document.getElementById("range-last-24h"),
  rangeClear: document.getElementById("range-clear"),
  rangeHint: document.getElementById("range-hint"),
  grep: document.getElementById("grep"),
  source: document.getElementById("source"),
  pageLimit: document.getElementById("page-limit"),
  search: document.getElementById("search"),
  reset: document.getElementById("reset"),
  rows: document.getElementById("rows"),
  resultCount: document.getElementById("result-count"),
  highlight: document.getElementById("highlight"),
  pageInfo: document.getElementById("page-info"),
  prev: document.getElementById("prev"),
  next: document.getElementById("next"),
  detail: document.getElementById("detail"),
  detailMeta: document.getElementById("detail-meta"),
  detailBody: document.getElementById("detail-body"),
  detailSearchAround1m: document.getElementById("detail-search-around-1m"),
  detailSearchAround5m: document.getElementById("detail-search-around-5m"),
  detailFilterSameContext: document.getElementById("detail-filter-same-context"),
  regexSamples: document.getElementById("regex-samples"),
  regexSamplesDialog: document.getElementById("regex-samples-dialog"),
  browseDialog: document.getElementById("browse-dialog"),
  browseCurrent: document.getElementById("browse-current"),
  browseList: document.getElementById("browse-list"),
  browseUp: document.getElementById("browse-up"),
  browseSelect: document.getElementById("browse-select"),
  loadingOverlay: document.getElementById("loading-overlay"),
  loadingText: document.getElementById("loading-text"),
  tabSearch: document.getElementById("tab-search"),
  tabStats: document.getElementById("tab-stats"),
  panelSearch: document.getElementById("panel-search"),
  panelStats: document.getElementById("panel-stats"),
};

let loadingDepth = 0;
let backgroundLoading = false;

function syncLoadingOverlay() {
  const visible = loadingDepth > 0 || backgroundLoading;
  els.loadingOverlay.hidden = !visible;
  document.body.classList.toggle("is-loading", visible);
}

function pushLoading(message) {
  loadingDepth += 1;
  if (message) els.loadingText.textContent = message;
  syncLoadingOverlay();
}

function popLoading() {
  loadingDepth = Math.max(0, loadingDepth - 1);
  syncLoadingOverlay();
}

function setBackgroundLoading(loading, message) {
  backgroundLoading = loading;
  if (message) els.loadingText.textContent = message;
  syncLoadingOverlay();
}

function pad2(n) {
  return String(n).padStart(2, "0");
}

function parseIsoParts(iso) {
  if (!iso) return null;
  const match = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  if (!match) return null;
  return {
    y: Number(match[1]), mo: Number(match[2]), d: Number(match[3]),
    h: Number(match[4]), mi: Number(match[5]), s: Number(match[6]),
  };
}

/*
 * ログの時刻はタイムゾーンを持たない壁時計時刻として扱う（サーバ側 TimeUtil も同じ）。
 * UTC 系 API で組み立て・分解することで、ブラウザのタイムゾーンに影響されないようにする。
 */
function isoToMs(iso) {
  const p = parseIsoParts(iso);
  if (!p) return null;
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s);
}

function msToFields(ms) {
  const d = new Date(ms);
  return {
    date: d.getUTCFullYear() + "-" + pad2(d.getUTCMonth() + 1) + "-" + pad2(d.getUTCDate()),
    time: pad2(d.getUTCHours()) + ":" + pad2(d.getUTCMinutes()),
  };
}

function formatRangeHint(iso) {
  const p = parseIsoParts(iso);
  if (!p) return iso;
  return `${p.y}-${pad2(p.mo)}-${pad2(p.d)} ${pad2(p.h)}:${pad2(p.mi)}`;
}

function setDatetimeFields(start, end) {
  els.sinceDate.value = start.date;
  els.sinceTime.value = start.time;
  els.untilDate.value = end.date;
  els.untilTime.value = end.time;
}

/** プログラムから期間を設定するとき、日付入力の min/max を広げる。 */
function widenDateInputBounds(startDate, endDate) {
  const minDate = startDate <= endDate ? startDate : endDate;
  const maxDate = startDate <= endDate ? endDate : startDate;
  if (!els.sinceDate.min || minDate < els.sinceDate.min) {
    els.sinceDate.min = minDate;
    els.untilDate.min = minDate;
  }
  if (!els.sinceDate.max || maxDate > els.sinceDate.max) {
    els.sinceDate.max = maxDate;
    els.untilDate.max = maxDate;
  }
}

/** クイック選択・詳細ダイアログ用。手入力フィールドより優先する正確な since/until。 */
function clearExactQueryRange() {
  exactQueryRange = null;
}

function setExactQueryRange(sinceMs, untilMs) {
  const start = msToFields(sinceMs);
  const end = msToFields(untilMs);
  widenDateInputBounds(start.date, end.date);
  exactQueryRange = {
    since: msToApiDatetime(sinceMs),
    until: msToApiDatetime(untilMs),
  };
  setDatetimeFields(start, end);
}

function clearDatetimeFields() {
  els.sinceDate.value = "";
  els.sinceTime.value = "";
  els.untilDate.value = "";
  els.untilTime.value = "";
  clearExactQueryRange();
}

/** time 入力が HH:mm:ss を返す実装もあるため HH:mm に正規化する。 */
function normalizeTimeValue(value, fallback) {
  return (value || fallback).slice(0, 5);
}

function getSinceParam() {
  if (!els.sinceDate.value) return null;
  const time = normalizeTimeValue(els.sinceTime.value, "00:00");
  return `${els.sinceDate.value} ${time}:00.000`;
}

function getUntilParam() {
  if (!els.untilDate.value) return null;
  const time = normalizeTimeValue(els.untilTime.value, "23:59");
  // 時刻入力は分単位のため、その分の末尾（59.999 秒）まで含める
  return `${els.untilDate.value} ${time}:59.999`;
}

/** ISO 文字列（API meta）を since/until クエリ形式へ。 */
function isoToApiDatetime(iso) {
  return iso.replace("T", " ");
}

function msToApiDatetime(ms) {
  const d = new Date(ms);
  const y = d.getUTCFullYear();
  const mo = pad2(d.getUTCMonth() + 1);
  const day = pad2(d.getUTCDate());
  const h = pad2(d.getUTCHours());
  const mi = pad2(d.getUTCMinutes());
  const sec = pad2(d.getUTCSeconds());
  const milli = String(d.getUTCMilliseconds()).padStart(3, "0");
  return `${y}-${mo}-${day} ${h}:${mi}:${sec}.${milli}`;
}

function updateRangeUi() {
  const ready = Boolean(metaRange.first && metaRange.last);
  els.rangeFirst1h.disabled = !ready;
  els.rangeFirst24h.disabled = !ready;
  els.rangeLast1h.disabled = !ready;
  els.rangeLast24h.disabled = !ready;
  if (ready) {
    els.sinceDate.min = msToFields(isoToMs(metaRange.first)).date;
    els.sinceDate.max = msToFields(isoToMs(metaRange.last)).date;
    els.untilDate.min = els.sinceDate.min;
    els.untilDate.max = els.sinceDate.max;
    els.rangeHint.textContent =
      "ログの範囲: " +
      formatRangeHint(metaRange.first) +
      " 〜 " +
      formatRangeHint(metaRange.last);
  } else {
    els.sinceDate.min = "";
    els.sinceDate.max = "";
    els.untilDate.min = "";
    els.untilDate.max = "";
    els.rangeHint.textContent = "ログ読み込み後に期間ボタンが使えます";
  }
}

function applyFirstHours(hours) {
  if (!metaRange.first || !metaRange.last) return;
  const startMs = isoToMs(metaRange.first);
  const endMs = isoToMs(metaRange.last);
  if (startMs == null || endMs == null) return;
  const untilMs = Math.min(endMs, startMs + hours * 3600000);
  exactQueryRange = {
    since: isoToApiDatetime(metaRange.first),
    until: msToApiDatetime(untilMs),
  };
  setDatetimeFields(msToFields(startMs), msToFields(untilMs));
  offset = 0;
  loadSql();
}

function applyLastHours(hours) {
  if (!metaRange.first || !metaRange.last) return;
  const endMs = isoToMs(metaRange.last);
  const startMs = isoToMs(metaRange.first);
  if (endMs == null || startMs == null) return;
  const sinceMs = Math.max(startMs, endMs - hours * 3600000);
  exactQueryRange = {
    since: msToApiDatetime(sinceMs),
    until: isoToApiDatetime(metaRange.last),
  };
  setDatetimeFields(msToFields(sinceMs), msToFields(endMs));
  offset = 0;
  loadSql();
}

function applyAroundMinutes(isoTimestamp, minutes) {
  const centerMs = isoToMs(isoTimestamp);
  if (centerMs == null) return false;
  const delta = minutes * 60 * 1000;
  setExactQueryRange(centerMs - delta, centerMs + delta);
  offset = 0;
  loadSql();
  return true;
}

function getPageLimit() {
  const raw = Number(els.pageLimit.value);
  if (!Number.isFinite(raw) || raw < 1) return DEFAULT_PAGE_LIMIT;
  return Math.min(Math.floor(raw), MAX_PAGE_LIMIT);
}

function buildQuery() {
  const params = new URLSearchParams();
  params.set("limit", String(getPageLimit()));
  params.set("offset", String(offset));
  for (const [key, el] of [
    ["mapper", els.mapper], ["sql_type", els.sqlType], ["sql", els.sql],
    ["parameters", els.parameters], ["thread", els.thread],
    ["grep", els.grep], ["source", els.source],
  ]) {
    if (el.value.trim()) params.set(key, el.value.trim());
  }
  if (els.minElapsed.value.trim()) params.set("min_elapsed", els.minElapsed.value.trim());
  if (els.maxElapsed.value.trim()) params.set("max_elapsed", els.maxElapsed.value.trim());
  if (els.minRowCount.value.trim()) params.set("min_row_count", els.minRowCount.value.trim());
  if (els.maxRowCount.value.trim()) params.set("max_row_count", els.maxRowCount.value.trim());
  if (els.complete.value) params.set("complete", els.complete.value);
  if (exactQueryRange) {
    params.set("since", exactQueryRange.since);
    params.set("until", exactQueryRange.until);
  } else {
    if (els.sinceDate.value) params.set("since", getSinceParam());
    if (els.untilDate.value) params.set("until", getUntilParam());
  }
  return params;
}

let loadPollTimer = null;
let lastPageItems = [];
/** 検索リクエストの連番。古い応答を捨てるために使う。 */
let searchSeq = 0;

function getHighlightNeedle() {
  const text = els.highlight.value.trim();
  return text ? text.toLowerCase() : "";
}

/** ハイライト判定。grep と同様に MyBatis ブロック全文（raw）を優先する（検索結果は変えない）。 */
function rowMatchesHighlight(item, needle) {
  if (!needle) return false;
  const haystack = item.raw || [
    item.timestamp,
    item.thread,
    item.mapper,
    item.sql_type,
    item.sql,
    item.bound_sql,
    item.bound_sql_preview,
    item.parameters,
    item.elapsed_ms != null ? item.elapsed_ms + " ms" : "",
    item.row_count,
    item.level,
    item.complete === false ? "未完了" : "完了",
    item.bind_warning,
    item.source,
    item.line_no,
  ].filter((v) => v != null && v !== "").join(" ");
  return haystack.toLowerCase().includes(needle);
}

function applyRowHighlights() {
  const needle = getHighlightNeedle();
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    rows[i].classList.toggle(
      "row-highlight",
      Boolean(item && rowMatchesHighlight(item, needle))
    );
  }
}

function clearLoadPoll() {
  if (loadPollTimer) {
    clearTimeout(loadPollTimer);
    loadPollTimer = null;
  }
}

function scheduleLoadPoll() {
  if (loadPollTimer) return;
  loadPollTimer = setTimeout(async () => {
    loadPollTimer = null;
    const data = await fetchMeta();
    updateMeta(data);
    if (data.loading) {
      scheduleLoadPoll();
      return;
    }
    offset = 0;
    await loadSql();
    if (typeof window.refreshStats === "function") window.refreshStats();
  }, 1000);
}

async function fetchMeta() {
  const res = await fetch("/api/meta");
  return res.json();
}

async function loadMeta(options = {}) {
  if (!options.silent) pushLoading("情報を取得中...");
  try {
    const data = await fetchMeta();
    updateMeta(data);
    return data;
  } finally {
    if (!options.silent) popLoading();
  }
}

function updateParseWarning(data) {
  const skipped = data.skipped_lines || 0;
  if (skipped <= 0) {
    els.parseWarning.hidden = true;
    return;
  }
  els.parseWarning.hidden = false;
  els.parseWarningText.textContent =
    `${skipped.toLocaleString()} 行を MyBatis SQL ブロックとして認識できませんでした。`;
  els.parseWarningSamples.innerHTML = "";
  for (const s of data.skipped_samples || []) {
    const li = document.createElement("li");
    li.textContent = `${s.source}:${s.line_no} — ${s.preview}`;
    els.parseWarningSamples.appendChild(li);
  }
}

function updateMeta(data) {
  if (data.directory) els.logDir.value = data.directory;
  if (!data.files || data.files.length === 0) {
    metaRange = { first: null, last: null };
    els.meta.textContent = "ログファイル未読み込み — ディレクトリを選択してください";
    els.fileList.textContent = "";
    els.fileList.title = "";
    setBackgroundLoading(false);
    updateRangeUi();
    return;
  }
  if (data.load_error) {
    els.meta.textContent = `読み込みエラー: ${data.load_error}`;
    els.fileList.textContent = data.files.join(" | ");
    els.fileList.title = data.files.join("\n");
    setBackgroundLoading(false);
    updateRangeUi();
    return;
  }
  if (data.loading) {
    metaRange = { first: null, last: null };
    const message = `MyBatis SQL をインデックス中... ${data.load_progress.toLocaleString()} 件`;
    els.meta.textContent = `${message} / ファイル ${data.files.length} 件`;
    els.fileList.textContent = data.files.join(" | ");
    els.fileList.title = data.files.join("\n");
    setBackgroundLoading(true, message);
    scheduleLoadPoll();
    updateRangeUi();
    return;
  }
  metaRange = { first: data.first, last: data.last };
  els.meta.textContent =
    `${data.total.toLocaleString()} 件の SQL / ファイル ${data.files.length} 件` +
    (data.first ? ` / ${data.first} 〜 ${data.last}` : "");
  els.fileList.textContent = data.files.join(" | ");
  els.fileList.title = data.files.join("\n");
  updateParseWarning(data);
  setBackgroundLoading(false);
  clearLoadPoll();
  updateRangeUi();
}

async function loadDirectory() {
  const directory = els.logDir.value.trim();
  if (!directory) {
    alert("ログディレクトリを入力してください。");
    return;
  }
  pushLoading("ディレクトリを読み込み中...");
  try {
    const res = await fetch("/api/load", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ directory }),
    });
    const data = await res.json();
    if (!res.ok) {
      alert(data.error || "読み込みに失敗しました。");
      return;
    }
    offset = 0;
    updateMeta(data);
    if (!data.loading) {
      await loadSql();
      if (typeof window.refreshStats === "function") window.refreshStats();
    }
  } finally {
    popLoading();
  }
}

async function loadSql() {
  const seq = ++searchSeq;
  pushLoading("SQL を検索中...");
  try {
    const res = await fetch("/api/sql?" + buildQuery());
    const data = await res.json();
    // 連打で応答が前後することがあるため、古い応答で新しい結果を上書きしない
    if (seq !== searchSeq) return;
    if (!res.ok) {
      alert(data.error || "検索に失敗しました。");
      return;
    }
    if (data.loading) {
      setBackgroundLoading(true, "インデックス構築中...");
      return;
    }
    lastTotal = data.total;
    lastPageItems = data.items;
    renderRows(data.items);
    applyRowHighlights();
    updatePager(data.offset, data.limit, data.total);
  } finally {
    popLoading();
  }
}

function sqlTypeClass(type) {
  const t = (type || "").toLowerCase();
  if (t === "select") return "sql-type-select";
  if (t === "insert") return "sql-type-insert";
  if (t === "update") return "sql-type-update";
  if (t === "delete") return "sql-type-delete";
  return "";
}

function truncate(s, len) {
  if (!s) return "";
  return s.length > len ? s.substring(0, len - 3) + "..." : s;
}

function formatCompleteStatus(complete) {
  return complete ? "完了" : "未完了";
}

function formatThreadLabel(thread) {
  if (!thread) return "-";
  return thread;
}

function renderRows(items) {
  els.rows.innerHTML = "";
  if (!items || items.length === 0) {
    lastPageItems = [];
    return;
  }
  let prevKey = null;
  for (const item of items) {
    const tr = document.createElement("tr");
    if (item.complete === false) tr.classList.add("row-incomplete");
    const groupKey = (item.source || "") + "\0" + (item.thread || "");
    if (groupKey !== "\0" && groupKey === prevKey) {
      tr.classList.add("row-same-thread-group");
    }
    prevKey = groupKey;
    tr.addEventListener("click", () => showDetail(item));
    addCell(tr, item.timestamp, { className: "col-timestamp" });
    addCell(tr, formatThreadLabel(item.thread), {
      className: "thread",
      title: item.thread
        ? "スレッド: " + item.thread + "\n同一ログファイル・同一スレッドの連続行は、同一 Java メソッド内の SQL の可能性があります"
        : "",
    });
    addCell(tr, shortMapper(item.mapper), { className: "mapper", title: item.mapper });
    addCell(tr, item.sql_type, { className: sqlTypeClass(item.sql_type) });
    addCell(tr, formatCompleteStatus(item.complete !== false), {
      className: (item.complete === false ? "status-incomplete" : "status-complete") + " col-status",
      title: item.complete === false
        ? "Total/Updates 行に到達していません（SQL失敗・中断などの可能性）"
        : "Total/Updates まで正常終了",
    });
    const boundPreview = item.bound_sql_preview || item.bound_sql;
    const sqlPreview = boundPreview || truncate(item.sql, 80);
    const sqlTitle = item.bound_sql
      ? "バインド済み:\n" + item.bound_sql + (item.sql !== item.bound_sql ? "\n\nPrepared:\n" + item.sql : "")
      : item.sql;
    addCell(tr, truncate(sqlPreview, 80), { className: "sql", title: sqlTitle });
    addCell(tr, item.elapsed_ms != null ? item.elapsed_ms + " ms" : "-");
    addCell(tr, item.row_count != null ? String(item.row_count) : "-");
    addCell(tr, shortSource(item.source), { className: "source", title: item.source });
    els.rows.appendChild(tr);
  }
}

function addCell(tr, content, options = {}) {
  const td = document.createElement("td");
  td.textContent = content;
  if (options.className) td.className = options.className;
  if (options.title) td.title = options.title;
  tr.appendChild(td);
}

function updatePager(off, limit, total) {
  const from = total === 0 ? 0 : off + 1;
  const to = Math.min(off + limit, total);
  els.resultCount.textContent = `${total.toLocaleString()} 件中 ${from}–${to} 件を表示`;
  els.pageInfo.textContent = `offset ${off}`;
  els.prev.disabled = off <= 0;
  els.next.disabled = off + limit >= total;
}

async function showDetail(item) {
  const params = new URLSearchParams({
    source: item.source,
    line_no: String(item.line_no),
    timestamp: item.timestamp,
  });
  const res = await fetch("/api/sql/detail?" + params);
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || "詳細の取得に失敗しました。");
    return;
  }
  els.detailMeta.innerHTML =
    `<dl>` +
    `<dt>Mapper:</dt><dd>${escapeHtml(data.mapper)}</dd>` +
    `<dt>スレッド:</dt><dd class="thread">${escapeHtml(data.thread || "-")}</dd>` +
    `<dt>ログファイル:</dt><dd class="source">${escapeHtml(data.source || "-")}</dd>` +
    `<dt>種別:</dt><dd>${escapeHtml(data.sql_type)}</dd>` +
    `<dt>状態:</dt><dd class="${data.complete === false ? "status-incomplete" : "status-complete"}">${escapeHtml(formatCompleteStatus(data.complete !== false))}</dd>` +
    `<dt>elapsed:</dt><dd>${data.elapsed_ms != null ? data.elapsed_ms + " ms" : "-"}</dd>` +
    `<dt>件数:</dt><dd>${data.row_count != null ? data.row_count : "-"}</dd>` +
    `<dt>Parameters:</dt><dd>${escapeHtml(data.parameters || "-")}</dd>` +
    `</dl>` +
    (data.bind_warning ? `<p class="bind-warning">${escapeHtml(data.bind_warning)}</p>` : "") +
    `<p><strong>Prepared SQL:</strong></p><pre class="sql-block">${escapeHtml(data.sql || "")}</pre>` +
    (data.bound_sql ? `<p><strong>バインド済み SQL:</strong></p><pre class="sql-block sql-bound">${escapeHtml(data.bound_sql)}</pre>` : "");
  els.detailBody.textContent = data.raw || "";
  detailTimestamp = data.timestamp;
  detailContext = { source: data.source || "", thread: data.thread || "" };
  els.detail.showModal();
}

function filterBySameSourceAndThreadFromDetail() {
  if (!detailContext || !detailContext.source || !detailContext.thread) return;
  switchTab("search");
  els.source.value = escapeRegex(detailContext.source);
  els.thread.value = escapeRegex(detailContext.thread);
  offset = 0;
  els.detail.close();
  loadSql();
}

function searchAroundFromDetail(minutes) {
  if (!detailTimestamp) return;
  switchTab("search");
  if (applyAroundMinutes(detailTimestamp, minutes)) {
    els.detail.close();
  }
}

async function openBrowseDialog() {
  await refreshBrowseList(els.logDir.value.trim());
  els.browseDialog.showModal();
}

/**
 * 一覧を取得して表示する。現在位置 browsePath は取得に成功したときだけ更新する。
 * 失敗時に不正なパスが残ると、そのまま「選択」でログディレクトリ欄に入ってしまうため。
 */
async function refreshBrowseList(nextPath) {
  const params = new URLSearchParams();
  if (nextPath) params.set("path", nextPath);
  const res = await fetch("/api/browse?" + params);
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || "ディレクトリ一覧の取得に失敗しました。");
    return;
  }
  browsePath = data.current;
  browseParent = data.parent;
  els.browseCurrent.textContent = data.current;
  els.browseUp.disabled = !data.parent;
  els.browseList.innerHTML = "";
  for (const dir of data.directories) {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = dir.split(/[/\\]/).pop() || dir;
    btn.title = dir;
    btn.addEventListener("click", () => refreshBrowseList(dir));
    li.appendChild(btn);
    els.browseList.appendChild(li);
  }
}

function resetFilters() {
  els.mapper.value = "";
  els.sqlType.value = "";
  els.sql.value = "";
  els.parameters.value = "";
  els.thread.value = "";
  els.complete.value = "";
  els.minElapsed.value = "";
  els.maxElapsed.value = "";
  els.minRowCount.value = "";
  els.maxRowCount.value = "";
  els.grep.value = "";
  els.source.value = "";
  clearDatetimeFields();
  offset = 0;
}

function switchTab(tab) {
  const isSearch = tab === "search";
  els.tabSearch.classList.toggle("active", isSearch);
  els.tabStats.classList.toggle("active", !isSearch);
  els.panelSearch.hidden = !isSearch;
  els.panelStats.hidden = isSearch;
  if (!isSearch && typeof window.refreshStats === "function") {
    window.refreshStats();
  }
}

window.applySearchFilter = function (filter) {
  switchTab("search");
  if (filter.mapper != null) els.mapper.value = filter.mapper;
  if (filter.sqlType != null) els.sqlType.value = filter.sqlType;
  if (filter.sql != null) els.sql.value = filter.sql;
  if (filter.minElapsed != null) els.minElapsed.value = filter.minElapsed;
  if (filter.minRowCount != null) els.minRowCount.value = filter.minRowCount;
  if (filter.maxRowCount != null) els.maxRowCount.value = filter.maxRowCount;
  if (filter.source != null) els.source.value = filter.source;
  if (filter.thread != null) els.thread.value = filter.thread;
  if (filter.complete != null) els.complete.value = filter.complete ? "1" : "0";
  offset = 0;
  loadSql();
};

els.tabSearch.addEventListener("click", () => switchTab("search"));
els.tabStats.addEventListener("click", () => switchTab("stats"));
els.search.addEventListener("click", () => { clearExactQueryRange(); offset = 0; loadSql(); });
els.reset.addEventListener("click", () => { resetFilters(); loadSql(); });
els.loadDir.addEventListener("click", loadDirectory);
els.browse.addEventListener("click", openBrowseDialog);
els.browseUp.addEventListener("click", () => {
  // 親パスは一覧取得時に控えてあるため、問い合わせ直す必要はない
  if (browseParent) refreshBrowseList(browseParent);
});
els.browseSelect.addEventListener("click", () => {
  els.logDir.value = browsePath;
  els.browseDialog.close();
});
els.prev.addEventListener("click", () => {
  offset = Math.max(0, offset - getPageLimit());
  loadSql();
});
els.next.addEventListener("click", () => {
  offset += getPageLimit();
  loadSql();
});
els.pageLimit.addEventListener("change", () => {
  // limit を変えたまま「次へ」を押すと offset がずれるため、その場で 1 ページ目を取り直す
  offset = 0;
  loadSql();
});
els.regexSamples.addEventListener("click", () => els.regexSamplesDialog.showModal());
els.highlight.addEventListener("input", applyRowHighlights);
els.detailSearchAround1m.addEventListener("click", () => searchAroundFromDetail(1));
els.detailSearchAround5m.addEventListener("click", () => searchAroundFromDetail(5));
els.detailFilterSameContext.addEventListener("click", () => filterBySameSourceAndThreadFromDetail());

els.rangeFirst1h.addEventListener("click", () => applyFirstHours(1));
els.rangeFirst24h.addEventListener("click", () => applyFirstHours(24));
els.rangeLast1h.addEventListener("click", () => applyLastHours(1));
els.rangeLast24h.addEventListener("click", () => applyLastHours(24));
els.rangeClear.addEventListener("click", () => {
  clearDatetimeFields();
  offset = 0;
  loadSql();
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && e.target.tagName === "INPUT") {
    if (e.target === els.logDir) {
      loadDirectory();
      return;
    }
    if (e.target === els.highlight) {
      applyRowHighlights();
      return;
    }
    if (
      e.target === els.sinceDate ||
      e.target === els.sinceTime ||
      e.target === els.untilDate ||
      e.target === els.untilTime
    ) {
      clearExactQueryRange();
    }
    offset = 0;
    loadSql();
  }
});

loadMeta().then(async (data) => {
  if (data && !data.loading && data.total > 0) await loadSql();
});

updateRangeUi();
