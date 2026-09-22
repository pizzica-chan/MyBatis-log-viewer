package com.example.mlv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.example.mlv.SqlLogIndex.EntryRow;
import com.example.mlv.SqlStats.MapperStat;
import com.example.mlv.SqlStats.SlowSql;
import com.example.mlv.SqlStats.Summary;

public final class LogServer {

    // 一覧は行ごとに MyBatis ブロック全文（raw）を返すため、上限を上げるとレスポンスが
    // 一気に膨らむ（5000 件で 5.2MB 実測）。raw を切り詰めるとハイライトとずれるため上限側で抑える
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 200;
    /**
     * 先行ワーカーの終了を待つ上限。
     *
     * <p>割り込まれたワーカーは「パーサ回収（最大 {@code PARSER_SHUTDOWN_TIMEOUT_MS}）→
     * abortIncompleteBuild →接続クローズ」の順で後始末する。回収だけで上限いっぱい掛かると
     * 残りの後始末は待ち時間の外に出てしまうため、回収の上限に余裕を足した値にする。
     */
    private static final long PREVIOUS_WORKER_WAIT_MS =
            SqlLogIndex.PARSER_SHUTDOWN_TIMEOUT_MS + 30_000L;

    /**
     * 読み込み完了時点の meta 情報。
     * 進捗ポーリング（/api/meta）が重い検索と dbLock を奪い合わないよう、DB に触らず応答するために持つ。
     */
    private static final class MetaSnapshot {
        static final MetaSnapshot EMPTY =
                new MetaSnapshot(0L, null, null, 0, Collections.<SkippedSample>emptyList());

        final long total;
        final String first;
        final String last;
        final int skippedLines;
        final List<SkippedSample> skippedSamples;

        MetaSnapshot(long total, String first, String last, int skippedLines,
                List<SkippedSample> skippedSamples) {
            this.total = total;
            this.first = first;
            this.last = last;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    private static final class SkippedSample {
        final String source;
        final long lineNo;
        final String preview;

        SkippedSample(String source, long lineNo, String preview) {
            this.source = source;
            this.lineNo = lineNo;
            this.preview = preview;
        }
    }

    private volatile Path logRoot;
    private volatile List<Path> logPaths = Collections.emptyList();

    private volatile String loadStatus = "idle";
    private volatile String loadError;
    private final AtomicLong loadProgress = new AtomicLong();
    private final AtomicLong loadGeneration = new AtomicLong(0);
    private volatile Thread loadWorker;
    private volatile MetaSnapshot metaSnapshot = MetaSnapshot.EMPTY;

    private final Object loadLock = new Object();
    private final Object dbLock = new Object();
    private Connection conn;

    private final Map<String, byte[]> staticCache = new HashMap<>();
    private final SavedSearchesStore savedSearches;
    /**
     * 利用者が定義した書式の置き場所。画面の「書式の管理」から登録・削除すると
     * このファイルを書き換える。利用者が直接編集してもよい。
     */
    private final LogFormatStore logFormats;
    /** 書式ファイルを読めなかった理由。画面に出して、黙って無視されないようにする。 */
    private volatile String logFormatsError;

    /**
     * 利用者が明示指定した書式。{@code null} なら取り込みのたびに自動判定する。
     * 自動判定が外れたときに UI から上書きできるようにするための逃げ道。
     */
    private volatile LogFormatSpec requestedFormat;
    /** 直近の取り込みで実際に使った書式。画面に出すために保持する。 */
    private volatile LogFormatSpec resolvedFormat = LogFormatSpec.DEFAULT;

    public LogServer(Path logRoot, List<Path> logPaths) {
        this(logRoot, logPaths, null);
    }

    public LogServer(Path logRoot, List<Path> logPaths, LogFormatSpec requestedFormat) {
        this.logRoot = logRoot;
        this.logPaths = logPaths != null ? logPaths : Collections.<Path>emptyList();
        this.requestedFormat = requestedFormat;
        this.savedSearches = new SavedSearchesStore(SavedSearchesStore.defaultFile());
        this.logFormats = new LogFormatStore(LogFormatStore.defaultFile());
    }

    public void start(String host, int port) throws IOException {
        synchronized (dbLock) {
            try {
                conn = SqlLogIndex.openMemory();
            } catch (Exception e) {
                throw new IOException("SQLite を初期化できません: " + e.getMessage(), e);
            }
        }
        if (!logPaths.isEmpty()) {
            startLoad();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.createContext("/", new RootHandler());

        System.out.println("MyBatis Log Viewer (Java): http://" + host + ":" + port);
        System.out.println("インデックス: " + IndexStore.tmpIndexDir() + " (MLV_HOME で repo 変更可)");
        System.out.println("保存した検索条件: " + savedSearches.file() + " (MLV_HOME で変更可)");
        System.out.println("利用者定義のログ書式: " + logFormats.file()
                + " (無くてもよい。MLV_HOME で変更可)");
        System.out.println("ログ書式: "
                + (requestedFormat != null ? requestedFormat.displayName() : "自動判定"));
        if (logRoot != null) {
            System.out.println("ログディレクトリ: " + PathUtil.normalizePath(logRoot));
        }
        System.out.println("読み込みファイル (" + logPaths.size() + "):");
        for (Path p : logPaths) {
            System.out.println("  - " + PathUtil.normalizePath(p));
        }
        if (logPaths.isEmpty()) {
            System.out.println("  (未読み込み — ブラウザからディレクトリを選択してください)");
        }
        server.start();
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                if ("/".equals(path)) {
                    serveStatic(ex, "index.html");
                } else if (path.startsWith("/static/")) {
                    serveStatic(ex, path.substring("/static/".length()));
                } else if ("/api/meta".equals(path)) {
                    sendJson(ex, 200, metaPayload());
                } else if ("/api/browse".equals(path)) {
                    handleBrowse(ex);
                } else if ("/api/load".equals(path) && "POST".equalsIgnoreCase(method)) {
                    handleLoad(ex);
                } else if ("/api/sql".equals(path)) {
                    handleSql(ex);
                } else if ("/api/sql/detail".equals(path)) {
                    handleDetail(ex);
                } else if ("/api/stats/summary".equals(path)) {
                    handleStatsSummary(ex);
                } else if ("/api/stats/mappers".equals(path)) {
                    handleStatsMappers(ex);
                } else if ("/api/saved-searches".equals(path)) {
                    handleSavedSearches(ex);
                } else if ("/api/log-formats".equals(path)) {
                    handleLogFormats(ex);
                } else if ("/api/log-formats/try".equals(path)) {
                    handleLogFormatTry(ex);
                } else if ("/api/stats/slow".equals(path)) {
                    handleStatsSlow(ex);
                } else {
                    sendError(ex, 404, "not found");
                }
            } catch (Exception e) {
                // 127.0.0.1 限定のローカルツールのため、詳細はログにもレスポンスにも出す
                System.err.println("リクエスト処理に失敗しました: " + ex.getRequestURI());
                e.printStackTrace();
                try {
                    sendError(ex, 500, e.getMessage() != null ? e.getMessage() : e.toString());
                } catch (IOException ignored) {
                    // ignore
                }
            } finally {
                ex.close();
            }
        }
    }

