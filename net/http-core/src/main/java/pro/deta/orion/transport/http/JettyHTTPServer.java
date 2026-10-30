package pro.deta.orion.transport.http;

import pro.deta.orion.config.OrionDocumentRevision;
import java.util.Set;

import jakarta.inject.Inject;
import pro.deta.orion.agent.server.AgentSessionServer;
import jakarta.inject.Singleton;
import jakarta.servlet.DispatcherType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.TlsClientAuthentication;
import pro.deta.orion.keymaterial.TlsMaterialConfiguration;
import pro.deta.orion.bootstrap.config.HttpTransportConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.keymaterial.KeyMaterialId;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
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
    private final OrionConfigurationEditor editor;
    private final OrionKeyMaterial keyMaterial;
    private final AgentSessionServer agentServer;
    private final OrionHttpRouteServlet rootServlet;
    private final OrionAuthorizationFilter authorizationFilter;
    private final AtomicReference<Server> jettyServer = new AtomicReference<>();
    @Getter(lombok.AccessLevel.NONE)
    private volatile ActiveTls activeTls;
    private volatile String certificateActivationError = "";

    @Inject
    public JettyHTTPServer(
            BootstrapConfiguration orionConfiguration,
            OrionConfigurationEditor editor,
            OrionKeyMaterial keyMaterial,
            OrionHttpRouteServlet rootServlet,
            OrionAuthorizationFilter authorizationFilter,
            AgentSessionServer agentServer) {
        this.httpTransportConfig = orionConfiguration.getTransport().getHttp();
        this.editor = editor;
        this.keyMaterial = keyMaterial;
        this.rootServlet = rootServlet;
        this.authorizationFilter = authorizationFilter;
        this.agentServer = agentServer;
    }

    public void onStart() {
        if (!isEnabled()) {
            return;
        }
        try {
            Server server = getNewServer();
            jettyServer.set(server);
            server.start();
            certificateActivationError = "";
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

    public String certificateActivationError() {
        return certificateActivationError;
    }

    boolean usesKeyMaterialResources(Set<String> resources) {
        ActiveTls current = activeTls;
        if (current == null) return false;
        return containsResource(resources, current.material().identity().alias());
    }

    private static boolean containsResource(Set<String> resources, String alias) {
        for (String resource : resources) {
            if (resource.equalsIgnoreCase(alias)) return true;
        }
        return false;
    }

    void reloadHttpsCertificate() {
        ActiveTls current = activeTls;
        if (current == null || !isRunning()) return;
        try {
            Optional<OrionHttpsConfiguration> configured = httpsConfiguration();
            if (configured.isEmpty() || !configured.orElseThrow().enabled()) return;
            TlsMaterialConfiguration selected = tlsMaterial(configured.orElseThrow());
            List<X509Certificate> chain = keyMaterial.read(selected.identity().id()).certificateChain();
            if (selected.equals(current.material()) && chain.equals(current.chain())) {
                certificateActivationError = "";
                return;
            }
            SSLContext replacement = keyMaterial.createTlsContext(selected);
            SSLContext previous = current.factory().getSslContext();
            try {
                current.factory().reload(factory -> {
                    current.factory().setWantClientAuth(
                            selected.clientAuthentication() == TlsClientAuthentication.WANT);
                    current.factory().setNeedClientAuth(
                            selected.clientAuthentication() == TlsClientAuthentication.REQUIRED);
                    factory.setSslContext(replacement);
                });
            } catch (Exception failure) {
                try {
                    current.factory().reload(factory -> {
                        current.factory().setWantClientAuth(
                                current.material().clientAuthentication() == TlsClientAuthentication.WANT);
                        current.factory().setNeedClientAuth(
                                current.material().clientAuthentication() == TlsClientAuthentication.REQUIRED);
                        factory.setSslContext(previous);
                    });
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            activeTls = new ActiveTls(selected, current.factory(), List.copyOf(chain));
            certificateActivationError = "";
        } catch (Exception failure) {
            certificateActivationError = "Could not activate the saved certificate. Retrying automatically.";
            throw new IllegalStateException("Cannot activate the saved HTTPS certificate", failure);
        }
    }

    void reloadHttpsCertificate(Set<String> resources) {
        if (usesKeyMaterialResources(resources)) reloadHttpsCertificate();
    }

    boolean usesConfiguration(OrionDocumentRevision snapshot) {
        return editor.current().equals(snapshot);
    }

    private record ActiveTls(TlsMaterialConfiguration material, SslContextFactory.Server factory,
                             List<X509Certificate> chain) {}

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

            ServletHolder servletHolder = new ServletHolder(rootServlet);
            servletHolder.setAsyncSupported(true);
            context.addServlet(servletHolder, "/*");
            if (authorizationFilter != null) {
                FilterHolder filterHolder = new FilterHolder(authorizationFilter);
                filterHolder.setAsyncSupported(true);
                context.addFilter(
                        filterHolder,
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
        List<X509Certificate> chain = keyMaterial.read(material.identity().id()).certificateChain();
        sslContextFactory.setSslContext(keyMaterial.createTlsContext(material));
        activeTls = new ActiveTls(material, sslContextFactory, List.copyOf(chain));
        sslContextFactory.setWantClientAuth(
                material.clientAuthentication() == TlsClientAuthentication.WANT);
        sslContextFactory.setNeedClientAuth(
                material.clientAuthentication() == TlsClientAuthentication.REQUIRED);

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory http1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory http2 = new AgentHttp2ConnectionFactory(
                httpConfiguration, () -> agentServer.replicationService());
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory(http2.getProtocol(), "http/1.1");
        alpn.setDefaultProtocol(http1.getProtocol());
        ServerConnector httpsConnector = new ServerConnector(
                server,
                new SslConnectionFactory(sslContextFactory, alpn.getProtocol()),
                alpn,
                http2,
                http1);
        httpsConnector.setName("https");
        httpsConnector.setHost(https.address());
        httpsConnector.setPort(https.port());
        server.addConnector(httpsConnector);
    }

    private void enableHttpIfNeeded(Server server, HttpTransportConfig httpTransportConfig) {
        if (httpTransportConfig != null && httpTransportConfig.isEnabled()) {
            HttpConfiguration configuration = new HttpConfiguration();
            ServerConnector httpConnector = new ServerConnector(server,
                    new HttpConnectionFactory(configuration),
                    AgentHttp2ConnectionFactory.cleartext(configuration, () -> agentServer.replicationService()));
            httpConnector.setName("http");
            httpConnector.setHost(httpTransportConfig.getAddress());
            httpConnector.setPort(httpTransportConfig.getPort());
            httpConnector.setAcceptQueueSize(httpTransportConfig.getBacklog());
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
        activeTls = null;
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
        activeTls = null;
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
                .orElseThrow(() -> new IllegalStateException("HTTPS configuration is not configured"));
        int boundPort = boundHttpsPort();
        int port = boundPort > 0 ? boundPort : https.port();
        return new URL("https://%s:%d%s".formatted(https.address(), port, path));
    }

    private Optional<OrionHttpsConfiguration> httpsConfiguration() {
        return editor.current().result()
                .valueOrFailure("Cannot read configuration document")
                .system().https();
    }

    private TlsMaterialConfiguration tlsMaterial(OrionHttpsConfiguration https) {
        KeyMaterialDescriptor identity = descriptor(
                https.identity().orElseThrow(
                        () -> new IllegalStateException("Enabled HTTPS requires identity material")),
                KeyMaterialPurpose.TLS_IDENTITY);
        Optional<KeyMaterialDescriptor> issuer = https.serverIssuerTrustAnchor()
                .map(this::trustedCertificate);
        List<KeyMaterialDescriptor> clientRoots = new ArrayList<>();
        for (KeyMaterialId root : https.clientTrustAnchors()) {
            clientRoots.add(trustedCertificate(root));
        }
        return new TlsMaterialConfiguration(
                identity,
                issuer,
                clientRoots,
                TlsClientAuthentication.valueOf(https.clientAuthentication().name()));
    }

    private KeyMaterialDescriptor descriptor(
            KeyMaterialId reference,
            KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(
                reference.alias(),
                Set.of(purpose),
                KeyMaterialAlgorithm.RSA,
                reference.version());
    }

    private KeyMaterialDescriptor trustedCertificate(KeyMaterialId reference) {
        return new KeyMaterialDescriptor(
                reference.alias(), Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                KeyMaterialAlgorithm.RSA,
                reference.version());
    }
}
