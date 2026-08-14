package com.fengnanrui.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.URI;
import java.net.URISyntaxException;
import org.junit.Test;

public class EndpointValidatorTest {
    @Test public void addsHttpWhenSchemeIsMissing() throws Exception {
        assertEquals("http://192.168.1.4:3080/", EndpointValidator.parse("192.168.1.4:3080").toString());
    }

    @Test public void preservesPathAndQueryButDropsFragment() throws Exception {
        assertEquals("https://example.com/dsh?x=1", EndpointValidator.parse("https://example.com/dsh?x=1#secret").toString());
    }

    @Test public void rejectsEmbeddedCredentials() {
        try {
            EndpointValidator.parse("https://user:pass@example.com");
            fail("Expected an invalid endpoint");
        } catch (URISyntaxException expected) {
            assertTrue(expected.getMessage().contains("Credentials"));
        }
    }

    @Test public void recognizesLoopbackAndSecureOrigins() throws Exception {
        URI loopback = EndpointValidator.parse("http://127.0.0.1:3080");
        URI lan = EndpointValidator.parse("http://192.168.1.9:3080");
        assertTrue(EndpointValidator.isSecureOrLoopback(loopback));
        assertFalse(EndpointValidator.isSecureOrLoopback(lan));
        assertEquals("http://127.0.0.1:3080", EndpointValidator.origin(loopback));
    }

    @Test public void formatsIpv6AndDefaultPortsAsOrigins() throws Exception {
        assertEquals("http://[::1]:3080", EndpointValidator.origin(
                EndpointValidator.parse("http://[::1]:3080")));
        assertEquals("https://example.com", EndpointValidator.origin(
                EndpointValidator.parse("https://example.com:443")));
    }
}
