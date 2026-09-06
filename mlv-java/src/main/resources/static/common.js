/** app.js / stats.js で共有するユーティリティ。両方より先に読み込むこと。 */

/** テキストノード・属性値の両方で安全なようにクォートまでエスケープする。 */
function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/** 入力文字列を正規表現フィルタにそのまま渡すためのエスケープ。 */
function escapeRegex(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** com.example.mapper.UserMapper.selectById -> UserMapper.selectById */
function shortMapper(mapper) {
  if (!mapper) return "-";
  const parts = mapper.split(".");
  return parts.length > 2 ? parts.slice(-2).join(".") : mapper;
}

/** パスの末尾 2 要素だけを表示用に残す。 */
function shortSource(source) {
  if (!source) return "-";
  const parts = source.split(/[/\\]/).filter(Boolean);
  if (parts.length <= 1) return parts[0] || source;
  const sep = source.includes("\\") ? "\\" : "/";
  return parts.slice(-2).join(sep);
}
