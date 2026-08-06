package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.config.schema.GitPackfileUriConfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class OrionGitPackfileUriBaseResolver {
    private static final String ROUTE_ROOT = "/r";

    private OrionGitPackfileUriBaseResolver() {
    }

    static Optional<String> resolve(
            HttpServletRequest request,
            GitPackfileUriConfig config) {
        Objects.requireNonNull(request, "request");
        if (config == null || !config.isConfigured()) {
            return Optional.empty();
        }
        if (!config.isAuto()) {
            return Optional.of(trimTrailingSlash(config.getBaseUri()));
        }
        RequestOrigin origin = directOrigin(request);
        if (trustedProxy(request, config)) {
            origin = forwardedOrigin(request).orElse(origin);
        }
        return Optional.of(origin.uri() + ROUTE_ROOT);
    }

    private static RequestOrigin directOrigin(HttpServletRequest request) {
        String scheme = request.isSecure()
                ? "https"
                : defaultString(request.getScheme(), "http");
        String host = hostHeader(request.getHeader("Host"));
        if (host == null) {
            host = hostAndPort(
                    request.getServerName(),
                    request.getServerPort(),
                    scheme);
        }
        return new RequestOrigin(scheme, host);
    }

    private static Optional<RequestOrigin> forwardedOrigin(
            HttpServletRequest request) {
        Optional<RequestOrigin> forwarded = forwardedHeaderOrigin(
                request.getHeader("Forwarded"));
        if (forwarded.isPresent()) {
            return forwarded;
        }
        String protocol = firstHeaderValue(
                request.getHeader("X-Forwarded-Proto"));
        String host = firstHeaderValue(
                request.getHeader("X-Forwarded-Host"));
        String port = firstHeaderValue(
                request.getHeader("X-Forwarded-Port"));
        if (!validScheme(protocol) || !validHost(host)) {
            return Optional.empty();
        }
        if (port != null
                && !hostContainsPort(host)
                && validPort(port)
                && !defaultPort(protocol, Integer.parseInt(port))) {
            host = host + ":" + port;
        }
        return Optional.of(new RequestOrigin(protocol, host));
    }

    private static Optional<RequestOrigin> forwardedHeaderOrigin(
            String header) {
        String value = firstHeaderValue(header);
        if (value == null) {
            return Optional.empty();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : value.split(";")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = part.substring(0, separator)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            String parameterValue = unquote(
                    part.substring(separator + 1).trim());
            parameters.put(name, parameterValue);
        }
        String protocol = parameters.get("proto");
        String host = parameters.get("host");
        if (!validScheme(protocol) || !validHost(host)) {
            return Optional.empty();
        }
        return Optional.of(new RequestOrigin(protocol, host));
    }

    private static boolean trustedProxy(
            HttpServletRequest request,
            GitPackfileUriConfig config) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress != null
                && config.getTrustedProxyAddresses() != null
                && config.getTrustedProxyAddresses().contains(remoteAddress);
    }

    private static String hostAndPort(
            String serverName,
            int serverPort,
            String scheme) {
        String host = defaultString(serverName, "localhost");
        if (serverPort <= 0 || defaultPort(scheme, serverPort)) {
            return host;
        }
        return host + ":" + serverPort;
    }

    private static boolean defaultPort(String scheme, int port) {
        return "http".equalsIgnoreCase(scheme) && port == 80
                || "https".equalsIgnoreCase(scheme) && port == 443;
    }

    private static String hostHeader(String host) {
        String value = firstHeaderValue(host);
        return validHost(value) ? value : null;
    }

    private static String firstHeaderValue(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.split(",", 2)[0].trim();
        return value.isEmpty() ? null : value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && value.startsWith("\"")
                && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean validScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);
    }

    private static boolean validHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if (character <= 0x20
                    || character >= 0x7f
                    || character == '/'
                    || character == '\\') {
                return false;
            }
        }
        return true;
    }

    private static boolean validPort(String port) {
        if (port == null || port.isBlank()) {
            return false;
        }
        int value = 0;
        for (int index = 0; index < port.length(); index++) {
            char digit = port.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
            value = value * 10 + digit - '0';
            if (value > 65535) {
                return false;
            }
        }
        return value > 0;
    }

    private static boolean hostContainsPort(String host) {
        int colon = host.lastIndexOf(':');
        return colon > 0 && colon < host.length() - 1;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = Objects.requireNonNull(value, "value").trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record RequestOrigin(String scheme, String host) {
        private RequestOrigin {
            if (!validScheme(scheme)) {
                throw new IllegalArgumentException("Invalid HTTP scheme");
            }
            if (!validHost(host)) {
                throw new IllegalArgumentException("Invalid HTTP host");
            }
        }

        private String uri() {
            return scheme.toLowerCase(Locale.ROOT)
                    + "://"
                    + host;
        }
    }
}
