package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Servlet;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.HttpTransportConfig;
import pro.deta.orion.config.schema.HttpsTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.config.schema.SSLKeyStoreConfig;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.util.CertUtils;
import pro.deta.orion.util.OrionUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Singleton
@Getter
public class JettyHTTPServer implements OrionApplicationStageEventListener {
    public static final String ROOT_CONTEXT_PATH = "/";


    private final HttpTransportConfig httpTransportConfig;
    private final HttpsTransportConfig httpsTransportConfig;
    private final DispatcherServlet dispatcherServlet;
    private final AtomicReference<Server> jettyServer = new AtomicReference<>();

    @Inject
    public JettyHTTPServer(OrionConfiguration orionConfiguration, DispatcherServlet dispatcherServlet) {
        this.httpTransportConfig = orionConfiguration.getTransports().getHttp();
        this.httpsTransportConfig = orionConfiguration.getTransports().getHttps();
        this.dispatcherServlet = dispatcherServlet;
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.task(this, ApplicationState.STARTING, OrionLifecycleTasks.HTTP_TRANSPORT_START, this::onStart)
                .after(OrionLifecycleTasks.TRANSPORTS_START);
        registrar.task(this, ApplicationState.STOPPING, OrionLifecycleTasks.HTTP_TRANSPORT_STOP, this::onStop)
                .after(OrionLifecycleTasks.TRANSPORTS_STOP);
    }

    public OrionStageCallResult onStart() {
        jettyServer.set(getNewServer());

        try {
            jettyServer.get().start();
        } catch (Exception e) {
            log.error("Can't start jetty server");
        }
        log.warn("HTTP Listening on {}:{}", httpTransportConfig.getAddress(), httpTransportConfig.getPort());
        log.warn("HTTPS Listening on {}:{}", httpsTransportConfig.getAddress(), httpsTransportConfig.getPort());

        return null;
    }

    private Server getNewServer() {
        try {
            QueuedThreadPool threadPool = new QueuedThreadPool(10, 2, 120);
            Server server = new Server(threadPool);
            GzipHandler gzipHandler = new GzipHandler();
            gzipHandler.setIncludedMimeTypes("text/html", "text/plain", "text/xml", "text/css", "application/json", "text/javascript");
            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath(ROOT_CONTEXT_PATH);
            context.insertHandler(gzipHandler);


            addServletMapStartFrom(context, dispatcherServlet);
            server.setHandler(context);

            enableHttpIfNeeded(server, httpTransportConfig);

            enableHttpsIfNeeded(server, httpsTransportConfig);

            return server;
        } catch (Exception e) {
            log.error("Failed to initialize server", e);
            throw new RuntimeException("Failed to initialize server", e);
        }
    }

    private <T extends Servlet & MapToUrlServlet> void addServletMapStartFrom(ServletContextHandler context, T servlet) {
        context.addServlet(new ServletHolder(dispatcherServlet), pathToMask(servlet.servletPath()));
    }

    private static String pathToMask(String path) {
        if (path.contains("*"))
            return path;
        if (path.endsWith("/"))
            return path + "*";
        else
            return path + "/*";
    }

    private static void enableHttpsIfNeeded(Server server, HttpsTransportConfig httpsTransportConfig) throws Exception {
        if (httpsTransportConfig != null && httpsTransportConfig.isEnabled()) {
            // Create and configure SSL Context Factory
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            KeyStore keyStore;
            SSLKeyStoreConfig sslKeyStore = httpsTransportConfig.getKsystore();
            if (sslKeyStore != null && sslKeyStore.getType() == SSLKeyStoreConfig.SSLKeyStoreType.JKS) {
                keyStore = CertUtils.readKeyWithCertsFromJKS(sslKeyStore.getPath(), sslKeyStore.getKeyStorePassword().toCharArray());
                if (sslKeyStore.getAlias() != null)
                    sslContextFactory.setCertAlias(sslKeyStore.getAlias());
                if (sslKeyStore.getKeyStorePassword() != null)
                    sslContextFactory.setKeyStorePassword(sslKeyStore.getKeyStorePassword());
                sslContextFactory.setKeyManagerPassword(sslKeyStore.getKeyPassword());
            } else {
                CertUtils.PrivateKeyWithCerts pkWithCert;
                if (sslKeyStore != null && sslKeyStore.getType() == SSLKeyStoreConfig.SSLKeyStoreType.PEM) {
                    pkWithCert = CertUtils.readKeyWithCertsFromPEM(sslKeyStore.getPath(), sslKeyStore.getKeyPassword());
                } else {
                    pkWithCert = CertUtils.generateSelfSignedCertificate();
                }
                char[] keyStorePassword = OrionUtils.generatePassword(34);
                String alias = "jetty";
                keyStore = CertUtils.convertToKeyStore(pkWithCert, alias, keyStorePassword);
                sslContextFactory.setCertAlias(alias);
                sslContextFactory.setKeyManagerPassword(new String(keyStorePassword));
            }
            sslContextFactory.setKeyStore(keyStore);

            // Create HTTPS connector
            ServerConnector httpsConnector = new ServerConnector(server,
                    new SslConnectionFactory(sslContextFactory, "http/1.1"),
                    new HttpConnectionFactory(new HttpConfiguration()));
            httpsConnector.setHost(httpsTransportConfig.getAddress());
            httpsConnector.setPort(httpsTransportConfig.getPort());
            server.addConnector(httpsConnector);
        }
    }

    private static void enableHttpIfNeeded(Server server, HttpTransportConfig httpTransportConfig) {
        if (httpTransportConfig != null) {
            ServerConnector httpConnector = new ServerConnector(server);
            httpConnector.setHost(httpTransportConfig.getAddress());
            httpConnector.setPort(httpTransportConfig.getPort());
            server.addConnector(httpConnector);
        }
    }

    public OrionStageCallResult onStop() {
        try {
            Server server = jettyServer.get();
            server.stop();
            server.join();
            server.destroy();
        } catch (Exception e) {
            log.error("Failed to stop Jetty server", e);
        }
        return null;
    }

    public URL relativiseHttp(String path) throws MalformedURLException {
        return new URL("http://%s:%d%s".formatted(httpTransportConfig.getAddress(), httpTransportConfig.getPort(), path));
    }

    public URL relativiseHttps(String path) throws MalformedURLException {
        return new URL("https://%s:%d%s".formatted(httpsTransportConfig.getAddress(), httpsTransportConfig.getPort(), path));
    }
}
