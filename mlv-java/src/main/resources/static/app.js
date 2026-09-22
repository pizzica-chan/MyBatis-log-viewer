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
  logFormat: document.getElementById("log-format"),
  formatFileError: document.getElementById("format-file-error"),
  manageFormats: document.getElementById("manage-formats"),
  logFormatsDialog: document.getElementById("log-formats-dialog"),
  logFormatFile: document.getElementById("log-format-file"),
  logFormatList: document.getElementById("log-format-list"),
  logFormatEmpty: document.getElementById("log-format-empty"),
  logFormatSkipped: document.getElementById("log-format-skipped"),
  logFormatEditorTitle: document.getElementById("log-format-editor-title"),
  logFormatId: document.getElementById("log-format-id"),
  logFormatIdNote: document.getElementById("log-format-id-note"),
  logFormatName: document.getElementById("log-format-name"),
  logFormatPattern: document.getElementById("log-format-pattern"),
  logFormatTimestamp: document.getElementById("log-format-timestamp"),
  logFormatSample: document.getElementById("log-format-sample"),
  logFormatTry: document.getElementById("log-format-try"),
  logFormatSave: document.getElementById("log-format-save"),
  logFormatReset: document.getElementById("log-format-reset"),
  logFormatResult: document.getElementById("log-format-result"),
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
  // マッチ行のハイライト。並び順が色の優先順位（row-hl-1 → 3）になる
  highlights: [1, 2, 3].map((n) => document.getElementById("highlight-" + n)),
  fullPath: document.getElementById("full-path"),
  parseWarningDetails: document.getElementById("parse-warning-details"),
  filtersFields: document.getElementById("filters-fields"),
  filtersToggle: document.getElementById("filters-toggle"),
  filtersSummary: document.getElementById("filters-summary"),
  savedSearches: document.getElementById("saved-searches"),
  savedSearchesDialog: document.getElementById("saved-searches-dialog"),
  savedSearchName: document.getElementById("saved-search-name"),
  savedSearchSave: document.getElementById("saved-search-save"),
  savedSearchList: document.getElementById("saved-search-list"),
  savedSearchEmpty: document.getElementById("saved-search-empty"),
  savedSearchSkipped: document.getElementById("saved-search-skipped"),
  savedSearchFile: document.getElementById("saved-search-file"),
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
/** 保存した条件の一覧を描いた世代。古い応答を捨てるために使う。 */
let savedSearchListSeq = 0;

/** 各ハイライト欄の検索語（小文字化済み）。空欄は "" のまま位置を保つ。 */
function getHighlightNeedles() {
  return els.highlights.map((el) => el.value.trim().toLowerCase());
}

/** ハイライト判定の対象文字列。grep と同様に MyBatis ブロック全文（raw）を優先する（検索結果は変えない）。 */
function highlightHaystack(item) {
  const text = item.raw || [
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
  return text.toLowerCase();
}

/**
 * 行を色分けする。複数の欄に一致した行は番号の小さい欄の色にする
 * （背景色は 1 色しか出せないため。並びで優先順位が分かるようにしている）。
 */
function applyRowHighlights() {
  const needles = getHighlightNeedles();
  const active = needles.some((n) => n);
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    let hit = -1;
    if (active && item) {
      const haystack = highlightHaystack(item);
      hit = needles.findIndex((n) => n && haystack.includes(n));
    }
    for (let k = 0; k < needles.length; k += 1) {
      rows[i].classList.toggle("row-hl-" + (k + 1), k === hit);
    }
  }
}

/**
 * ハイライトの語はブラウザに覚えておく（検索条件とは別。表示だけの設定）。
 * 読めない・壊れていても空欄のまま使える。
 */
const HIGHLIGHTS_KEY = "mlv.highlights";

function saveHighlights() {
  try {
    localStorage.setItem(HIGHLIGHTS_KEY, JSON.stringify(els.highlights.map((el) => el.value)));
  } catch (e) {
    // 保存できなくてもハイライト自体は続ける
  }
}

