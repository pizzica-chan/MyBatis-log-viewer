package com.example.mlv;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathUtil {

    private PathUtil() {
    }

    public static String normalizePathStr(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("\\\\?\\")) {
            String rest = path.substring(4);
            if (rest.startsWith("UNC\\")) {
                return "\\\\" + rest.substring(4);
            }
            return rest;
        }
        return path;
    }

    public static String normalizePath(Path path) {
        Path resolved = resolve(path);
        return normalizePathStr(resolved.toString());
    }

    public static Path resolve(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    public static Path resolve(String path) {
        return resolve(Paths.get(path));
    }

    /** 一覧ログファイル列用: 親フォルダ + ファイル名（例: {@code samples\mybatis-sample.log}）。 */
    public static String sourceListLabel(String path) {
        if (path == null || path.isEmpty()) {
            return "-";
        }
        String[] parts = path.split("[/\\\\]");
        int n = 0;
        String[] segs = new String[parts.length];
        for (String part : parts) {
            if (!part.isEmpty()) {
                segs[n++] = part;
            }
        }
        if (n == 0) {
            return path;
        }
        if (n == 1) {
            return segs[0];
        }
        String sep = path.indexOf('\\') >= 0 ? "\\" : "/";
        return segs[n - 2] + sep + segs[n - 1];
    }
}
