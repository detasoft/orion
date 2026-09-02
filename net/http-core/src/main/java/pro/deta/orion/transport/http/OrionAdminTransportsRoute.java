package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.TransportConfig;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitSshTransportService;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

public class OrionAdminTransportsRoute extends BaseAdminRoute {
    private final OrionConfiguration configuration;
    private final Provider<JettyHTTPServer> httpServer;
    private final Provider<GitSshTransportService> sshServer;
    private final Provider<GitNativeTransportService> nativeGitServer;

    @Inject
    public OrionAdminTransportsRoute(
            OrionConfiguration configuration,
            Provider<JettyHTTPServer> httpServer,
            Provider<GitSshTransportService> sshServer,
            Provider<GitNativeTransportService> nativeGitServer) {
        super(OrionAdminPaths.TRANSPORTS, "GET");
        this.configuration = configuration;
        this.httpServer = httpServer;
        this.sshServer = sshServer;
        this.nativeGitServer = nativeGitServer;
    }

    OrionAdminTransportsRoute(OrionConfiguration configuration) {
        this(configuration, null, null, null);
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        if (transport == null) {
            return OrionHttpResponse.ok(new AdminTransportsResponse(
                    descriptor(null, "http", 0),
                    descriptor(null, "https", 0),
                    descriptor(null, "ssh", 0),
                    descriptor(null, "git", 0)));
        }
        return OrionHttpResponse.ok(new AdminTransportsResponse(
                descriptor(transport.getHttp(), "http", httpPort()),
                descriptor(transport.getHttps(), "https", httpsPort()),
                descriptor(transport.getSsh(), "ssh", sshPort()),
                descriptor(transport.getGit(), "git", nativeGitPort())));
    }

    static TransportDescriptor descriptor(TransportConfig config, String scheme, int boundPort) {
        if (config == null || !config.isEnabled() || boundPort <= 0) {
            return new TransportDescriptor(false, null);
        }
        String configuredPublicUrl = config.getPublicUrl();
        if (configuredPublicUrl != null && !configuredPublicUrl.isBlank()) {
            return new TransportDescriptor(true, validPublicUrl(configuredPublicUrl, scheme));
        }
        String address = config.getAddress();
        if (!LOOPBACK_HOSTS.contains(address)) {
            return new TransportDescriptor(true, null);
        }
        return new TransportDescriptor(true, "%s://%s:%d".formatted(scheme, urlHost(address), boundPort));
    }

    private static String validPublicUrl(String value, String transportScheme) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !validScheme(uri.getScheme(), transportScheme)) {
                return null;
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                return null;
            }
            return uri.toString();
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static boolean validScheme(String scheme, String transportScheme) {
        if ("http".equals(transportScheme) || "https".equals(transportScheme)) {
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        }
        return transportScheme.equalsIgnoreCase(scheme);
    }

    private static String urlHost(String address) {
        if (address.startsWith("[") && address.endsWith("]")) {
            return address;
        }
        return address.contains(":") ? "[" + address + "]" : address;
    }

    private int httpPort() {
        return httpServer == null ? 0 : httpServer.get().boundHttpPort();
    }

    private int httpsPort() {
        return httpServer == null ? 0 : httpServer.get().boundHttpsPort();
    }

    private int sshPort() {
        return sshServer == null ? 0 : sshServer.get().boundPort();
    }

    private int nativeGitPort() {
        return nativeGitServer == null ? 0 : nativeGitServer.get().boundPort();
    }

    public record AdminTransportsResponse(
            TransportDescriptor http,
            TransportDescriptor https,
            TransportDescriptor ssh,
            TransportDescriptor nativeGit) {
    }

    public record TransportDescriptor(boolean enabled, String url) {
    }

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
}