function restoreHighlights() {
  try {
    const saved = JSON.parse(localStorage.getItem(HIGHLIGHTS_KEY) || "null");
    if (!Array.isArray(saved)) return;
    els.highlights.forEach((el, i) => {
      if (typeof saved[i] === "string") el.value = saved[i];
    });
  } catch (e) {
    // 壊れた値は無視して空欄のままにする
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
  els.parseWarningDetails.open = false;
  // 利用者定義の書式でここに出るのは「正規表現は一致したが日時を読めなかった行」が主。
  // 先頭の孤立行（一致しない行）も件数に含まれる。直す先は日時書式なので言い分ける。
  els.parseWarningText.textContent = data.log_format_custom
    ? `${skipped.toLocaleString()} 行が、書式「${data.log_format_name}」の正規表現には` +
      "一致しましたが、日時として読めませんでした。日時書式を見直してください" +
      "（下の「ファイル名:行番号」の行をログから取り出し、「書式の管理」の" +
      "「この行で試す」に貼ると理由が出ます。先頭の孤立行など、一致しない行も件数に含まれます。" +
      "下に出る例は長いと末尾を切り詰めます）。"
    : `${skipped.toLocaleString()} 行を MyBatis SQL ブロックとして認識できませんでした。`;
  els.parseWarningSamples.innerHTML = "";
  for (const s of data.skipped_samples || []) {
    const li = document.createElement("li");
    li.textContent = `${s.source}:${s.line_no} — ${s.preview}`;
    els.parseWarningSamples.appendChild(li);
  }
}

function updateMeta(data) {
  if (data.directory) els.logDir.value = data.directory;
  // 書式のプルダウンは、この先の早期 return より前に作り直す。ディレクトリ未選択・
  // 読み込み中・読み込み失敗のときも、登録した書式を選べるようにしておかないと、
  // 「登録したのに 1 回目の読み込みで指定できない」という状態になる。
  syncLogFormatSelect(data);
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
    (data.first ? ` / ${data.first} 〜 ${data.last}` : "") +
    (data.log_format_name ? ` / 書式: ${data.log_format_name}` : "");
  els.fileList.textContent = data.files.join(" | ");
  els.fileList.title = data.files.join("\n");
  updateCustomFormatHint(data);
  updateParseWarning(data);
  setBackgroundLoading(false);
  clearLoadPoll();
  updateRangeUi();
}

/**
 * 利用者定義の書式で読み込んで SQL が 1 件も出なかったときに、手がかりを出す。
 *
 * 正規表現が行にまったく一致しないと、その行は継続行として扱われる（読み飛ばしには
 * 数えない。スタックトレースと見分けられないため）。つまり正規表現が丸ごと外れていると、
 * 画面には「0 件」とだけ出て、正規表現が外れているのか、ログに SQL がないのかが
 * 区別できない。ここが利用者定義の書式でいちばん多いつまずき方になる。
 */
function updateCustomFormatHint(data) {
  if (!els.formatFileError || data.log_formats_error) return; // 読み取り失敗の表示を消さない
  const show = data.log_format_custom && data.total === 0;
  els.formatFileError.hidden = !show;
  els.formatFileError.textContent = show
    ? `書式「${data.log_format_name}」で読み込みましたが、SQL は 0 件でした。` +
      "正規表現が行に一致していないか、message に MyBatis の出力が入っていない可能性があります。" +
      "「書式の管理」の「この行で試す」に、ログの 1 行を貼って確かめてください。"
    : "";
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
      body: JSON.stringify({ directory, format: els.logFormat.value }),
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
  // 検索を実行する経路（リセット・詳細ダイアログの絞り込み・統計からの絞り込み）は
  // すべてここを通る。呼び出し側を数え上げると漏れるので、件数表示の更新は
  // この 1 箇所に集約する。保存した条件の適用だけは検索を走らせないため、
  // applyFilterFields 側で同じ更新を行う。
  updateFiltersSummary();
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
    addCell(tr, sourceCellText(item), { className: "source", title: item.source });
    els.rows.appendChild(tr);
  }
}

/**
 * ログファイル列の表示文字列。既定は末尾 2 要素だけの短縮表示で、
 * 「フルパス表示」を入れると絶対パスをそのまま出す。
 */
function sourceCellText(item) {
  return els.fullPath.checked && item.source ? item.source : shortSource(item.source);
}

/**
 * ログファイル列だけを描き替える。検索をやり直さずに切り替えたいので
 * 行は作り直さない。フルパスのときは列幅の上限を外す（body のクラスで CSS 側を切り替え）。
 */
