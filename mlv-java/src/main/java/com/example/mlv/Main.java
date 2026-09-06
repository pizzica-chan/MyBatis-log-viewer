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

        LogServer server = new LogServer(logRoot, paths);
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
        System.out.println("  java -jar mlv-java.jar [--dir <ログディレクトリ>] [--host <host>] [--port <port>]");
        System.out.println("  デフォルト: http://127.0.0.1:8767");
    }
}
