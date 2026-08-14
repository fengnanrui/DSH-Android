package com.fengnanrui.dshandroid;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class EndpointValidator {
    private EndpointValidator() {}

    static URI parse(String raw) throws URISyntaxException {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new URISyntaxException(value, "Server address is empty");
        }
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        URI uri = new URI(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new URISyntaxException(value, "Only HTTP and HTTPS are supported");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new URISyntaxException(value, "Server host is missing");
        }
        if (uri.getUserInfo() != null) {
            throw new URISyntaxException(value, "Credentials must not be embedded in the URL");
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return new URI(scheme, null, uri.getHost(), uri.getPort(), path, uri.getRawQuery(), null);
    }

    static boolean isLoopback(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }

    static boolean isSecureOrLoopback(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || isLoopback(uri);
    }

    static String origin(URI uri) {
        int port = uri.getPort();
        String host = uri.getHost();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        boolean defaultPort = port == -1
                || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()))
                || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()));
        return uri.getScheme() + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