function applySourceDisplay() {
  document.body.classList.toggle("show-full-path", els.fullPath.checked);
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    const td = rows[i].querySelector("td.source");
    if (item && td) td.textContent = sourceCellText(item);
  }
}

/**
 * 検索条件の保存・呼び出し（サーバ側。ツールホーム直下の JSON）。
 * ハイライトやフルパス表示など表示設定は対象外。検索条件欄（.filters）の
 * input/select を id -> value のマップとして保存し、適用時は同じ id の要素へ書き戻す。
 */
async function loadSavedSearches() {
  const res = await fetch("/api/saved-searches", { cache: "no-store" });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || "検索条件の読み込みに失敗しました。");
  }
  return {
    items: Array.isArray(data.items) ? data.items : [],
    skipped: typeof data.skipped === "number" ? data.skipped : 0,
    overflow: data.overflow === true,
    file: typeof data.file === "string" ? data.file : "",
  };
}

/** 検索条件欄の input/select を id -> value のマップにする。 */
function collectFilterFields() {
  const fields = {};
  for (const el of document.querySelectorAll(".filters input[id], .filters select[id]")) {
    fields[el.id] = el.value;
  }
  return fields;
}

/**
 * 読み飛ばした項目の案内。上限超過のときは保存・削除ごと断られるので、
 * 「次の保存で消える」ではなくファイルを減らすよう促す。
 */
function skippedMessage(loaded) {
  if (loaded.overflow) {
    return `保存ファイルの件数が上限を超えています。読み込めていない項目が ${loaded.skipped} 件あり、`
      + "消えないよう保存と削除を止めています。ファイルを直接編集して減らしてください。";
  }
  return `読めなかった項目が ${loaded.skipped} 件あります（次の保存でファイルから消えます）。`;
}

/** 入力欄の既定値（HTML に書いた値）。保存にない項目はここへ戻す。 */
function defaultFieldValue(el) {
  if (el.tagName === "SELECT") {
    const selected = el.querySelector("option[selected]");
    return selected ? selected.value : (el.options[0] ? el.options[0].value : "");
  }
  return el.defaultValue;
}

function applyFilterFields(fields) {
  // 保存に含まれない項目に前の入力が残ると、条件が混ざって分かりにくい。
  // 「適用 = 保存したときの状態を再現」に揃えるため、いったん既定値へ戻す。
  for (const el of document.querySelectorAll(".filters input[id], .filters select[id]")) {
    el.value = defaultFieldValue(el);
  }
  for (const [id, value] of Object.entries(fields || {})) {
    const el = document.getElementById(id);
    if (el) el.value = value;
  }
  // クイック範囲ボタンが設定する秒未満の精度は保存対象外。日時欄の値（分単位）で
  // 検索すれば同じ分の範囲になるため、古い厳密範囲は捨てる（秒精度までは再現しない）。
  clearExactQueryRange();
  updateRangeUi();
  // 適用しただけでは検索しないので、折りたたみ中の件数表示はここで合わせる
  updateFiltersSummary();
}