    private void startLoad() {
        final long gen = loadGeneration.incrementAndGet();
        final Thread previous;
        synchronized (loadLock) {
            previous = loadWorker;
            loadStatus = "loading";
            loadError = null;
            loadProgress.set(0);
            metaSnapshot = MetaSnapshot.EMPTY;
        }
        if (previous != null) {
            previous.interrupt();
        }
        final Path root = logRoot;
        final List<Path> paths = new ArrayList<>(logPaths);
        // 明示指定が無ければ先頭ファイルの冒頭から判定する。判定は取り込み開始時の 1 回だけで、
        // 1 行あたりの処理は確定した 1 書式ぶんしか走らない。
        final LogFormatSpec requested = requestedFormat;
        final LogFormatSpec format = requested != null
                ? requested : LogFormatSpec.detect(paths, customFormats());

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                Connection newConn = null;
                boolean adopted = false;
                try {
                    // 先行ワーカーが同じ DB ファイルを閉じ切るまで待つ。待たずに削除・再オープンすると
                    // 同一ファイルへの二重書き込みでインデックスが壊れる。
                    // 待ち切れなかった場合は例外になり、下の catch が error 状態にする
                    if (!awaitPreviousWorker(previous)) {
                        return;
                    }
                    MetaSnapshot snapshot;
                    if (root == null) {
                        if (isStale(gen)) {
                            return;
                        }
                        newConn = SqlLogIndex.openMemory();
                        SqlLogIndex.clearIndex(newConn);
                        snapshot = MetaSnapshot.EMPTY;
                    } else {
                        if (isStale(gen)) {
                            return;
                        }
                        IndexStore.ensureTmpDirFor(root);
                        newConn = SqlLogIndex.openOrCreate(root);
                        if (paths.isEmpty()) {
                            if (isStale(gen)) {
                                return;
                            }
                            closeQuietly(newConn);
                            IndexStore.deleteIndexFiles(root);
                            if (isStale(gen)) {
                                return;
                            }
                            newConn = SqlLogIndex.openOrCreate(root);
                            SqlLogIndex.clearIndex(newConn);
                            snapshot = MetaSnapshot.EMPTY;
                        } else if (SqlLogIndex.needsRebuild(newConn, paths, format)) {
                            if (isStale(gen)) {
                                return;
                            }
                            closeQuietly(newConn);
                            IndexStore.deleteIndexFiles(root);
                            if (isStale(gen)) {
                                return;
                            }
                            newConn = SqlLogIndex.openOrCreate(root);
                            SqlLogIndex.buildIndex(newConn, paths, loadProgress::set, format);
                            SqlLogIndex.updateStatistics(newConn);
                            snapshot = captureMeta(newConn);
                        } else {
                            // 索引構成が変わると既存の統計は古くなる。サンプリング ANALYZE は
                            // 行数に関係なく数十ミリ秒で終わるため、有無を判定せず作り直す。
                            SqlLogIndex.updateStatistics(newConn);
                            snapshot = captureMeta(newConn);
                            loadProgress.set(snapshot.total);
                        }
                    }
                    if (isStale(gen)) {
                        return;
                    }
                    // 索引を再利用した場合は、そのとき使った書式を meta から引く（判定と食い違わない）。
                    // 世代が古いワーカーが上書きしないよう、isStale の後で代入する。
                    String usedId = newConn != null ? SqlLogIndex.getLogFormatId(newConn) : null;
                    LogFormatSpec used = LogFormatSpec.byId(usedId, customFormats());
                    resolvedFormat = used != null ? used : format;
                    synchronized (loadLock) {
                        if (isStale(gen)) {
                            return;
                        }
                        replaceConn(newConn);
                        adopted = true;
                        metaSnapshot = snapshot;
                        loadProgress.set(snapshot.total);
                        loadStatus = "ready";
                    }
                } catch (Throwable t) {
                    synchronized (loadLock) {
                        if (isStale(gen)) {
                            return;
                        }
                        loadStatus = "error";
                        loadError = t.getMessage() != null ? t.getMessage() : t.toString();
                    }
                } finally {
                    if (newConn != null && !adopted) {
                        closeQuietly(newConn);
                    }
                }
            }
        }, "mlv-loader");
        worker.setDaemon(true);
        // 代入と起動の間に startLoad が割り込むと interrupt が届かないため、ロック内で起動する
        synchronized (loadLock) {
            loadWorker = worker;
            worker.start();
        }
    }

    /**
     * 先行ワーカーの終了を待つ。待ち切れない場合はこの世代を捨てる。
     *
     * <p>先行ワーカーが生きているまま進むと、同じ DB ファイルを削除・再オープンした先へ
     * 旧接続が abortIncompleteBuild を書き込みうる。上限を超えたら索引を壊すより
     * エラーにして中断する。
     *
     * @return 先に進んでよい場合のみ true
     */
    private static boolean awaitPreviousWorker(Thread previous) {
        if (previous == null) {
            return true;
        }
        try {
            previous.join(PREVIOUS_WORKER_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (previous.isAlive()) {
            throw new IllegalStateException(
                    "先行する読み込みが " + (PREVIOUS_WORKER_WAIT_MS / 1000)
                            + " 秒以内に終了しませんでした。インデックスを保護するため中断します");
        }
        return true;
    }

    private boolean isStale(long gen) {
        return gen != loadGeneration.get();
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void replaceConn(Connection newConn) {
        synchronized (dbLock) {
            if (conn != null && conn != newConn) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            conn = newConn;
        }
    }

    private void ensureLoadStarted() {
        synchronized (loadLock) {
            if (logPaths.isEmpty() || !"idle".equals(loadStatus)) {
                return;
            }
            // 同時に届いた /api/meta が二重にワーカーを起動しないよう、先に状態を進める
            loadStatus = "loading";
        }
        startLoad();
    }

    /** 読み込み完了時の DB 内容を控える。以降 /api/meta は DB に触らない。 */
    private static MetaSnapshot captureMeta(Connection c) throws SQLException {
        long total = SqlLogIndex.entryCount(c);
        String[] bounds = SqlLogIndex.timestampBounds(c);
        int skipped = SqlLogIndex.getSkippedLineCount(c);
        List<SkippedSample> samples = Collections.emptyList();
        if (skipped > 0) {
            samples = new ArrayList<>();
            for (SkippedLine s : SqlLogIndex.getSkippedLineSamples(c)) {
                String source = SqlLogIndex.filePath(c, s.fileId);
                samples.add(new SkippedSample(source != null ? source : "", s.lineNo, s.preview));
            }
        }
        return new MetaSnapshot(total, bounds[0], bounds[1], skipped, samples);
    }

    /**
     * 利用者定義の書式を読み直す。ファイルを直してから画面で読み込み直せば、
     * サーバを起動し直さずに新しい書式を試せる（中身が変わっていなければ
     * {@link LogFormatStore} が前回の結果を返すので、読み直しの費用はかからない）。
     *
     * <p>読めないときは空として扱い、理由を画面に出す。組み込み書式まで
     * 巻き添えで使えなくなると、書式ファイルを直すための調査すらできなくなる。
     */
    private List<CustomLogFormat> customFormats() {
        try {
            List<CustomLogFormat> formats = logFormats.load();
            logFormatsError = null;
            return formats;
        } catch (IOException e) {
            // 例外によっては message が null になるので、そのまま equals しない
            String message = String.valueOf(e.getMessage());
            if (!message.equals(logFormatsError)) {
                System.err.println("書式ファイルを読めません: " + message);
            }
            logFormatsError = message;
            return Collections.emptyList();
        }
    }

    /** 画面の書式プルダウンに出す一覧（組み込み + 利用者定義）。 */
    private JsonArray formatChoices(List<CustomLogFormat> customs) {
        JsonArray choices = new JsonArray();
        for (LogFormat f : LogFormat.values()) {
            choices.add(formatChoice(f.id(), f.displayName(), false));
        }
        for (CustomLogFormat c : customs) {
            choices.add(formatChoice(c.id(), c.displayName(), true));
        }
        return choices;
    }

    private static JsonObject formatChoice(String id, String name, boolean custom) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("custom", custom);
        return o;
    }

    private JsonObject metaPayload() {
        ensureLoadStarted();
        String status = loadStatus;
        boolean loading = "loading".equals(status);
        long progress = loadProgress.get();
        MetaSnapshot meta = "ready".equals(status) ? metaSnapshot : MetaSnapshot.EMPTY;

        JsonObject payload = new JsonObject();
        payload.addProperty("directory", logRoot != null ? PathUtil.normalizePath(logRoot) : null);
        payload.add("files", sourceNames());
        payload.addProperty("loading", loading);
        payload.addProperty("load_status", status);
        payload.addProperty("load_progress", progress);
        payload.addProperty("total", loading ? progress : meta.total);
        payload.addProperty("first", meta.first);
        payload.addProperty("last", meta.last);
        LogFormatSpec usedFormat = resolvedFormat;
        payload.addProperty("log_format", usedFormat.id());
        payload.addProperty("log_format_name", usedFormat.displayName());
        // 読み飛ばした行の説明を書き分けるために要る。利用者定義の書式で外れたときに
        // 「MyBatis SQL ブロックとして認識できません」と言われても、直す先が分からない
        payload.addProperty("log_format_custom", usedFormat.isCustom());
        payload.addProperty("log_format_auto", requestedFormat == null);
        payload.add("log_formats", formatChoices(customFormats()));
        if (logFormatsError != null) {
            payload.addProperty("log_formats_error", logFormatsError);
        }
        if (!loading && meta.skippedLines > 0) {
            payload.addProperty("skipped_lines", meta.skippedLines);
            JsonArray samples = new JsonArray();
            for (SkippedSample s : meta.skippedSamples) {
                JsonObject o = new JsonObject();
                o.addProperty("source", s.source);
                o.addProperty("line_no", s.lineNo);
                o.addProperty("preview", s.preview);
                samples.add(o);
            }
            payload.add("skipped_samples", samples);
        }
        if (loadError != null) {
            payload.addProperty("load_error", loadError);
        }
        return payload;
    }

    private JsonArray sourceNames() {
        JsonArray arr = new JsonArray();
        for (Path p : logPaths) {
            arr.add(PathUtil.normalizePath(p));
        }
        return arr;
    }

    private void handleBrowse(HttpExchange ex) throws IOException {
        Map<String, String> params = queryParams(ex);
        String rawPath = params.getOrDefault("path", "");
        Path current;
        if (!rawPath.isEmpty()) {
            current = PathUtil.resolve(rawPath);
        } else if (logRoot != null) {
            current = logRoot;
        } else {
            current = Paths.get("").toAbsolutePath();
        }

        if (!Files.isDirectory(current)) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "ディレクトリが見つかりません: " + PathUtil.normalizePath(current));
            sendJson(ex, 400, err);
            return;
        }

        Path parent = current.getParent();
        List<String> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path entry : stream) {
                Path name = entry.getFileName();
                if (name != null && Files.isDirectory(entry) && !name.toString().startsWith(".")) {
                    dirs.add(PathUtil.normalizePath(entry));
                }
            }
        } catch (IOException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "ディレクトリを読み取れません: " + e.getMessage());
            sendJson(ex, 400, err);
            return;
        }
        dirs.sort(new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
            }
        });

        JsonObject payload = new JsonObject();
        payload.addProperty("current", PathUtil.normalizePath(current));
        payload.addProperty("parent",
                (parent != null && !parent.equals(current)) ? PathUtil.normalizePath(parent) : null);
        JsonArray arr = new JsonArray();
        for (String d : dirs) {
            arr.add(d);
        }
        payload.add("directories", arr);
        sendJson(ex, 200, payload);
    }

    private void handleLoad(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String directory = "";
        String formatId = "";
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("directory") && !obj.get("directory").isJsonNull()) {
                directory = obj.get("directory").getAsString();
            }
            if (obj.has("format") && !obj.get("format").isJsonNull()) {
                formatId = obj.get("format").getAsString();
            }
        } catch (RuntimeException e) {
            sendErrorJson(ex, 400, "JSON を解釈できません");
            return;
        }
        if (directory.isEmpty()) {
            sendErrorJson(ex, 400, "directory を指定してください");
            return;
        }
        // "auto"（または未指定）は自動判定。未知の id はエラーにして黙って既定へ落とさない。
        LogFormatSpec format = null;
        if (!formatId.isEmpty() && !"auto".equals(formatId)) {
            format = LogFormatSpec.byId(formatId, customFormats());
            if (format == null) {
                // 書式ファイルを読めていないなら、原因はそちら。「未知の書式」とだけ返すと、
                // 選んだ書式が消えたように見えて、直すべきファイルに辿り着けない
                // （自動判定はこの場合「書式ファイルを読めません」と言う。言い分けない）。
                String error = logFormatsError;
                sendErrorJson(ex, 400, error != null
                        ? "書式ファイルを読めないため、書式 " + formatId + " を引けません: " + error
                        : "未知のログ書式です: " + formatId);
                return;
            }
        }
        Path root = PathUtil.resolve(directory);
        if (!Files.isDirectory(root)) {
            sendErrorJson(ex, 400, "ディレクトリが見つかりません: " + PathUtil.normalizePath(root));
            return;
        }

        List<Path> paths;
        try {
            paths = Discovery.findLogFiles(root);
        } catch (IOException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }
        this.requestedFormat = format;
        synchronized (loadLock) {
            this.logRoot = root;
            this.logPaths = paths;
            this.loadError = null;
        }
        startLoad();
        sendJson(ex, 200, metaPayload());
    }

    private void handleSql(HttpExchange ex) throws IOException {
        if ("loading".equals(loadStatus)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("loading", true);
            payload.addProperty("load_progress", loadProgress.get());
            payload.addProperty("total", 0);
            payload.addProperty("offset", 0);
            payload.addProperty("limit", 0);
            payload.add("items", new JsonArray());
            sendJson(ex, 200, payload);
            return;
        }
        if ("error".equals(loadStatus)) {
            sendErrorJson(ex, 500, loadError != null ? loadError : "読み込みに失敗しました");
            return;
        }

        Map<String, String> p = queryParams(ex);
        SqlQueryFilter filter = new SqlQueryFilter();
        try {
            filter.sqlTypes = SqlQueryFilter.parseSqlTypeFilter(p.get("sql_type"));
            filter.mapperRe = SqlQueryFilter.compileRegex(p.get("mapper"));
            filter.sqlRe = SqlQueryFilter.compileRegex(p.get("sql"));
            filter.parametersRe = SqlQueryFilter.compileRegex(p.get("parameters"));
            filter.threadRe = SqlQueryFilter.compileRegex(p.get("thread"));
            filter.sourceRe = SqlQueryFilter.compileRegex(p.get("source"));
            filter.grepRe = SqlQueryFilter.compileRegex(p.get("grep"));
            filter.grepText = p.get("grep");
            filter.minElapsed = SqlQueryFilter.parseIntOrNull(p.get("min_elapsed"));
            filter.maxElapsed = SqlQueryFilter.parseIntOrNull(p.get("max_elapsed"));
            filter.minRowCount = SqlQueryFilter.parseIntOrNull(p.get("min_row_count"));
            filter.maxRowCount = SqlQueryFilter.parseIntOrNull(p.get("max_row_count"));
            filter.complete = SqlQueryFilter.parseCompleteFilter(p.get("complete"));
        } catch (RuntimeException e) {
            sendErrorJson(ex, 400, "正規表現が不正です: " + e.getMessage());
            return;
        }
        try {
            String since = p.get("since");
            String until = p.get("until");
            filter.sinceMillis = (since != null && !since.isEmpty()) ? TimeUtil.parseUiDatetime(since) : null;
            filter.untilMillis = (until != null && !until.isEmpty()) ? TimeUtil.parseUiDatetime(until) : null;
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }

        long limit;
        long offset;
        try {
            limit = Math.max(0, Math.min(parseLong(p.get("limit"), DEFAULT_LIMIT), MAX_LIMIT));
            offset = Math.max(parseLong(p.get("offset"), 0), 0);
        } catch (NumberFormatException e) {
            sendErrorJson(ex, 400, "limit/offset は整数で指定してください");
            return;
        }

        SqlQuery.Result result;
        try {
            synchronized (dbLock) {
                result = SqlQuery.querySql(conn, filter, offset, limit);
            }
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("total", result.total);
        payload.addProperty("offset", offset);
        payload.addProperty("limit", limit);
        JsonArray items = new JsonArray();
        Map<String, RandomAccessFile> rawHandles = new HashMap<>();
        try {
            for (EntryRow e : result.page) {
                items.add(rowJson(e, readPageRaw(rawHandles, e)));
            }
        } finally {
            for (RandomAccessFile f : rawHandles.values()) {
                try {
                    f.close();
                } catch (IOException ignored) {
                    // クローズ失敗は無視
                }
            }
        }
        payload.add("items", items);
        sendJson(ex, 200, payload);
    }

    private JsonObject rowJson(EntryRow e, String raw) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", TimeUtil.formatIso(e.tsMillis));
        o.addProperty("mapper", e.mapper);
        o.addProperty("sql_type", e.sqlType);
        o.addProperty("sql", e.sqlText);
        o.addProperty("parameters", e.parameters);
        addBoundSqlFields(o, e.sqlText, e.parameters);
        if (e.rowCount != null) {
            o.addProperty("row_count", e.rowCount);
        }
        if (e.elapsedMs != null) {
            o.addProperty("elapsed_ms", e.elapsedMs);
        }
        o.addProperty("thread", e.thread);
        o.addProperty("level", e.level);
        o.addProperty("complete", e.complete);
        o.addProperty("source", e.source);
        o.addProperty("line_no", e.lineNo);
        o.addProperty("raw", raw);
        return o;
    }

    /** 一覧表示用。MyBatis ブロック全文（Preparing/Parameters/Total 行含む）を読み出す。 */
    private static String readPageRaw(Map<String, RandomAccessFile> handles, EntryRow e) {
        try {
            RandomAccessFile file = handles.get(e.source);
            if (file == null) {
                file = new RandomAccessFile(e.source, "r");
                handles.put(e.source, file);
            }
            file.seek(e.byteOffset);
            long size = e.endByteOffset > e.byteOffset ? e.endByteOffset - e.byteOffset : 0;
            if (size <= 0) {
                return "";
            }
            byte[] buf = new byte[(int) Math.min(size, Integer.MAX_VALUE)];
            file.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static void addBoundSqlFields(JsonObject o, String sqlText, String parameters) {
        SqlParameterBinder.BindResult bound = SqlParameterBinder.bind(sqlText, parameters);
        if (bound.sql != null && !bound.sql.equals(sqlText)) {
            o.addProperty("bound_sql", bound.sql);
            String preview = SqlParameterBinder.bindPreview(sqlText, parameters, 80);
            if (preview != null) {
                o.addProperty("bound_sql_preview", preview);
            }
        } else if (bound.sql != null) {
            o.addProperty("bound_sql", bound.sql);
        }
        if (bound.warning != null) {
            o.addProperty("bind_warning", bound.warning);
        }
    }

    private void handleDetail(HttpExchange ex) throws IOException {
        if ("loading".equals(loadStatus)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("loading", true);
            payload.addProperty("load_progress", loadProgress.get());
            sendJson(ex, 200, payload);
            return;
        }
        if ("error".equals(loadStatus)) {
            sendErrorJson(ex, 500, loadError != null ? loadError : "読み込みに失敗しました");
            return;
        }

        Map<String, String> p = queryParams(ex);
        String source = p.getOrDefault("source", "");
        if (source.isEmpty()) {
            sendErrorJson(ex, 400, "ログファイルを指定してください");
            return;
        }
        long lineNo;
        try {
            lineNo = Long.parseLong(p.getOrDefault("line_no", "0"));
        } catch (NumberFormatException e) {
            sendErrorJson(ex, 400, "line_no は整数で指定してください");
            return;
        }

        EntryRow entry;
        String timestamp = p.get("timestamp");
        try {
            synchronized (dbLock) {
                entry = SqlLogIndex.findEntry(conn, source, lineNo, timestamp);
            }
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
            return;
        }
        if (entry == null) {
            sendErrorJson(ex, 404, "該当行が見つかりません");
            return;
        }

        String raw;
        try {
            raw = SqlLogIndex.readEntryRaw(Paths.get(entry.source), entry.byteOffset, entry.endByteOffset);
        } catch (IOException e) {
            sendErrorJson(ex, 500, e.getMessage());
            return;
        }

        JsonObject o = new JsonObject();
        o.addProperty("source", entry.source);
        o.addProperty("line_no", entry.lineNo);
        o.addProperty("timestamp", TimeUtil.formatIso(entry.tsMillis));
        o.addProperty("mapper", entry.mapper);
        o.addProperty("sql_type", entry.sqlType);
        o.addProperty("sql", entry.sqlText);
        o.addProperty("parameters", entry.parameters);
        addBoundSqlFields(o, entry.sqlText, entry.parameters);
        if (entry.rowCount != null) {
            o.addProperty("row_count", entry.rowCount);
        }
        if (entry.elapsedMs != null) {
            o.addProperty("elapsed_ms", entry.elapsedMs);
        }
        o.addProperty("thread", entry.thread);
        o.addProperty("complete", entry.complete);
        o.addProperty("raw", raw);
        sendJson(ex, 200, o);
    }

    private void handleStatsSummary(HttpExchange ex) throws IOException {
        if (!checkReady(ex)) {
            return;
        }
        try {
            Summary s;
            synchronized (dbLock) {
                s = SqlStats.summary(conn);
            }
            JsonObject o = new JsonObject();
            o.addProperty("total", s.total);
            o.addProperty("first", s.first);
            o.addProperty("last", s.last);
            if (s.avgElapsed != null) {
                o.addProperty("avg_elapsed", s.avgElapsed);
            }
            if (s.maxElapsed != null) {
                o.addProperty("max_elapsed", s.maxElapsed);
            }
            JsonArray types = new JsonArray();
            long maxCount = 0;
            for (Map.Entry<String, Long> e : s.bySqlType.entrySet()) {
                if (e.getValue() > maxCount) {
                    maxCount = e.getValue();
                }
            }
            for (Map.Entry<String, Long> e : s.bySqlType.entrySet()) {
                JsonObject t = new JsonObject();
                t.addProperty("sql_type", e.getKey());
                t.addProperty("count", e.getValue());
                if (maxCount > 0) {
                    // 全体比ではなく最大値を 100 とする相対値（バーの見た目用）
                    t.addProperty("bar_ratio", 100.0 * e.getValue() / maxCount);
                }
                types.add(t);
            }
            o.add("by_sql_type", types);
            sendJson(ex, 200, o);
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
        }
    }

    private void handleStatsMappers(HttpExchange ex) throws IOException {
        if (!checkReady(ex)) {
            return;
        }
        String q = queryParams(ex).get("q");
        if (q == null || q.trim().length() < 2) {
            sendErrorJson(ex, 400, "検索文字列 q は2文字以上指定してください");
            return;
        }
        try {
            List<MapperStat> list;
            List<SlowSql> slowList;
            synchronized (dbLock) {
                String trimmed = q.trim();
                list = SqlStats.searchMappers(conn, trimmed);
                slowList = SqlStats.searchSlowSql(conn, trimmed, 20);
            }
            JsonArray arr = new JsonArray();
            for (MapperStat m : list) {
                JsonObject o = new JsonObject();
                o.addProperty("mapper", m.mapper);
                o.addProperty("count", m.count);
                if (m.avgElapsed != null) {
                    o.addProperty("avg_elapsed", m.avgElapsed);
                }
                if (m.maxElapsed != null) {
                    o.addProperty("max_elapsed", m.maxElapsed);
                }
                arr.add(o);
            }
            JsonObject payload = new JsonObject();
            payload.add("items", arr);
            payload.add("slow_sql", slowSqlToJsonArray(slowList));
            sendJson(ex, 200, payload);
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
        }
    }

    private void handleStatsSlow(HttpExchange ex) throws IOException {
        if (!checkReady(ex)) {
            return;
        }
        int limit = parseIntParam(queryParams(ex).get("limit"), 20);
        try {
            List<SlowSql> list;
            synchronized (dbLock) {
                list = SqlStats.slowSql(conn, limit);
            }
            JsonObject payload = new JsonObject();
            payload.add("items", slowSqlToJsonArray(list));
            sendJson(ex, 200, payload);
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
        }
    }

    private JsonArray slowSqlToJsonArray(List<SlowSql> list) {
        JsonArray arr = new JsonArray();
        for (SlowSql s : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id);
            o.addProperty("timestamp", s.timestamp);
            o.addProperty("mapper", s.mapper);
            o.addProperty("sql_type", s.sqlType);
            o.addProperty("sql_preview", s.sqlPreview);
            o.addProperty("elapsed_ms", s.elapsedMs);
            o.addProperty("source", s.source);
            o.addProperty("line_no", s.lineNo);
            arr.add(o);
        }
        return arr;
    }

    private boolean checkReady(HttpExchange ex) throws IOException {
        if ("loading".equals(loadStatus)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("loading", true);
            sendJson(ex, 200, payload);
            return false;
        }
        if ("error".equals(loadStatus)) {
            sendErrorJson(ex, 500, loadError != null ? loadError : "読み込みに失敗しました");
            return false;
        }
        return true;
    }

    // ---- API: log-formats -------------------------------------------------

    /**
     * 利用者定義のログ書式の読み書き。
     *
     * <p>ファイルを手で編集する経路も残してあるので、ここは同じファイルを同じ検査で
     * 読み書きするだけ。壊れた書式を弾くのは {@link LogFormatStore#create}。
     */
    private void handleLogFormats(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        try {
            if ("GET".equalsIgnoreCase(method)) {
                LogFormatStore.Loaded loaded = logFormats.loadDetailed();
                logFormatsError = null;
                JsonObject payload = new JsonObject();
                JsonArray items = new JsonArray();
                for (CustomLogFormat f : loaded.items) {
                    items.add(logFormatJson(f));
                }
                payload.add("items", items);
                // 書式の件数と行の件数は分けて返す（画面が「書式が N 件」と出すため）
                payload.addProperty("skipped_formats", loaded.skippedFormats);
                payload.addProperty("skipped_lines", loaded.skippedLines);
                // 画面に実際の保存先を出すため、解決済みの絶対パスを返す
                payload.addProperty("file", PathUtil.normalizePath(logFormats.file()));
                payload.addProperty("max", LogFormatStore.MAX_FORMATS);
                sendJson(ex, 200, payload);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                JsonObject obj = readJsonObject(ex);
                if (obj == null) {
                    return;
                }
                CustomLogFormat saved = logFormats.upsert(
                        jsonString(obj, "id"), jsonString(obj, "name"),
                        jsonString(obj, "pattern"), jsonString(obj, "timestamp"));
                sendJson(ex, 200, logFormatJson(saved));
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                String id = queryParams(ex).get("id");
                if (id == null || id.isEmpty()) {
                    sendErrorJson(ex, 400, "id を指定してください");
                    return;
                }
                if (!logFormats.delete(id)) {
                    sendErrorJson(ex, 404, "指定した書式が見つかりません");
                    return;
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("deleted", true);
                sendJson(ex, 200, payload);
                return;
            }
            sendErrorJson(ex, 405, "method not allowed");
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
        } catch (IOException e) {
            sendErrorJson(ex, 500,
                    e.getMessage() != null ? e.getMessage() : "書式ファイルの読み書きに失敗しました");
        }
    }

    /**
     * 書式をサンプル 1 行で試す。<strong>保存はしない。</strong>
     *
     * <p>正規表現は書いてすぐ当たることのほうが少ない。保存してから取り込み直して
     * 確かめる往復をなくすため、その場で結果（取り出せた項目、または当たらない理由）を返す。
     *
     * <p><strong>日時書式はまだ空でもよい。</strong>利用者はふつう、ログの行を貼って
     * 正規表現を組み立て、当たることを確かめてから日時書式を書く。そこで日時書式を
     * 必須にすると、いちばん最初の試し打ちが「日時書式を入れてください」で止まる。
     * 空のときは正規表現だけを見て、{@code ts} に取れた文字列をそのまま返す。
     */
    private void handleLogFormatTry(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendErrorJson(ex, 405, "method not allowed");
            return;
        }
        JsonObject obj = readJsonObject(ex);
        if (obj == null) {
            return;
        }
        // 画面は必ず文字列を送るが、API を直に叩かれると項目ごと欠けることがある。
        // 欠け・null は空文字と同じに扱う（500 にはしない）。sample が空なら
        // 理由つきの 400、timestamp が空なら正規表現だけを見た 200 になる。
        String sample = orEmpty(jsonString(obj, "sample"));
        String timestamp = orEmpty(jsonString(obj, "timestamp")).trim();
        boolean timestampChecked = !timestamp.isEmpty();
        JsonObject payload = new JsonObject();
        CustomLogFormat format;
        try {
            // id は試し打ちでは使わないが、検査を本番と揃えるため仮の値を通す。
            // 日時書式が空のときは、正規表現だけを見るために仮の書式で組み立てる
            // （この仮の値は結果に出さない）。
            format = LogFormatStore.create("try", jsonString(obj, "name"),
                    jsonString(obj, "pattern"), timestampChecked ? timestamp : "yyyy");
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }
        if (sample.isEmpty()) {
            sendErrorJson(ex, 400, "試すログの行を入れてください");
            return;
        }
        // 書式に触る処理はまとめて包む。1 か所でも外に出すと、そこだけが
        // 原因の分からない 500 になる（実際、項目の取り出しだけが素通しだった）。
        try {
            // 実際の取り込みと同じ経路（バイト列から）で試す
            byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);
            LogParser.ParsedLine parsed =
                    timestampChecked ? format.parse(bytes, bytes.length) : null;
            String matchedTs = format.matchedTimestamp(sample);
            payload.addProperty("timestamp_checked", timestampChecked);
            if (!timestampChecked) {
                // 正規表現だけを見る。日時として読めるかは、日時書式を入れてから確かめる
                payload.addProperty("matched", matchedTs != null);
                if (matchedTs == null) {
                    payload.addProperty("reason", NO_MATCH_REASON);
                } else {
                    addMatchedGroups(payload, format, sample, matchedTs);
                }
                sendJson(ex, 200, payload);
                return;
            }
            payload.addProperty("matched", parsed != null);
            if (parsed == null) {
                payload.addProperty("reason", tryFailureReason(format, sample));
            } else {
                payload.addProperty("timestamp", TimeUtil.formatIso(parsed.tsMillis));
                payload.addProperty("level", parsed.level);
                payload.addProperty("thread", parsed.thread);
                payload.addProperty("logger", parsed.logger);
                payload.addProperty("message", parsed.message);
            }
        } catch (CustomLogFormat.FormatFailure e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }
        sendJson(ex, 200, payload);
    }

    /**
     * 当たらなかった理由を、直せる粒度で返す。
     * 「一致しない」と「日時を読めない」は直す場所が違うので、必ず区別する。
     */
    private static final String NO_MATCH_REASON =
            "正規表現がこの行に一致しません。行全体（^ から $ まで）に当たる形になっているか確かめてください";

    /** 日時書式がまだ空のときの結果。日時は「取れた文字列」のまま返す。 */
    private static void addMatchedGroups(JsonObject payload, CustomLogFormat format,
            String sample, String matchedTs) {
        payload.addProperty("ts_text", matchedTs);
        for (Map.Entry<String, String> e : format.matchedGroups(sample).entrySet()) {
            payload.addProperty(e.getKey(), e.getValue());
        }
    }

    private static String tryFailureReason(CustomLogFormat format, String sample) {
        String ts = format.matchedTimestamp(sample);
        if (ts == null) {
            return NO_MATCH_REASON;
        }
        String why = format.timestampError(ts);
        return "正規表現は一致しましたが、ts に取れた「" + ts + "」を日時として読めません: "
                + (why != null ? why : "日時書式「" + format.timestampPattern() + "」を見直してください");
    }

    private static JsonObject logFormatJson(CustomLogFormat f) {
        JsonObject o = new JsonObject();
        o.addProperty("id", f.id());
        o.addProperty("name", f.displayName());
        o.addProperty("pattern", f.patternText());
        o.addProperty("timestamp", f.timestampPattern());
        return o;
    }

    /** 本文を JSON オブジェクトとして読む。読めなければ 400 を返して {@code null}。 */
    private JsonObject readJsonObject(HttpExchange ex) throws IOException {
        String body = readBodyLimited(ex, 64 * 1024);
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                sendErrorJson(ex, 400, "JSON を解釈できません");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            sendErrorJson(ex, 400, "JSON を解釈できません");
            return null;
        }
    }

    /** 無い・null を空文字に均す。「入れてください」と言えるようにするため。 */
    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    // ---- API: saved-searches ----------------------------------------------

    /**
     * 検索・追跡条件の保存。値は正規表現を含みうるが、ここでは文字列として読み書きするだけ。
     * コンパイルやマッチは行わない。
     */
    private void handleSavedSearches(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        try {
            if ("GET".equalsIgnoreCase(method)) {
                SavedSearchesStore.LoadResult loaded = savedSearches.load();
                JsonObject payload = new JsonObject();
                JsonArray items = new JsonArray();
                for (SavedSearchesStore.SavedSearch item : loaded.items) {
                    items.add(item.toJson());
                }
                payload.add("items", items);
                payload.addProperty("skipped", loaded.skipped);
                payload.addProperty("overflow", loaded.overflow);
                // 画面に実際の保存先を出すため、解決済みの絶対パスを返す
                payload.addProperty("file", PathUtil.normalizePath(savedSearches.file()));
                sendJson(ex, 200, payload);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                String body = readBodyLimited(ex, 64 * 1024);
                JsonObject obj;
                try {
                    JsonElement parsed = JsonParser.parseString(body);
                    if (!parsed.isJsonObject()) {
                        sendErrorJson(ex, 400, "JSON を解釈できません");
                        return;
                    }
                    obj = parsed.getAsJsonObject();
                } catch (RuntimeException e) {
                    sendErrorJson(ex, 400, "JSON を解釈できません");
                    return;
                }
                String name = jsonString(obj, "name");
                String mode = jsonString(obj, "mode");
                Map<String, String> fields = jsonStringMap(obj, "fields");
                SavedSearchesStore.SavedSearch saved = savedSearches.upsert(name, mode, fields);
                sendJson(ex, 200, saved.toJson());
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                String id = queryParams(ex).get("id");
                if (id == null || id.isEmpty()) {
                    sendErrorJson(ex, 400, "id を指定してください");
                    return;
                }
                if (!savedSearches.delete(id)) {
                    sendErrorJson(ex, 404, "指定した条件が見つかりません");
                    return;
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("deleted", true);
                sendJson(ex, 200, payload);
                return;
            }
            sendErrorJson(ex, 405, "method not allowed");
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
        } catch (IOException e) {
            sendErrorJson(ex, 500,
                    e.getMessage() != null ? e.getMessage() : "保存ファイルの読み書きに失敗しました");
        }
    }

    /** JSON の文字列フィールド。無い・null なら null。文字列以外は 400 相当の例外。 */
    private static String jsonString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement value = obj.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " は文字列で指定してください");
        }
        return value.getAsJsonPrimitive().getAsString();
    }

    /**
     * fields オブジェクトから文字列だけを拾う。入れ子や数値は拒否する。
     * キーの選別（未知 id を捨てる）は {@link SavedSearchesStore} 側で行う。
     */
    private static Map<String, String> jsonStringMap(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return Collections.emptyMap();
        }
        JsonElement value = obj.get(key);
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException(key + " はオブジェクトで指定してください");
        }
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            JsonElement field = entry.getValue();
            if (field == null || field.isJsonNull()) {
                continue;
            }
            if (!field.isJsonPrimitive()) {
                throw new IllegalArgumentException(
                        key + "." + entry.getKey() + " は文字列で指定してください");
            }
            JsonPrimitive primitive = field.getAsJsonPrimitive();
            if (!primitive.isString()) {
                throw new IllegalArgumentException(
                        key + "." + entry.getKey() + " は文字列で指定してください");
            }
            map.put(entry.getKey(), primitive.getAsString());
        }
        return map;
    }

    private void serveStatic(HttpExchange ex, String name) throws IOException {
        byte[] content = loadStatic(name);
        if (content == null) {
            sendError(ex, 404, "not found");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", contentType(name));
        ex.sendResponseHeaders(200, content.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(content);
        }
    }

    private byte[] loadStatic(String name) {
        if (name.contains("..")) {
            return null;
        }
        synchronized (staticCache) {
            byte[] cached = staticCache.get(name);
            if (cached != null) {
                return cached;
            }
        }
        byte[] data = null;
        try (InputStream in = LogServer.class.getResourceAsStream("/static/" + name)) {
            if (in != null) {
                data = readAll(in);
            }
        } catch (IOException ignored) {
            data = null;
        }
        // 存在しないパスを覚えるとリクエスト由来のキーでマップが無限に伸びるため、成功時のみ保持する
        if (data != null) {
            synchronized (staticCache) {
                staticCache.put(name, data);
            }
        }
        return data;
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> map = new TreeMap<String, String>();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = decode(pair.substring(0, eq));
                value = decode(pair.substring(eq + 1));
            } else {
                key = decode(pair);
                value = "";
            }
            if (!map.containsKey(key)) {
                map.put(key, value);
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static long parseLong(String s, long defaultValue) {
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(s.trim());
    }

    private static int parseIntParam(String s, int defaultValue) {
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    /** 本文を最大 {@code maxBytes} まで読む。超えたら読み切る前に止めて 400 相当にする。 */
    private String readBodyLimited(HttpExchange ex, int maxBytes) throws IOException {
        List<String> lengthHeaders = ex.getRequestHeaders().get("Content-Length");
        if (lengthHeaders != null && !lengthHeaders.isEmpty()) {
            try {
                long declared = Long.parseLong(lengthHeaders.get(0).trim());
                if (declared > maxBytes) {
                    throw new IllegalArgumentException("リクエストが大きすぎます");
                }
            } catch (NumberFormatException ignored) {
                // ヘッダが不正でも、実サイズの上限で止める
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if ((long) bos.size() + n > maxBytes) {
                    throw new IllegalArgumentException("リクエストが大きすぎます");
                }
                bos.write(buf, 0, n);
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private void sendJson(HttpExchange ex, int status, JsonObject obj) throws IOException {
        byte[] body = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendErrorJson(HttpExchange ex, int status, String message) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message != null ? message : "error");
        sendJson(ex, status, obj);
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        byte[] body = (message != null ? message : "error").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
