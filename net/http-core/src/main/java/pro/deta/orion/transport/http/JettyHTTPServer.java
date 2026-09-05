package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.DispatcherType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.keymaterial.TlsClientAuthentication;
import pro.deta.orion.keymaterial.TlsMaterialConfiguration;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;
import pro.deta.orion.schema.config.HttpTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Singleton
@Getter
public class JettyHTTPServer  implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    public static final String ROOT_CONTEXT_PATH = "/";
    private static final long STOP_TIMEOUT_MILLIS = 1_000;

    private final HttpTransportConfig httpTransportConfig;
    private final KeyMaterialScope clusterScope;
    private final OrionDesiredState desiredState;
    private final TlsCapability tls;
    private final OrionHttpRouteServlet rootServlet;
    private final OrionAuthorizationFilter authorizationFilter;
    private final AtomicReference<Server> jettyServer = new AtomicReference<>();

    @Inject
    public JettyHTTPServer(
            OrionConfiguration orionConfiguration,
            OrionDesiredState desiredState,
            TlsCapability tls,
            OrionHttpRouteServlet rootServlet,
            OrionAuthorizationFilter authorizationFilter) {
        this.httpTransportConfig = orionConfiguration.getTransport().getHttp();
        this.clusterScope = KeyMaterialScope.cluster(
                orionConfiguration.getBootstrap().getKeyMaterial().getClusterId());
        this.desiredState = desiredState;
        this.tls = tls;
        this.rootServlet = rootServlet;
        this.authorizationFilter = authorizationFilter;
    }

    public void onStart() {
        if (!isEnabled()) {
            return;
        }
        try {
            Server server = getNewServer();
            jettyServer.set(server);
            server.start();
        } catch (Exception e) {
            destroyFailedServer();
            throw new IllegalStateException("Cannot start Jetty HTTP server", e);
        }
        if (httpTransportConfig != null && httpTransportConfig.isEnabled()) {
            log.warn("HTTP Listening on http://{}:{}", httpTransportConfig.getAddress(), boundHttpPort());
        }
        httpsConfiguration().filter(OrionHttpsConfiguration::enabled).ifPresent(https ->
                log.warn("HTTPS Listening on https://{}:{}", https.address(), boundHttpsPort()));
    }

    public boolean isEnabled() {
        return (httpTransportConfig != null && httpTransportConfig.isEnabled())
                || httpsConfiguration().map(OrionHttpsConfiguration::enabled).orElse(false);
    }

    public boolean isRunning() {
        Server server = jettyServer.get();
        return server != null && server.isStarted();
    }

    public int boundHttpPort() {
        return boundPort("http");
    }

    public int boundHttpsPort() {
        return boundPort("https");
    }

    private Server getNewServer() {
        try {
            QueuedThreadPool threadPool = new QueuedThreadPool(10, 2, 120);
            Server server = new Server(threadPool);
            server.setStopTimeout(STOP_TIMEOUT_MILLIS);
            server.setStopAtShutdown(false);
            GzipHandler gzipHandler = new GzipHandler();
            gzipHandler.setIncludedMimeTypes(
                    "text/html",
                    "text/plain",
                    "text/xml",
                    "text/css",
                    "application/json",
                    "application/javascript",
                    "text/javascript");
            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath(ROOT_CONTEXT_PATH);
            context.insertHandler(gzipHandler);

            context.addServlet(new ServletHolder(rootServlet), "/*");
            if (authorizationFilter != null) {
                context.addFilter(
                        new FilterHolder(authorizationFilter),
                        authorizationFilter.filterPath(),
                        EnumSet.of(DispatcherType.REQUEST));
            }
            server.setHandler(context);

            enableHttpIfNeeded(server, httpTransportConfig);

            enableHttpsIfNeeded(server, httpsConfiguration());

            return server;
        } catch (Exception e) {
            log.error("Failed to initialize server", e);
            throw new RuntimeException("Failed to initialize server", e);
        }
    }

    private void enableHttpsIfNeeded(
            Server server,
            Optional<OrionHttpsConfiguration> configured) throws GeneralSecurityException {
        if (configured.isEmpty() || !configured.orElseThrow().enabled()) {
            return;
        }
        OrionHttpsConfiguration https = configured.orElseThrow();
        TlsMaterialConfiguration material = tlsMaterial(https);
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setSslContext(tls.createContext(material));
        sslContextFactory.setWantClientAuth(
                material.clientAuthentication() == TlsClientAuthentication.WANT);
        sslContextFactory.setNeedClientAuth(
                material.clientAuthentication() == TlsClientAuthentication.REQUIRED);

        ServerConnector httpsConnector = new ServerConnector(
                server,
                new SslConnectionFactory(sslContextFactory, "http/1.1"),
                new HttpConnectionFactory(new HttpConfiguration()));
        httpsConnector.setName("https");
        httpsConnector.setHost(https.address());
        httpsConnector.setPort(https.port());
        server.addConnector(httpsConnector);
    }

    private static void enableHttpIfNeeded(Server server, HttpTransportConfig httpTransportConfig) {
        if (httpTransportConfig != null && httpTransportConfig.isEnabled()) {
            ServerConnector httpConnector = new ServerConnector(server);
            httpConnector.setName("http");
            httpConnector.setHost(httpTransportConfig.getAddress());
            httpConnector.setPort(httpTransportConfig.getPort());
            server.addConnector(httpConnector);
        }
    }

    private int boundPort(String name) {
        Server server = jettyServer.get();
        if (server == null) {
            return 0;
        }
        for (Connector connector : server.getConnectors()) {
            if (connector instanceof ServerConnector serverConnector && name.equals(connector.getName())) {
                return serverConnector.getLocalPort();
            }
        }
        return 0;
    }

    private void destroyFailedServer() {
        Server server = jettyServer.getAndSet(null);
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } catch (Exception e) {
            log.warn("Failed to stop Jetty server after startup failure", e);
        }
        try {
            server.destroy();
        } catch (Exception e) {
            log.warn("Failed to destroy Jetty server after startup failure", e);
        }
    }

    public void onStop() {
        Server server = jettyServer.getAndSet(null);
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } catch (TimeoutException e) {
            log.warn("Jetty graceful shutdown timed out after {} ms; destroying server", STOP_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping Jetty server; destroying server", e);
        } catch (Exception e) {
            log.error("Failed to stop Jetty server", e);
        } finally {
            try {
                server.destroy();
            } catch (Exception e) {
                log.warn("Failed to destroy Jetty server", e);
            }
        }
    }

    public URL relativiseHttp(String path) throws MalformedURLException {
        int boundPort = boundHttpPort();
        int port = boundPort > 0 ? boundPort : httpTransportConfig.getPort();
        return new URL("http://%s:%d%s".formatted(httpTransportConfig.getAddress(), port, path));
    }

    public URL relativiseHttps(String path) throws MalformedURLException {
        OrionHttpsConfiguration https = httpsConfiguration()
                .orElseThrow(() -> new IllegalStateException("HTTPS desired state is not configured"));
        int boundPort = boundHttpsPort();
        int port = boundPort > 0 ? boundPort : https.port();
        return new URL("https://%s:%d%s".formatted(https.address(), port, path));
    }

    private Optional<OrionHttpsConfiguration> httpsConfiguration() {
        return desiredState.current().document().system().https();
    }

    private TlsMaterialConfiguration tlsMaterial(OrionHttpsConfiguration https) {
        KeyMaterialDescriptor identity = descriptor(
                https.identity().orElseThrow(
                        () -> new IllegalStateException("Enabled HTTPS requires identity material")),
                KeyMaterialPurpose.TLS_IDENTITY);
        Optional<TrustedCertificateDescriptor> issuer = https.serverIssuerTrustAnchor()
                .map(this::trustedCertificate);
        List<TrustedCertificateDescriptor> clientRoots = new ArrayList<>();
        for (OrionMaterialReference root : https.clientTrustAnchors()) {
            clientRoots.add(trustedCertificate(root));
        }
        return new TlsMaterialConfiguration(
                identity,
                issuer,
                clientRoots,
                TlsClientAuthentication.valueOf(https.clientAuthentication().name()));
    }

    private KeyMaterialDescriptor descriptor(
            OrionMaterialReference reference,
            KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(reference.alias()),
                purpose,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(reference.version()),
                clusterScope);
    }

    private TrustedCertificateDescriptor trustedCertificate(OrionMaterialReference reference) {
        return new TrustedCertificateDescriptor(
                new KeyMaterialAlias(reference.alias()),
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(reference.version()),
                clusterScope);
    }
}
