package com.example.mlv;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private static final long PREVIOUS_WORKER_WAIT_MS = 60_000L;

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

    public LogServer(Path logRoot, List<Path> logPaths) {
        this.logRoot = logRoot;
        this.logPaths = logPaths != null ? logPaths : Collections.<Path>emptyList();
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

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                // 先行ワーカーが同じ DB ファイルを閉じ切るまで待つ。
                // 待たずに削除・再オープンすると同一ファイルへの二重書き込みでインデックスが壊れる
                if (!awaitPreviousWorker(previous)) {
                    return;
                }
                Connection newConn = null;
                boolean adopted = false;
                try {
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
                        } else if (SqlLogIndex.needsRebuild(newConn, paths)) {
                            if (isStale(gen)) {
                                return;
                            }
                            closeQuietly(newConn);
                            IndexStore.deleteIndexFiles(root);
                            if (isStale(gen)) {
                                return;
                            }
                            newConn = SqlLogIndex.openOrCreate(root);
                            SqlLogIndex.buildIndex(newConn, paths, loadProgress::set);
                            SqlLogIndex.updateStatistics(newConn);
                            snapshot = captureMeta(newConn);
                        } else {
                            SqlLogIndex.ensureStatistics(newConn);
                            snapshot = captureMeta(newConn);
                            loadProgress.set(snapshot.total);
                        }
                    }
                    if (isStale(gen)) {
                        return;
                    }
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
     * 先行ワーカーの終了を待つ。
     *
     * @return さらに新しい読み込みに割り込まれた場合は false（この世代は破棄する）
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
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("directory") && !obj.get("directory").isJsonNull()) {
                directory = obj.get("directory").getAsString();
            }
        } catch (RuntimeException e) {
            sendErrorJson(ex, 400, "JSON を解釈できません");
            return;
        }
        if (directory.isEmpty()) {
            sendErrorJson(ex, 400, "directory を指定してください");
            return;
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
