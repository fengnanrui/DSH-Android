package com.fengnanrui.dshandroid;

import org.junit.Test;
import java.io.StringReader;
import static org.junit.Assert.assertEquals;

public final class BoundedTextTest {
    @Test public void drainsHugeSingleLineWithoutRetainingIt() throws Exception {
        StringReader input = new StringReader("a".repeat(1024 * 1024));
        assertEquals("a".repeat(128), BoundedText.read(input, 128, true));
        assertEquals(-1, input.read());
    }

    @Test public void boundsNetworkReadsWithoutWaitingForEof() throws Exception {
        StringReader input = new StringReader("abcdef");
        assertEquals("abc", BoundedText.read(input, 3, false));
        assertEquals('d', input.read());
    }
}