function formatSavedAt(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p2 = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`;
}

async function renderSavedSearchList() {
  const seq = (savedSearchListSeq += 1);
  els.savedSearchList.innerHTML = "";
  els.savedSearchEmpty.hidden = false;
  els.savedSearchEmpty.textContent = "読み込み中...";
  if (els.savedSearchSkipped) els.savedSearchSkipped.hidden = true;
  let loaded;
  try {
    loaded = await loadSavedSearches();
  } catch (e) {
    if (seq !== savedSearchListSeq) return;
    els.savedSearchEmpty.textContent = e.message || "検索条件の読み込みに失敗しました。";
    return;
  }
  if (seq !== savedSearchListSeq) return;
  if (els.savedSearchFile) {
    // パスは textContent で入れる（HTML として解釈させない）
    els.savedSearchFile.textContent = loaded.file || "(取得できませんでした)";
    els.savedSearchFile.title = loaded.file || "";
  }
  if (els.savedSearchSkipped) {
    els.savedSearchSkipped.hidden = loaded.skipped <= 0;
    els.savedSearchSkipped.textContent = loaded.skipped > 0
      ? skippedMessage(loaded)
      : "";
  }
  const list = loaded.items.slice().sort((a, b) => (a.savedAt < b.savedAt ? 1 : -1));
  els.savedSearchEmpty.textContent = "保存した検索条件はまだありません。";
  els.savedSearchEmpty.hidden = list.length > 0;
  for (const saved of list) {
    const li = document.createElement("li");
    li.className = "saved-search-item";

    const info = document.createElement("div");
    info.className = "saved-search-info";
    const name = document.createElement("span");
    name.className = "saved-search-name";
    name.textContent = saved.name;
    name.title = saved.name;
    const date = document.createElement("span");
    date.className = "saved-search-date";
    date.textContent = formatSavedAt(saved.savedAt);
    info.appendChild(name);
    info.appendChild(date);

    const buttons = document.createElement("div");
    buttons.className = "saved-search-buttons";
    const applyBtn = document.createElement("button");
    applyBtn.type = "button";
    applyBtn.textContent = "適用";
    // 条件を入れるだけにする（実行は利用者が「検索」を押したとき）。
    // 重いログでは、呼び出しただけで走るほうが困るため。
    applyBtn.addEventListener("click", () => {
      applyFilterFields(saved.fields);
      els.savedSearchesDialog.close();
      // 欄を入れるだけなので、表には前の条件の結果が残る。そのまま「次へ」を押すと
      // 新しい条件の 2 ページ目から読んでしまうため、ページャは止めておく。
      // 「検索」を押した時点で offset ごと組み直す。
      offset = 0;
      els.prev.disabled = true;
      els.next.disabled = true;
    });
    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "saved-search-delete";
    deleteBtn.textContent = "削除";
    deleteBtn.addEventListener("click", async () => {
      if (!confirm(`「${saved.name}」を削除しますか？`)) return;
      try {
        const params = new URLSearchParams();
        params.set("id", saved.id);
        const res = await fetch("/api/saved-searches?" + params, { method: "DELETE" });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          alert(data.error || "削除に失敗しました。");
          return;
        }
        await renderSavedSearchList();
      } catch (e) {
        alert(e.message || "削除に失敗しました。");
      }
    });
    buttons.appendChild(applyBtn);
    buttons.appendChild(deleteBtn);

    li.appendChild(info);
    li.appendChild(buttons);
    els.savedSearchList.appendChild(li);
  }
}

/** 現在の検索条件欄の内容に名前を付けて保存する。同名があれば確認のうえ上書きする。 */
async function saveCurrentSearch() {
  const name = els.savedSearchName.value.trim();
  if (!name) {
    alert("名前を入力してください。");
    return;
  }
  try {
    const loaded = await loadSavedSearches();
    const existing = loaded.items.find((s) => s.name === name);
    if (existing && !confirm(`「${name}」は既に保存されています。上書きしますか？`)) return;
    const res = await fetch("/api/saved-searches", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, fields: collectFilterFields() }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      alert(data.error || "検索条件の保存に失敗しました。");
      return;
    }
    els.savedSearchName.value = "";
    await renderSavedSearchList();
  } catch (e) {
    alert(e.message || "検索条件の保存に失敗しました。");
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
  const page = total === 0 ? 1 : Math.floor(off / limit) + 1;
  const pages = Math.max(1, Math.ceil(total / limit));
  els.pageInfo.textContent = `${page} / ${pages}`;
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

/**
 * 検索条件欄の折りたたみ。フィールド部分（#filters-fields）だけを hidden にし、
 * ツールバー・検索/リセットボタン・範囲ヒントは常に表示のままにする。
 * hidden で隠すだけなので、折りたたんでいても入力値は DOM 上に残ったままで、
 * 検索ボタンを押したときに送られるパラメータは開いているときと変わらない。
 */
const FILTERS_COLLAPSED_KEY = "mlv.filtersCollapsed";

/**
 * 有効な検索条件の数。折りたたむと何で絞り込んでいるか見えなくなるため、
 * 件数だけは常に出す。期間は入力欄が 4 つあるが、条件としては 1 つと数える。
 * 表示件数（page-limit）は絞り込み条件ではないので数えない。
 */
function countActiveFilters() {
  const datetimeIds = ["since-date", "since-time", "until-date", "until-time"];
  let count = 0;
  for (const el of document.querySelectorAll(".filters input[id], .filters select[id]")) {
    if (el.id === "page-limit" || datetimeIds.indexOf(el.id) >= 0) continue;
    if (el.value.trim()) count += 1;
  }
  if (els.sinceDate.value || els.untilDate.value) count += 1;
  return count;
}

/** 件数表示を現在の入力に合わせる。折りたたんでいるときだけ表示する。 */
function updateFiltersSummary() {
  const count = countActiveFilters();
  els.filtersSummary.textContent = count > 0 ? `条件 ${count} 件` : "条件なし";
  els.filtersSummary.hidden = !els.filtersFields.hidden;
}

function setFiltersCollapsed(collapsed) {
  els.filtersFields.hidden = collapsed;
  els.filtersToggle.textContent = collapsed ? "展開する" : "折りたたむ";
  els.filtersToggle.setAttribute("aria-expanded", String(!collapsed));
  updateFiltersSummary();
}

els.filtersToggle.addEventListener("click", () => {
  const collapsed = !els.filtersFields.hidden;
  setFiltersCollapsed(collapsed);
  try {
    localStorage.setItem(FILTERS_COLLAPSED_KEY, collapsed ? "1" : "");
  } catch (e) {
    // ストレージが使えなくても表示の切り替え自体は継続する
  }
});

// 前回の開閉状態を復元する（ブラウザ単位。読めない/壊れていても既定の展開状態にする）
try {
  setFiltersCollapsed(localStorage.getItem(FILTERS_COLLAPSED_KEY) === "1");
} catch (e) {
  setFiltersCollapsed(false);
}

for (const el of els.highlights) {
  el.addEventListener("input", () => {
    applyRowHighlights();
    saveHighlights();
  });
}
// 最初の一覧を描く前に戻しておく（描画後の applyRowHighlights で色が付く）
restoreHighlights();
els.savedSearches.addEventListener("click", () => {
  renderSavedSearchList();
  els.savedSearchesDialog.showModal();
});
els.savedSearchSave.addEventListener("click", saveCurrentSearch);
/*
 * ダイアログは form method="dialog" なので、名前欄で Enter を押すと暗黙送信が
 * 走って「閉じる」が発火し、保存されないままダイアログが閉じる。Enter でも
 * 保存できるように既定動作を止める。
 */
els.savedSearchName.addEventListener("keydown", (e) => {
  if (e.key !== "Enter") return;
  // document 側の Enter ハンドラ（検索の実行）まで伝播すると、保存の裏で
  // 検索も走ってしまう（対象が INPUT であること以外の条件を見ていないため）。
  // IME 確定の Enter でも同じなので、保存しない場合も伝播だけは止める。
  e.stopPropagation();
  if (e.isComposing || e.keyCode === 229) return; // IME 確定の Enter では保存しない
  e.preventDefault();
  saveCurrentSearch();
});
els.fullPath.addEventListener("change", applySourceDisplay);
// リロードでチェック状態が復元されることがあるので、初期表示でも body のクラスを合わせる
applySourceDisplay();
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
    if (els.highlights.includes(e.target)) {
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

/**
 * 書式のプルダウンをサーバの一覧で作り直す。
 *
 * 組み込み書式に加えて、利用者が mlv-log-formats.txt に書いた書式も並ぶ。
 * ファイルを直して読み込み直せば、サーバを起動し直さずに選べるようになる。
 * 選択中の値は保てるときだけ保つ（消された書式を選んだままにしない）。
 */
function syncLogFormatOptions(formats) {
  if (!Array.isArray(formats) || formats.length === 0) return;
  const signature = formats.map((f) => `${f.id}\t${f.name}`).join("\n");
  if (els.logFormat.dataset.signature === signature) return;
  els.logFormat.dataset.signature = signature;

  const previous = els.logFormat.value;
  const auto = els.logFormat.querySelector('option[value="auto"]');
  const autoText = auto ? auto.textContent : "書式: 自動判定";
  els.logFormat.innerHTML = "";
  const autoOption = document.createElement("option");
  autoOption.value = "auto";
  autoOption.textContent = autoText;
  els.logFormat.appendChild(autoOption);
  for (const f of formats) {
    if (typeof f.id !== "string" || typeof f.name !== "string") continue;
    const option = document.createElement("option");
    option.value = f.id;
    // 利用者が足した書式だと分かるようにする（組み込みと見分けがつかないと、
    // 書式ファイルを消したときに選択肢が消えた理由が分からない）。
    option.textContent = f.custom ? `書式: ${f.name}（利用者定義）` : `書式: ${f.name}`;
    els.logFormat.appendChild(option);
  }
  if (previous && els.logFormat.querySelector(`option[value="${cssEscape(previous)}"]`)) {
    els.logFormat.value = previous;
  }
}

/** セレクタに値を埋めるときのエスケープ（書式 id は英数字とハイフンだが、念のため）。 */
function cssEscape(value) {
  if (window.CSS && typeof window.CSS.escape === "function") {
    return window.CSS.escape(value);
  }
  return value.replace(/["\\]/g, "\\$&");
}

/**
 * 実際に使われた書式をセレクトへ反映する。
 *
 * <p>自動判定のときは「自動判定」の表示を保ったまま、判定結果を option の文言に添える。
 * 利用者が明示指定していた場合はその選択をそのまま残す。
 */
function syncLogFormatSelect(data) {
  syncLogFormatOptions(data.log_formats);
  // 書式ファイルを読めていないことは黙って無視しない。組み込み書式では動くので、
  // 気づかないまま「自分で足した書式が出てこない」と悩むことになる。
  if (els.formatFileError) {
    els.formatFileError.hidden = !data.log_formats_error;
    els.formatFileError.textContent = data.log_formats_error
      ? `書式ファイルを読めません: ${data.log_formats_error}`
      : "";
  }
  const auto = els.logFormat.querySelector('option[value="auto"]');
  if (!auto) return;
  // 判定結果を添えるのは取り込みが終わったときだけ。log_format_name は取り込み前も
  // 初期値（既定書式）が入っているので、条件を付けないと「まだ判定していないのに
  // 既定書式が選ばれた」ように見える。
  if (data.log_format_auto && data.log_format_name && data.load_status === "ready") {
    // 利用者定義が選ばれたことは必ず出す。自動判定は一致した行数で決めるので、
    // 広い正規表現の書式は組み込みを越えて選ばれうる。ここが黙っていると、
    // 自分が登録した書式で読まれていることに気づけない。
    auto.textContent = data.log_format_custom
      ? `書式: 自動判定（${data.log_format_name}・利用者定義）`
      : `書式: 自動判定（${data.log_format_name}）`;
  } else {
    auto.textContent = "書式: 自動判定";
  }
  if (!data.log_format_auto && data.log_format) {
    els.logFormat.value = data.log_format;
  }
}

/*
 * ログ書式の管理。
 *
 * サーバ側の mlv-log-formats.txt を読み書きする。ファイルを手で編集する経路も
 * 残してあるので、ここは同じファイルを触っているだけ。正規表現はそのままの形で
 * やり取りする（JSON のエスケープは保存時にサーバ側が面倒を見る）。
 */
async function loadLogFormats() {
  const res = await fetch("/api/log-formats", { cache: "no-store" });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || "書式の読み込みに失敗しました。");
  }
  return data;
}

function setLogFormatResult(message, kind) {
  els.logFormatResult.hidden = !message;
  els.logFormatResult.textContent = message || "";
  els.logFormatResult.className = message ? `log-format-result is-${kind}` : "log-format-result";
}

/**
 * 編集中かどうかで id 欄とボタンの表示を切り替える。
 *
 * 保存は id での upsert なので、編集の途中で id を書き換えると上書きではなく
 * 別の書式が増える（古いほうも残るので、上限 20 件を意図せず埋めてしまう）。
 * 編集中は id を触らせないことで、この取り違えを起こさせない。
 */
function setLogFormatEditing(editing) {
  els.logFormatId.readOnly = editing;
  els.logFormatIdNote.hidden = !editing;
  els.logFormatSave.textContent = editing ? "更新する" : "登録する";
}

function clearLogFormatEditor() {
  els.logFormatId.value = "";
  els.logFormatName.value = "";
  els.logFormatPattern.value = "";
  els.logFormatTimestamp.value = "";
  els.logFormatEditorTitle.textContent = "新しい書式を登録";
  setLogFormatEditing(false);
  setLogFormatResult("", "info");
}

/** 既存の書式を編集欄へ読み込む（id は固定され、保存すると上書きになる）。 */
function editLogFormat(format) {
  els.logFormatId.value = format.id;
  els.logFormatName.value = format.name;
  els.logFormatPattern.value = format.pattern;
  els.logFormatTimestamp.value = format.timestamp;
  els.logFormatEditorTitle.textContent = `「${format.name}」を編集`;
  setLogFormatEditing(true);
  setLogFormatResult("", "info");
  els.logFormatPattern.focus();
}

async function renderLogFormatList() {
  let data;
  try {
    data = await loadLogFormats();
  } catch (e) {
    els.logFormatEmpty.hidden = false;
    els.logFormatEmpty.textContent = e.message || "書式の読み込みに失敗しました。";
    els.logFormatList.innerHTML = "";
    return;
  }
  els.logFormatFile.textContent = data.file || "(不明)";
  const items = Array.isArray(data.items) ? data.items : [];
  els.logFormatEmpty.textContent = "登録した書式はまだありません。";
  els.logFormatEmpty.hidden = items.length > 0;
  // 書式の件数と行の件数は分けて出す（「書式 3 件」と言われて節が 1 つしか
  // ないと、利用者は何を直せばよいか分からなくなる）
  const skippedParts = [];
  if (data.skipped_formats) skippedParts.push(`書式 ${data.skipped_formats} 件`);
  if (data.skipped_lines) skippedParts.push(`行 ${data.skipped_lines} 件`);
  els.logFormatSkipped.hidden = skippedParts.length === 0;
  els.logFormatSkipped.textContent = skippedParts.length
    ? `読めなかった部分があります（${skippedParts.join(" / ")}）。`
      + "直すまで登録・削除はできません。理由は起動したターミナルに行番号つきで出ています。"
    : "";

  els.logFormatList.innerHTML = "";
  for (const format of items) {
    const li = document.createElement("li");
    li.className = "saved-search-item";

    const info = document.createElement("div");
    info.className = "saved-search-info";
    const name = document.createElement("span");
    name.className = "saved-search-name";
    name.textContent = `${format.name}（${format.id}）`;
    const detail = document.createElement("span");
    detail.className = "saved-search-meta";
    detail.textContent = format.timestamp;
    info.appendChild(name);
    info.appendChild(detail);
    li.appendChild(info);

    const buttons = document.createElement("div");
    buttons.className = "saved-search-buttons";
    const editBtn = document.createElement("button");
    editBtn.type = "button";
    editBtn.textContent = "編集";
    editBtn.addEventListener("click", () => editLogFormat(format));
    buttons.appendChild(editBtn);

    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "saved-search-delete";
    deleteBtn.textContent = "削除";
    deleteBtn.addEventListener("click", async () => {
      if (!confirm(`書式「${format.name}」を削除しますか？`)) return;
      try {
        const params = new URLSearchParams();
        params.set("id", format.id);
        const res = await fetch("/api/log-formats?" + params, { method: "DELETE" });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          alert(data.error || "削除に失敗しました。");
          return;
        }
        await renderLogFormatList();
      } catch (e) {
        alert(e.message || "削除に失敗しました。");
      }
    });
    buttons.appendChild(deleteBtn);
    li.appendChild(buttons);
    els.logFormatList.appendChild(li);
  }
}

function currentLogFormatInput() {
  return {
    id: els.logFormatId.value.trim(),
    name: els.logFormatName.value.trim(),
    pattern: els.logFormatPattern.value,
    timestamp: els.logFormatTimestamp.value.trim(),
  };
}

/** 保存せずにサンプル 1 行で試す。どこを直せばよいかを、その場で返す。 */
async function tryLogFormat() {
  const body = currentLogFormatInput();
  body.sample = els.logFormatSample.value.replace(/\r?\n$/, "");
  if (!body.sample) {
    setLogFormatResult("試すログの行を入れてください。", "error");
    return;
  }
  try {
    const res = await fetch("/api/log-formats/try", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      setLogFormatResult(data.error || "試せませんでした。", "error");
      return;
    }
    if (!data.matched) {
      setLogFormatResult(data.reason || "この行には一致しませんでした。", "error");
      return;
    }
    // 日時書式がまだ空のときは、正規表現だけを見た結果が返る
    const parts = [
      data.timestamp_checked ? `日時: ${data.timestamp}` : `日時の文字列: ${data.ts_text}`,
      `レベル: ${data.level || "(なし)"}`,
      `スレッド: ${data.thread || "(なし)"}`,
      `ロガー: ${data.logger || "(なし)"}`,
      `メッセージ: ${data.message || "(なし)"}`,
    ];
    const note = data.timestamp_checked
      ? ""
      : "（日時書式を入れると、この文字列を日時として読めるかも確かめます）";
    setLogFormatResult("この行から取り出せました — " + parts.join(" / ") + note, "ok");
  } catch (e) {
    setLogFormatResult(e.message || "試せませんでした。", "error");
  }
}

async function saveLogFormat() {
  const body = currentLogFormatInput();
  try {
    const res = await fetch("/api/log-formats", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      setLogFormatResult(data.error || "登録に失敗しました。", "error");
      return;
    }
    setLogFormatResult(`「${data.name}」を登録しました。書式のプルダウンから選べます。`, "ok");
    els.logFormatEditorTitle.textContent = `「${data.name}」を編集`;
    // 登録した直後はその書式の編集中。続けて保存しても同じ id を上書きする
    setLogFormatEditing(true);
    await renderLogFormatList();
  } catch (e) {
    setLogFormatResult(e.message || "登録に失敗しました。", "error");
  }
}

/**
 * ガイドの「部品」をクリックしたら、正規表現の欄のカーソル位置へ差し込む。
 *
 * 正規表現を 1 から書くのは負担が大きい。ログの行を貼り付けて、変わるところだけを
 * 部品で置き換える、という進め方ができるようにする。選択範囲があればそれを置き換える。
 */
function insertLogFormatPart(snippet) {
  const el = els.logFormatPattern;
  const start = el.selectionStart != null ? el.selectionStart : el.value.length;
  const end = el.selectionEnd != null ? el.selectionEnd : el.value.length;
  el.value = el.value.slice(0, start) + snippet + el.value.slice(end);
  const caret = start + snippet.length;
  el.focus();
  el.setSelectionRange(caret, caret);
}

for (const button of document.querySelectorAll(".log-format-part")) {
  button.addEventListener("click", () => {
    insertLogFormatPart(button.dataset.insert);
    // 日時の部品は、対になる日時書式が 1 つに決まる。正規表現と日時書式の食い違いは
    // いちばん多いつまずきなので、選んだ時点で両方そろえる。
    if (button.dataset.timestamp) {
      els.logFormatTimestamp.value = button.dataset.timestamp;
    }
  });
}

// 日時書式だけを選ぶボタン（正規表現を手で書いたときのため）
for (const button of document.querySelectorAll(".log-format-ts-part")) {
  button.addEventListener("click", () => {
    els.logFormatTimestamp.value = button.dataset.timestamp;
    els.logFormatTimestamp.focus();
  });
}

els.manageFormats.addEventListener("click", () => {
  clearLogFormatEditor();
  renderLogFormatList();
  els.logFormatsDialog.showModal();
});
els.logFormatTry.addEventListener("click", tryLogFormat);
els.logFormatSave.addEventListener("click", saveLogFormat);
els.logFormatReset.addEventListener("click", clearLogFormatEditor);
/*
 * このダイアログも form method="dialog" なので、input で Enter を押すと暗黙送信が
 * 走って「閉じる」が発火し、入力中の書式が捨てられる。さらに document 側の Enter
 * ハンドラまで伝播すると、裏で検索も走ってしまう。保存した検索条件の名前欄と
 * 同じ扱いにして、Enter では「登録する」を押したのと同じ動きにする。
 */
for (const input of [els.logFormatId, els.logFormatName, els.logFormatTimestamp]) {
  input.addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    e.stopPropagation();
    if (e.isComposing || e.keyCode === 229) return; // IME 確定の Enter では登録しない
    e.preventDefault();
    saveLogFormat();
  });
}
/*
 * 正規表現とサンプルは textarea なので暗黙送信は起きないが、document 側の Enter
 * ハンドラは拾ってしまう（改行を入れるたびに検索が走る）。伝播だけ止める。
 */
for (const area of [els.logFormatPattern, els.logFormatSample]) {
  area.addEventListener("keydown", (e) => {
    if (e.key === "Enter") e.stopPropagation();
  });
}
// ダイアログを閉じたら、書式の増減をプルダウンへ反映する
// （loadMeta が中で updateMeta まで行うので、読み直すだけでよい）
els.logFormatsDialog.addEventListener("close", () => {
  loadMeta({ silent: true }).catch(() => {});
});
