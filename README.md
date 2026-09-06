# MyBatis Log Viewer (mlv)

Java アプリケーションのログファイルから **MyBatis の SQL 実行ログ**（Preparing / Parameters / Total / Updates）を抽出し、Web UI で **検索・統計・詳細表示** するローカル専用ツールです。

## 機能

- ディレクトリ配下のログファイルを再帰探索し、MyBatis SQL ブロックをインデックス化
- SQL 一覧のフィルタ検索（mapper / SQL 種別 / SQL 文 / パラメータ / elapsed / 日時 / grep）
- 統計ダッシュボード（SQL 種別別件数、mapper 別 TOP、遅い SQL ランキング）
- 行クリックで SQL 全文・パラメータ・生ログブロックを表示
- SQLite インデックスを `tmp/mlv/` に永続化（2 回目以降は高速起動）

## 前提

- **Docker で動作確認（おすすめ）:** [Docker Desktop](https://www.docker.com/products/docker-desktop/)（ホストに JDK/Maven 不要）
- **ローカル開発:** JDK 8 以上（ビルド時は `javac` を含む JDK）、Maven 3.6 以上（ビルド時）

## ビルド

```powershell
cd D:\workspace\MyBatis-log-viewer\mlv-java
mvn -q clean package
```

成果物: `mlv-java\target\mlv-java.jar`

## 起動

```powershell
java -jar mlv-java\target\mlv-java.jar
```

または `start.bat` を実行（JAR 未ビルド時は自動ビルド → ブラウザ起動）。

ブラウザで http://127.0.0.1:8767 を開きます。

```powershell
java -jar mlv-java\target\mlv-java.jar --dir samples --port 8767
java -jar mlv-java\target\mlv-java.jar --fts
```

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--dir` | 起動時に読み込むログディレクトリ | — |
| `--host` | 待ち受けアドレス | `127.0.0.1` |
| `--port` | 待ち受けポート | `8767` |
| `--fts` | grep を FTS5 で高速化 | 無効 |

## Docker で動作確認（ローカル）

JDK/Maven をインストールせず、コンテナだけでサンプルログの閲覧まで試せます。

### ワンクリック起動（おすすめ）

1. [Docker Desktop](https://www.docker.com/products/docker-desktop/) をインストールする。
2. リポジトリ直下の **`docker-up.bat`** をダブルクリック（または `scripts\one-click-up.cmd`）。

Docker Desktop が止まっていれば **自動起動・待機**（最大約 3 分）→ **ビルド** → **起動** → **ブラウザで http://localhost:8767 を開く** まで一気に実行されます。`samples/` のログは自動読み込みされます。

| 操作 | コマンド |
|------|----------|
| 起動 | `docker-up.bat` |
| 停止 | `docker-down.bat` |
| ソース変更の反映 | `scripts\one-click-restart.cmd` |
| ログ追従 | `.\scripts\one-click-up.ps1 -FollowLogs` |

### 手動（docker compose のみ）

**Docker Desktop を先に起動**してから、リポジトリ直下で実行します。

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f app
docker compose down
```

### マウントと環境変数

| 項目 | 説明 |
|------|------|
| `./samples` → `/app/logs/samples` | サンプルログ（読み取り専用）。起動時に `--dir` で自動読み込み |
| `./tmp` → `/app/tmp` | SQLite インデックスの永続化 |
| `MLV_LOG_DIR` | ホスト側のログディレクトリを差し替え（例: `$env:MLV_LOG_DIR="C:\logs\app"`） |
| `MLV_HOME=/app` | コンテナ内のリポジトリルート（インデックス保存先の基準） |

**ホストポート:** 既定は **`127.0.0.1:8767`**（ループバックのみ）です。競合する場合は `docker-compose.yml` の `ports` を `"127.0.0.1:18767:8767"` のように変更してください。`127.0.0.1:` を外すと LAN 全体に公開されます。

**注意:** ローカル検証専用です。認証機構を持たず、`/api/browse` で任意ディレクトリの列挙、`/api/load` で任意ディレクトリのインデックス化ができるため、`127.0.0.1` 以外に公開しないでください。

## 対象ログ形式

MyBatis 3 標準 DEBUG 出力（SLF4J / Logback 等）:

```
2026-06-15 00:19:11.705[thread][DEBUG][com.example.mapper.UserMapper.selectById] - ==>  Preparing: SELECT ...
2026-06-15 00:19:11.706[thread][DEBUG][com.example.mapper.UserMapper.selectById] - ==> Parameters: 1(Long)
2026-06-15 00:19:11.708[thread][DEBUG][com.example.mapper.UserMapper.selectById] - <==      Total: 1
```

Java ログヘッダは Tomcat 形式 `[Thread][LEVEL][Logger(FQCN)]` および旧形式に対応しています。

## サンプル

`samples/mybatis-sample.log` を `--dir samples` で読み込むと動作確認できます。

## 参考

[application-log-viewer](D:\workspace\application-log-viewer) のアーキテクチャ（Java 8 + 内蔵 HTTP + SQLite + Web UI）をベースに MyBatis 専用パーサを実装しています。
