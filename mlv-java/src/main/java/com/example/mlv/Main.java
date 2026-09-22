package com.example.mlv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8767;
        String dir = null;
        String formatId = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--dir":
                    dir = requireValue(args, ++i, "--dir");
                    break;
                case "--host":
                    host = requireValue(args, ++i, "--host");
                    break;
                case "--port":
                    try {
                        port = Integer.parseInt(requireValue(args, ++i, "--port"));
                        if (port <= 0 || port > 65535) {
                            System.err.println("--port は 1〜65535 の整数を指定してください");
                            System.exit(2);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("--port には整数を指定してください");
                        System.exit(2);
                    }
                    break;
                case "--format": {
                    String value = requireValue(args, ++i, "--format");
                    // 利用者定義の書式も指定できるようにするため、ここでは id を覚えるだけ。
                    // 解決は書式ファイルを読んでから行う。
                    formatId = "auto".equals(value) ? null : value;
                    break;
                }
                case "-h":
                case "--help":
                    printUsage();
                    return;
                default:
                    System.err.println("不明な引数: " + arg);
                    printUsage();
                    System.exit(2);
            }
        }

        Path logRoot = null;
        List<Path> paths = Collections.emptyList();
        if (dir != null) {
            logRoot = PathUtil.resolve(dir);
            if (!Files.isDirectory(logRoot)) {
                System.err.println("ディレクトリが見つかりません: " + logRoot);
                System.exit(1);
            }
            try {
                paths = Discovery.findLogFiles(logRoot);
            } catch (IOException e) {
                System.err.println("ログ探索に失敗しました: " + e.getMessage());
                System.exit(1);
            }
        }

        LogFormatSpec logFormat = null;
        if (formatId != null) {
            List<CustomLogFormat> customs = Collections.emptyList();
            LogFormatStore store = new LogFormatStore(LogFormatStore.defaultFile());
            try {
                customs = store.load();
            } catch (IOException e) {
                System.err.println("書式ファイルを読めません: " + e.getMessage());
                System.exit(1);
            }
            logFormat = LogFormatSpec.byId(formatId, customs);
            if (logFormat == null) {
                System.err.println("不明なログ書式: " + formatId);
                printUsage();
                System.exit(2);
            }
        }

        LogServer server = new LogServer(logRoot, paths, logFormat);
        try {
            server.start(host, port);
        } catch (IOException e) {
            System.err.println("サーバ起動に失敗しました: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String requireValue(String[] args, int index, String name) {
        if (index >= args.length) {
            System.err.println(name + " の値を指定してください");
            printUsage();
            System.exit(2);
            return "";
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("MyBatis Log Viewer");
        System.out.println("  java -jar mlv-java.jar [--dir <ログディレクトリ>] [--host <host>] "
                + "[--port <port>] [--format <id>]");
        System.out.println("  デフォルト: http://127.0.0.1:8767");
        StringBuilder ids = new StringBuilder();
        for (LogFormat f : LogFormat.values()) {
            ids.append(" / ").append(f.id());
        }
        System.out.println("  --format ログ書式を固定する（省略時は先頭ファイルから自動判定）");
        System.out.println("          auto" + ids);
        System.out.println("          " + LogFormatStore.FILE_NAME
                + " に書いた利用者定義の書式の id も指定できる");
    }
}
