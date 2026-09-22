# MyBatis-log-viewer (mlv)

Java アプリケーションのログから MyBatis の SQL 実行ログ（Preparing / Parameters /
Total / Updates）を抽出し、Web UI で検索・統計・詳細表示するローカル専用ツール。
Java 8 / JDK 内蔵 HTTP サーバ / SQLite インデックス。実装は `mlv-java/`。

## ルール

`.cursor/rules/` のルールは Cursor 用に書かれているが、**Claude Code でも同じ内容を適用する**。
各ファイル冒頭の `description` / `globs` / `alwaysApply` は Cursor 用のメタ情報なので、
そこは読み飛ばして本文だけを適用する。

@.cursor/rules/performance-claims.mdc
@.cursor/rules/powershell-encoding.mdc

ルールを追加・変更するときは `.cursor/rules/` 側を正本として直し、この一覧にも追記する。

## ビルドとテスト

`pom.xml` はリポジトリ直下ではなく `mlv-java/` にある。直下から実行するときは `-f` を付ける。

```bash
mvn -f mlv-java/pom.xml test            # テスト
mvn -q -f mlv-java/pom.xml package      # 実行可能 JAR → mlv-java/target/mlv-java.jar
java -jar mlv-java/target/mlv-java.jar --dir samples
```

`cd mlv-java && mvn test` でもよいが、シェルの作業ディレクトリが戻る環境では
`-f` を使うほうが確実。

## 押さえておくこと

- 索引は `tmp/mlv/{sha256(ログディレクトリの絶対パス)}.db`。`MLV_HOME` で保存先を
  変えられるので、**新旧のビルドを同じログに対して同時に動かして比較できる**
- ログファイルの mtime + サイズの指紋が一致すれば索引を再利用する。作り直させたいときは
  該当の `tmp/mlv/*.db` を削除する
- 1 エントリは Preparing / Parameters / Total（または Updates）の複数行ブロックから
  組み立てる。このパースが重いため、以前は単一ファイルで大きくパース律速だった。
  `LogParser` のスレッド判定を最適化して解消し、**索引込みの構築は書き込み律速**に
  なっている。性能を変えるときは `.cursor/rules/performance-claims.mdc` に従う
- 索引は取込中に維持する。取込後にまとめて作る方式は実測で遅くなったため採用していない。
  理由と実測値は `SqlLogIndex.initSchema` の索引生成箇所のコメントを参照
- 一覧 API は行ごとに MyBatis ブロック全文（`raw`）を返す。切り詰めるとハイライトとずれるため、
  `LogServer.MAX_LIMIT` 側で抑える方針（コメント参照）
- 取り込むログの書式は `LogFormatSpec`（組み込みの `LogFormat` か、利用者が
  `mlv-log-formats.txt` に正規表現で定義した `CustomLogFormat`）で切り替える。
  **取り込み開始時に 1 つへ確定させる**ため、書式を増やしても 1 行あたりの判定は
  1 書式ぶんで済む。この前提を崩さないこと（行ごとに複数書式を試すと継続行の扱いが
  重くなる）
- 利用者定義の書式は正規表現で 1 行ずつ照合するぶん重い（索引構築で 1,852ms → 3,836ms。
  実測条件は `CustomLogFormat` のクラスコメント）。**この重さを払うのは、その書式を
  選んだ取り込みだけ**にすること。組み込み書式の経路を変えない（`SqlLogIndex` の
  取り込みループの分岐はループの外で 1 回だけ材料を取る）
- 利用者定義の書式では `message` を必須にしている。MyBatis の `Preparing:` /
  `Parameters:` / `Total:` をメッセージ部から探すため、**ここが空だと SQL が 1 件も
  見つからない索引が黙ってできあがる**。`thread` と `logger` はブロックの対応付けの鍵
  （`MyBatisBlockParser.blockKey`）なので、無いと別スレッドの SQL が混ざる
- 利用者定義の書式には**バイト列だけのヘッダ判定が無い**。代わりに
  `CustomLogFormat.parse` が「正規表現が当たったか」を返し、それをヘッダらしさとして使う。
  **当たったのに読めなかった行（日時が壊れている）は読み飛ばしとして数える**こと ――
  継続行と混ぜると、日時書式の間違いが画面のどこにも出ない。正規表現がそもそも
  当たらない行は継続行と見分けられないので数えない。そちらの手がかりは
  「SQL 0 件」の注記で出している（`app.js` の `updateCustomFormatHint`）
- 利用者が書いた正規表現は後戻りが爆発しうるので、照合させる文字数に上限を置いて
  取り込みごと失敗させる（`CustomLogFormat.BoundedCharSequence`）。固まらせない。
  **照合する経路はすべて `CustomLogFormat.guarded` を通すこと**（取り込みだけ包んで
  試し打ちを素通しにすると、同じ書式が試し打ちでだけ原因不明の 500 になる）
- 利用者定義の書式は画面の「書式の管理」からも、`mlv-log-formats.txt` の手編集でも
  入れられる。**どちらも `LogFormatStore.create` で同じ検査を通すこと**（別々に検査すると、
  画面では登録できるのにファイルからは読めない書式ができる）
- 書式は索引のフィンガープリントに含まれる。変えると索引は作り直される。
  組み込み書式のフィンガープリントは `format:<id>` のまま変えないこと
  （変えると利用者の既存の索引がすべて作り直しになる）

## Windows 環境での注意

- コンソールは cp932。日本語を含むファイルを生成・読み込みするスクリプトは
  UTF-8 を明示する（Python なら `open(..., encoding="utf-8")`）。
  コミットメッセージ自体は UTF-8 で正しく保存されるので、**ターミナル表示が
  文字化けしても内容は壊れていない**（`git log` の出力をバイト列で確認すればよい）
- `tmp/` は `.gitignore` 済み。ベンチマーク用の大きなログはリポジトリ内に置かない
