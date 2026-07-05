package com.example.mlv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * バイトオフセットを保持しながら 1 行ずつ読み出す高速リーダー。
 */
public final class ByteLineReader implements Closeable {

    private static final int READ_BUF_SIZE = 1 << 20;

    private final InputStream in;
    private final byte[] buf = new byte[READ_BUF_SIZE];
    private int bufLen;
    private int bufPos;
    private long absPos;

    public byte[] lineBuf = new byte[256];
    public int lineLen;
    public long lineStart;

    public ByteLineReader(InputStream in) {
        this.in = in;
    }

    public boolean next() throws IOException {
        if (bufPos >= bufLen && !fill()) {
            return false;
        }
        lineStart = absPos;
        lineLen = 0;
        while (true) {
            if (bufPos >= bufLen && !fill()) {
                return lineLen > 0;
            }
            int nl = indexOfNewline(buf, bufPos, bufLen);
            if (nl >= 0) {
                int chunk = nl - bufPos + 1;
                append(buf, bufPos, chunk);
                bufPos += chunk;
                absPos += chunk;
                return true;
            }
            int chunk = bufLen - bufPos;
            append(buf, bufPos, chunk);
            bufPos += chunk;
            absPos += chunk;
        }
    }

    public long position() {
        return absPos;
    }

    private boolean fill() throws IOException {
        int n = in.read(buf, 0, buf.length);
        if (n <= 0) {
            bufLen = 0;
            bufPos = 0;
            return false;
        }
        bufLen = n;
        bufPos = 0;
        return true;
    }

    private void append(byte[] src, int off, int len) {
        int need = lineLen + len;
        if (need > lineBuf.length) {
            int cap = lineBuf.length;
            while (cap < need) {
                cap <<= 1;
            }
            lineBuf = Arrays.copyOf(lineBuf, cap);
        }
        System.arraycopy(src, off, lineBuf, lineLen, len);
        lineLen = need;
    }

    private static int indexOfNewline(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    public boolean isBlankLine() {
        for (int i = 0; i < lineLen; i++) {
            byte c = lineBuf[i];
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
