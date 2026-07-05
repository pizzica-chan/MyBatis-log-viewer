package com.example.mlv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.mlv.MyBatisBlockParser.SqlBlock;

public final class SqlLogIndex {

    private static final int BATCH_SIZE = 5000;
    private static final long PROGRESS_INTERVAL = 50_000L;
    private static final long COMMIT_INTERVAL = 200_000L;
    private static final int MAX_SKIPPED_SAMPLES = 5;
    private static final int PREVIEW_MAX_LEN = 120;
    private static final String META_SKIPPED_LINES = "skipped_lines";
    private static final String META_SKIPPED_SAMPLES = "skipped_samples";

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc が見つかりません", e);
        }
    }

    private SqlLogIndex() {
    }

    public interface ProgressCallback {
        void onProgress(long count);
    }

    public static final class EntryRow {
        public long id;
        public long fileId;
        public long lineNo;
        public long byteOffset;
        public long endByteOffset;
        public long tsMillis;
        public long tsEndMillis;
        public String mapper;
        public String sqlType;
        public String sqlText;
        public String parameters;
        public Integer rowCount;
        public Integer elapsedMs;
        public String thread;
        public String level;
        public String source;
    }

    public static Path indexDbPath(Path logRoot) {
        return IndexStore.indexDbPath(logRoot);
    }

    public static Connection openOrCreate(Path logRoot) throws SQLException, IOException {
        IndexStore.ensureTmpDirFor(logRoot);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + indexDbPath(logRoot).toString());
        initSchema(conn);
        return conn;
    }

    public static Connection openMemory() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            initSchema(conn);
        } catch (IOException e) {
            throw new SQLException(e);
        }
        return conn;
    }

    private static void initSchema(Connection conn) throws SQLException, IOException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode = MEMORY");
            st.execute("PRAGMA synchronous = OFF");
            st.execute("PRAGMA temp_store = MEMORY");
            st.execute("PRAGMA cache_size = -65536");
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS files ("
                    + "id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, "
                    + "mtime_secs INTEGER NOT NULL, size INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS entries ("
                    + "id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, line_no INTEGER NOT NULL, "
                    + "byte_offset INTEGER NOT NULL, end_byte_offset INTEGER, "
                    + "ts_millis INTEGER NOT NULL, ts_end_millis INTEGER, "
                    + "mapper TEXT NOT NULL, sql_type TEXT NOT NULL, sql_text TEXT NOT NULL, "
                    + "parameters TEXT, row_count INTEGER, elapsed_ms INTEGER, "
                    + "thread TEXT NOT NULL, level TEXT NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_ts ON entries(ts_millis, file_id, line_no)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_mapper ON entries(mapper)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_sql_type ON entries(sql_type)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_elapsed ON entries(elapsed_ms)");
        }
    }

    private static final String FTS_SCHEMA =
            "CREATE VIRTUAL TABLE entries_fts USING fts5(body, content='', tokenize='trigram')";

    public static boolean ftsAvailable(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='entries_fts'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean recreateFts(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS entries_fts");
            st.execute(FTS_SCHEMA);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void dropFts(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS entries_fts");
        } catch (SQLException ignored) {
            // ignore
        }
    }

    private static String fileFingerprint(List<Path> paths) throws IOException {
        List<String> parts = new ArrayList<>(paths.size());
        for (Path path : paths) {
            long mtime = Files.getLastModifiedTime(path).toMillis() / 1000L;
            long size = Files.size(path);
            parts.add(PathUtil.normalizePath(path) + ":" + mtime + ":" + size);
        }
        Collections.sort(parts);
        return String.join("\n", parts);
    }

    public static boolean needsRebuild(Connection conn, List<Path> paths, boolean enableFts)
            throws SQLException, IOException {
        if (paths.isEmpty()) {
            return false;
        }
        String fp = indexFingerprint(paths, enableFts);
        String stored = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM meta WHERE key = 'fingerprint'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stored = rs.getString(1);
                }
            }
        }
        return !fp.equals(stored);
    }

    private static String indexFingerprint(List<Path> paths, boolean enableFts) throws IOException {
        return fileFingerprint(paths) + "\nfts:" + (enableFts ? "1" : "0");
    }

    public static void clearIndex(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM entries");
            st.execute("DELETE FROM files");
            st.execute("DELETE FROM meta WHERE key IN ('"
                    + META_SKIPPED_LINES + "', '" + META_SKIPPED_SAMPLES + "')");
        }
    }

    private static void abortIncompleteBuild(Connection conn) throws SQLException {
        clearIndex(conn);
        dropFts(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM meta WHERE key = 'fingerprint'");
        }
        conn.commit();
    }

    public static long entryCount(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM entries")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public static int getSkippedLineCount(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, META_SKIPPED_LINES);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString(1));
                }
            }
        }
        return 0;
    }

    public static List<SkippedLine> getSkippedLineSamples(Connection conn) throws SQLException {
        String json = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, META_SKIPPED_SAMPLES);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    json = rs.getString(1);
                }
            }
        }
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        return deserializeSkippedSamples(json);
    }

    public static String filePath(Connection conn, long fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT path FROM files WHERE id = ?")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static String[] timestampBounds(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(ts_millis), MAX(ts_millis) FROM entries")) {
            if (rs.next()) {
                long min = rs.getLong(1);
                boolean hasMin = !rs.wasNull();
                long max = rs.getLong(2);
                boolean hasMax = !rs.wasNull();
                return new String[] {
                        hasMin ? TimeUtil.formatIso(min) : null,
                        hasMax ? TimeUtil.formatIso(max) : null,
                };
            }
        }
        return new String[] {null, null};
    }

    static final class Row {
        long fileId;
        long lineNo;
        long byteOffset;
        long endByteOffset;
        long tsMillis;
        long tsEndMillis;
        String mapper;
        String sqlType;
        String sqlText;
        String parameters;
        Integer rowCount;
        Integer elapsedMs;
        String thread;
        String level;
        StringBuilder bodyBuf;
    }

    private static final List<Row> POISON = Collections.emptyList();

    public static final class BuildResult {
        public final long entryCount;
        public final int skippedLines;
        public final List<SkippedLine> skippedSamples;

        BuildResult(long entryCount, int skippedLines, List<SkippedLine> skippedSamples) {
            this.entryCount = entryCount;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    public static BuildResult buildIndex(Connection conn, List<Path> paths, ProgressCallback progress,
            boolean enableFts) throws SQLException, IOException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            return buildIndexTx(conn, paths, progress, enableFts);
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private static BuildResult buildIndexTx(Connection conn, List<Path> paths, ProgressCallback progress,
            boolean enableFts) throws SQLException, IOException {
        clearIndex(conn);
        boolean hasFts = enableFts && recreateFts(conn);
        if (!enableFts) {
            dropFts(conn);
        }
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM meta WHERE key = 'fingerprint'");
        }
        conn.commit();
        String fp = indexFingerprint(paths, enableFts);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO files (id, path, mtime_secs, size) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < paths.size(); i++) {
                Path path = paths.get(i);
                ps.setLong(1, i + 1L);
                ps.setString(2, PathUtil.normalizePath(path));
                ps.setLong(3, Files.getLastModifiedTime(path).toMillis() / 1000L);
                ps.setLong(4, Files.size(path));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();

        int threads = Math.max(1, Math.min(paths.size(), Runtime.getRuntime().availableProcessors()));
        BlockingQueue<List<Row>> queue = new ArrayBlockingQueue<>(Math.max(8, threads * 4));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger remaining = new AtomicInteger(paths.size());
        AtomicLong skippedCounter = new AtomicLong();
        List<SkippedLine> skippedSamples = Collections.synchronizedList(new ArrayList<SkippedLine>());

        for (int i = 0; i < paths.size(); i++) {
            final long fileId = i + 1L;
            final Path path = paths.get(i);
            pool.submit(() -> {
                try {
                    parseFileInto(fileId, path, queue, hasFts, skippedCounter, skippedSamples);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        putUninterruptibly(queue, POISON);
                    }
                }
            });
        }
        if (paths.isEmpty()) {
            putUninterruptibly(queue, POISON);
        }

        long total = 0;
        long nextId = 1;
        boolean buildComplete = false;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO entries (id, file_id, line_no, byte_offset, end_byte_offset, "
                        + "ts_millis, ts_end_millis, mapper, sql_type, sql_text, parameters, "
                        + "row_count, elapsed_ms, thread, level) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement ftsIns = hasFts ? conn.prepareStatement(
                "INSERT INTO entries_fts (rowid, body) VALUES (?, ?)") : null) {
            while (true) {
                List<Row> batch;
                try {
                    batch = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (batch == POISON) {
                    buildComplete = true;
                    break;
                }
                for (Row r : batch) {
                    long id = nextId++;
                    ins.setLong(1, id);
                    ins.setLong(2, r.fileId);
                    ins.setLong(3, r.lineNo);
                    ins.setLong(4, r.byteOffset);
                    ins.setLong(5, r.endByteOffset);
                    ins.setLong(6, r.tsMillis);
                    ins.setLong(7, r.tsEndMillis);
                    ins.setString(8, r.mapper);
                    ins.setString(9, r.sqlType);
                    ins.setString(10, r.sqlText);
                    ins.setString(11, r.parameters);
                    if (r.rowCount != null) {
                        ins.setInt(12, r.rowCount);
                    } else {
                        ins.setNull(12, java.sql.Types.INTEGER);
                    }
                    if (r.elapsedMs != null) {
                        ins.setInt(13, r.elapsedMs);
                    } else {
                        ins.setNull(13, java.sql.Types.INTEGER);
                    }
                    ins.setString(14, r.thread);
                    ins.setString(15, r.level);
                    ins.addBatch();
                    if (ftsIns != null) {
                        ftsIns.setLong(1, id);
                        ftsIns.setString(2, r.bodyBuf != null ? r.bodyBuf.toString() : ftsBody(r));
                        ftsIns.addBatch();
                    }
                }
                ins.executeBatch();
                if (ftsIns != null) {
                    ftsIns.executeBatch();
                }
                long before = total;
                total += batch.size();
                if (before / COMMIT_INTERVAL != total / COMMIT_INTERVAL) {
                    conn.commit();
                }
                if (progress != null && before / PROGRESS_INTERVAL != total / PROGRESS_INTERVAL) {
                    progress.onProgress(total);
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Throwable t = error.get();
        if (t != null || !buildComplete) {
            abortIncompleteBuild(conn);
            if (t != null) {
                throw new IOException("インデックス構築に失敗しました: " + t.getMessage(), t);
            }
            throw new IOException("インデックス構築が中断されました");
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('fingerprint', ?)")) {
            ps.setString(1, fp);
            ps.executeUpdate();
        }
        saveSkippedMeta(conn, (int) skippedCounter.get(), skippedSamples);
        conn.commit();

        if (progress != null) {
            progress.onProgress(total);
        }
        return new BuildResult(total, (int) skippedCounter.get(), new ArrayList<>(skippedSamples));
    }

    private static String ftsBody(Row r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.mapper).append(' ').append(r.sqlText);
        if (r.parameters != null) {
            sb.append(' ').append(r.parameters);
        }
        return sb.toString();
    }

    private static void saveSkippedMeta(Connection conn, int count, List<SkippedLine> samples)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, META_SKIPPED_LINES);
            ps.setString(2, String.valueOf(count));
            ps.executeUpdate();
            ps.setString(1, META_SKIPPED_SAMPLES);
            ps.setString(2, serializeSkippedSamples(samples));
            ps.executeUpdate();
        }
    }

    private static String serializeSkippedSamples(List<SkippedLine> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SkippedLine s = samples.get(i);
            sb.append("{\"file_id\":").append(s.fileId)
                    .append(",\"line_no\":").append(s.lineNo)
                    .append(",\"preview\":").append(jsonString(s.preview)).append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<SkippedLine> deserializeSkippedSamples(String json) {
        List<SkippedLine> out = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0) {
                break;
            }
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = json.substring(objStart + 1, objEnd);
            long fileId = extractJsonLong(obj, "file_id");
            long lineNo = extractJsonLong(obj, "line_no");
            String preview = extractJsonString(obj, "preview");
            out.add(new SkippedLine(fileId, lineNo, preview));
            i = objEnd + 1;
        }
        return out;
    }

    private static long extractJsonLong(String obj, String key) {
        String needle = "\"" + key + "\":";
        int idx = obj.indexOf(needle);
        if (idx < 0) {
            return 0L;
        }
        int start = idx + needle.length();
        int end = start;
        while (end < obj.length() && Character.isDigit(obj.charAt(end))) {
            end++;
        }
        return Long.parseLong(obj.substring(start, end));
    }

    private static String extractJsonString(String obj, String key) {
        String needle = "\"" + key + "\":\"";
        int idx = obj.indexOf(needle);
        if (idx < 0) {
            return "";
        }
        int start = idx + needle.length();
        StringBuilder sb = new StringBuilder();
        for (int j = start; j < obj.length(); j++) {
            char c = obj.charAt(j);
            if (c == '\\' && j + 1 < obj.length()) {
                char next = obj.charAt(j + 1);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 'r') {
                    sb.append('\r');
                } else if (next == 't') {
                    sb.append('\t');
                } else if (next == '\\' || next == '"') {
                    sb.append(next);
                } else {
                    sb.append(next);
                }
                j++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void parseFileInto(long fileId, Path path, BlockingQueue<List<Row>> queue,
            boolean collectBody, AtomicLong skippedCounter, List<SkippedLine> skippedSamples)
            throws IOException, InterruptedException {
        try (InputStream raw = Files.newInputStream(path);
             InputStream in = new BufferedInputStream(raw, 1 << 16);
             ByteLineReader reader = new ByteLineReader(in)) {
            long lineNo = 0;
            SqlBlock pending = null;
            List<Row> batch = new ArrayList<>(BATCH_SIZE);

            while (reader.next()) {
                lineNo++;
                if (reader.isBlankLine()) {
                    continue;
                }
                boolean header = LogParser.looksLikeHeader(reader.lineBuf, reader.lineLen);
                LogParser.ParsedLine parsed =
                        header ? LogParser.parse(reader.lineBuf, reader.lineLen) : null;

                if (parsed != null && MyBatisBlockParser.isPreparingLine(parsed)) {
                    if (pending != null) {
                        batch = flushBlock(pending, reader.lineStart, batch, queue, collectBody);
                        pending = null;
                    }
                    pending = MyBatisBlockParser.startBlock(fileId, lineNo, reader.lineStart, parsed);
                    if (collectBody) {
                        pending.bodyBuf = new StringBuilder();
                        pending.bodyBuf.append(new String(
                                reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
                    }
                } else if (pending != null && parsed != null
                        && MyBatisBlockParser.isBlockContinuation(parsed, pending.mapper, pending.thread)) {
                    MyBatisBlockParser.mergeLine(pending, parsed);
                    if (collectBody && pending.bodyBuf != null) {
                        pending.bodyBuf.append(new String(
                                reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
                    }
                    if (MyBatisBlockParser.isBlockEnd(parsed)) {
                        batch = flushBlock(pending, reader.position(), batch, queue, collectBody);
                        pending = null;
                    }
                } else if (pending != null && parsed != null) {
                    batch = flushBlock(pending, reader.lineStart, batch, queue, collectBody);
                    pending = null;
                } else if (parsed == null && header) {
                    skippedCounter.incrementAndGet();
                    if (skippedSamples.size() < MAX_SKIPPED_SAMPLES) {
                        skippedSamples.add(new SkippedLine(fileId, lineNo,
                                previewLine(reader.lineBuf, reader.lineLen)));
                    }
                }
            }

            if (pending != null) {
                batch = flushBlock(pending, reader.position(), batch, queue, collectBody);
            }
            if (!batch.isEmpty()) {
                queue.put(batch);
            }
        }
    }

    private static List<Row> flushBlock(SqlBlock block, long endOffset, List<Row> batch,
            BlockingQueue<List<Row>> queue, boolean collectBody) throws InterruptedException {
        MyBatisBlockParser.finalizeBlock(block, endOffset);
        Row row = toRow(block);
        if (collectBody && block.bodyBuf != null) {
            row.bodyBuf = block.bodyBuf;
        }
        batch.add(row);
        if (batch.size() >= BATCH_SIZE) {
            queue.put(batch);
            return new ArrayList<>(BATCH_SIZE);
        }
        return batch;
    }

    private static Row toRow(SqlBlock block) {
        Row row = new Row();
        row.fileId = block.fileId;
        row.lineNo = block.lineNo;
        row.byteOffset = block.byteOffset;
        row.endByteOffset = block.endByteOffset;
        row.tsMillis = block.tsMillis;
        row.tsEndMillis = block.tsEndMillis;
        row.mapper = block.mapper;
        row.sqlType = block.sqlType;
        row.sqlText = block.sqlText;
        row.parameters = block.parameters;
        row.rowCount = block.rowCount;
        row.elapsedMs = block.elapsedMs;
        row.thread = block.thread;
        row.level = block.level;
        return row;
    }

    private static String previewLine(byte[] buf, int len) {
        int end = len;
        while (end > 0 && (buf[end - 1] == '\n' || buf[end - 1] == '\r')) {
            end--;
        }
        String trimmed = new String(buf, 0, end, StandardCharsets.UTF_8);
        if (trimmed.length() <= PREVIEW_MAX_LEN) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_MAX_LEN - 3) + "...";
    }

    private static <T> void putUninterruptibly(BlockingQueue<T> queue, T item) {
        boolean interrupted = false;
        while (true) {
            try {
                queue.put(item);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static String readEntryRaw(Path path, long start, long end) throws IOException {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "r")) {
            file.seek(start);
            if (end <= start) {
                String line = file.readLine();
                return line == null ? "" : stripEol(new String(
                        line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
            int size = (int) Math.min(end - start, Integer.MAX_VALUE);
            byte[] buf = new byte[size];
            file.readFully(buf);
            return stripEol(new String(buf, StandardCharsets.UTF_8));
        }
    }

    private static String stripEol(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static final String SELECT_BASE =
            "SELECT e.id, e.file_id, e.line_no, e.byte_offset, e.end_byte_offset, "
                    + "e.ts_millis, e.ts_end_millis, e.mapper, e.sql_type, e.sql_text, "
                    + "e.parameters, e.row_count, e.elapsed_ms, e.thread, e.level, f.path "
                    + "FROM entries e JOIN files f ON e.file_id = f.id ";

    static EntryRow rowFrom(ResultSet rs) throws SQLException {
        EntryRow e = new EntryRow();
        e.id = rs.getLong(1);
        e.fileId = rs.getLong(2);
        e.lineNo = rs.getLong(3);
        e.byteOffset = rs.getLong(4);
        e.endByteOffset = rs.getLong(5);
        e.tsMillis = rs.getLong(6);
        e.tsEndMillis = rs.getLong(7);
        e.mapper = rs.getString(8);
        e.sqlType = rs.getString(9);
        e.sqlText = rs.getString(10);
        e.parameters = rs.getString(11);
        int rc = rs.getInt(12);
        e.rowCount = rs.wasNull() ? null : rc;
        int em = rs.getInt(13);
        e.elapsedMs = rs.wasNull() ? null : em;
        e.thread = rs.getString(14);
        e.level = rs.getString(15);
        e.source = rs.getString(16);
        return e;
    }

    public static EntryRow findEntry(Connection conn, String source, long lineNo, String timestampIso)
            throws SQLException {
        if (timestampIso != null && !timestampIso.isEmpty()) {
            long tsMillis = TimeUtil.parseUiDatetime(timestampIso);
            try (PreparedStatement ps = conn.prepareStatement(
                    SELECT_BASE + "WHERE f.path = ? AND e.line_no = ? AND e.ts_millis = ? LIMIT 1")) {
                ps.setString(1, source);
                ps.setLong(2, lineNo);
                ps.setLong(3, tsMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rowFrom(rs) : null;
                }
            }
        }
        return findEntry(conn, source, lineNo);
    }

    public static EntryRow findEntry(Connection conn, String source, long lineNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT_BASE + "WHERE f.path = ? AND e.line_no = ? LIMIT 1")) {
            ps.setString(1, source);
            ps.setLong(2, lineNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowFrom(rs) : null;
            }
        }
    }

    static String selectBase() {
        return SELECT_BASE;
    }
}
