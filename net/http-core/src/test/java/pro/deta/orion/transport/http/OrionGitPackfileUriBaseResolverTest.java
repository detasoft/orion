package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.bootstrap.config.GitPackfileUriConfig;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrionGitPackfileUriBaseResolverTest {
    @ParameterizedTest
    @CsvSource({
            "https, [2001:db8::1], 8443, https://[2001:db8::1]:8443/r",
            "https, [2001:db8::1]:9443, 8443, https://[2001:db8::1]:9443/r",
            "https, [2001:db8::1], 443, https://[2001:db8::1]/r",
            "http, [2001:db8::1], 80, http://[2001:db8::1]/r",
            "http, [2001:db8::1], 8080, http://[2001:db8::1]:8080/r",
            "https, git.example, 8443, https://git.example:8443/r",
            "https, git.example:9443, 8443, https://git.example:9443/r"
    })
    void preservesForwardedPortsAndExplicitHostPortPrecedence(
            String scheme, String host, String port, String expected) {
        GitPackfileUriConfig config = new GitPackfileUriConfig();
        config.setBaseUri("auto");
        config.setTrustedProxyAddresses(List.of("10.0.0.10"));

        assertThat(OrionGitPackfileUriBaseResolver.resolve(
                request(false, "http", "internal", 8080, "internal:8080", Map.of(
                        "X-Forwarded-Proto", scheme,
                        "X-Forwarded-Host", host,
                        "X-Forwarded-Port", port,
                        "Remote-Addr", "10.0.0.10")), config))
                .contains(expected);
    }

    @Test
    void resolvesExplicitBaseUri() {
        GitPackfileUriConfig config = new GitPackfileUriConfig();
        config.setBaseUri("https://git.example/r/");

        assertThat(OrionGitPackfileUriBaseResolver.resolve(
                request(false, "http", "internal", 8080, "ignored", null),
                config))
                .contains("https://git.example/r");
    }

    @Test
    void derivesAutoBaseUriFromDirectRequestHostAndTlsState() {
        GitPackfileUriConfig config = new GitPackfileUriConfig();
        config.setBaseUri("auto");

        assertThat(OrionGitPackfileUriBaseResolver.resolve(
                request(true, "http", "internal", 8443, "git.example", null),
                config))
                .contains("https://git.example/r");
    }

    @Test
    void ignoresForwardedHeadersFromUntrustedRemoteAddress() {
        GitPackfileUriConfig config = new GitPackfileUriConfig();
        config.setBaseUri("auto");
        config.setTrustedProxyAddresses(List.of("10.0.0.10"));

        assertThat(OrionGitPackfileUriBaseResolver.resolve(
                request(
                        false,
                        "http",
                        "internal",
                        8080,
                        "internal:8080",
                        Map.of(
                                "Forwarded",
                                "proto=https;host=git.example",
                                "Remote-Addr",
                                "10.0.0.11")),
                config))
                .contains("http://internal:8080/r");
    }

    @Test
    void usesForwardedHeaderFromTrustedRemoteAddress() {
        GitPackfileUriConfig config = new GitPackfileUriConfig();
        config.setBaseUri("auto");
        config.setTrustedProxyAddresses(List.of("10.0.0.10"));

        assertThat(OrionGitPackfileUriBaseResolver.resolve(
                request(
                        false,
                        "http",
                        "internal",
                        8080,
                        "internal:8080",
                        Map.of(
                                "Forwarded",
                                "for=203.0.113.9;proto=https;"
                                        + "host=git.example",
                                "Remote-Addr",
                                "10.0.0.10")),
                config))
                .contains("https://git.example/r");
    }

    @Test
    void usesXForwardedHeadersFromTrustedRemoteAddress() {
        GitPackfileUriConfig config = new GitPackfileUriConfig();
        config.setBaseUri("auto");
        config.setTrustedProxyAddresses(List.of("10.0.0.10"));

        assertThat(OrionGitPackfileUriBaseResolver.resolve(
                request(
                        false,
                        "http",
                        "internal",
                        8080,
                        "internal:8080",
                        Map.of(
                                "X-Forwarded-Proto",
                                "https",
                                "X-Forwarded-Host",
                                "git.example",
                                "X-Forwarded-Port",
                                "443",
                                "Remote-Addr",
                                "10.0.0.10")),
                config))
                .contains("https://git.example/r");
    }

    private static HttpServletRequest request(
            boolean secure,
            String scheme,
            String serverName,
            int serverPort,
            String host,
            Map<String, String> headers) {
        Map<String, String> safeHeaders = headers == null
                ? Map.of()
                : headers;
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) ->
                switch (invokedMethod.getName()) {
                    case "isSecure" -> secure;
                    case "getScheme" -> scheme;
                    case "getServerName" -> serverName;
                    case "getServerPort" -> serverPort;
                    case "getRemoteAddr" ->
                            safeHeaders.getOrDefault("Remote-Addr", "127.0.0.1");
                    case "getHeader" -> {
                        String name = (String) args[0];
                        if ("Host".equals(name)) {
                            yield host;
                        }
                        yield safeHeaders.get(name);
                    }
                    case "toString" -> "HttpServletRequest";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            invokedMethod.toString());
                });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                handler));
    }
}
