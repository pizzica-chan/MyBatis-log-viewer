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
}
