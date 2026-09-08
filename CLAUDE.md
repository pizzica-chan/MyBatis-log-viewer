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
mvn -f mlv-java/pom.xml test            # テスト（42 件）
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

## Windows 環境での注意

- コンソールは cp932。日本語を含むファイルを生成・読み込みするスクリプトは
  UTF-8 を明示する（Python なら `open(..., encoding="utf-8")`）。
  コミットメッセージ自体は UTF-8 で正しく保存されるので、**ターミナル表示が
  文字化けしても内容は壊れていない**（`git log` の出力をバイト列で確認すればよい）
- `tmp/` は `.gitignore` 済み。ベンチマーク用の大きなログはリポジトリ内に置かない
