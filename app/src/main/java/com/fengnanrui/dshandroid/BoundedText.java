package com.fengnanrui.dshandroid;

import java.io.IOException;
import java.io.Reader;

/** Retains a bounded prefix while optionally draining a subprocess pipe to EOF. */
final class BoundedText {
    private BoundedText() {}

    static String read(Reader reader, int limit, boolean drain) throws IOException {
        StringBuilder text = new StringBuilder(Math.min(limit, 8192));
        char[] buffer = new char[4096];
        int count;
        while ((drain || text.length() < limit) && (count = reader.read(buffer, 0,
                drain ? buffer.length : Math.min(buffer.length, limit - text.length()))) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            text.append(buffer, 0, Math.min(count, limit - text.length()));
        }
        return text.toString();
    }
}
