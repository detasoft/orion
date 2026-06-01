package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.HttpTransportConfig;
import pro.deta.orion.config.schema.HttpsTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.util.NetworkUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JettyHTTPServerTest {

    @Test
    void testServerWithHttpAndHttps() throws Exception {
        // Configure test ports
        int httpPort = NetworkUtils.findAvailablePort();
        int httpsPort = NetworkUtils.findAvailablePort();

        // Create configuration
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", httpPort));
        transports.setHttps(new HttpsTransportConfig("localhost", httpsPort));
        orionConfiguration.setTransport(transports);

        // Create and start server
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(new OkRoute())),
                new OrionHttpResponseWriter(new com.fasterxml.jackson.databind.ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, rootServlet);
        server.onStart();

        try {
            assertThat(server.isRunning()).isTrue();
            // Configure SSL context to trust our self-signed certificate
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {TrustAllX509TrustManager.INSTANCE}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

            // Test HTTP connection
            URL httpUrl = server.relativiseHttp("/ok");
            HttpURLConnection httpConn = (HttpURLConnection) httpUrl.openConnection();
            assertThat(httpConn.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);

            // Test HTTPS connection
            URL httpsUrl = server.relativiseHttps("/ok");
            HttpsURLConnection httpsConn = (HttpsURLConnection) httpsUrl.openConnection();
            assertThat(httpsConn.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);

        } finally {
            server.onStop();
        }
    }

    @Test
    void failsStartupWhenHttpPortIsAlreadyInUse() throws Exception {
        try (ServerSocket occupiedHttpPort = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            OrionConfiguration orionConfiguration = new OrionConfiguration();
            OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
            transports.setHttp(new HttpTransportConfig("127.0.0.1", occupiedHttpPort.getLocalPort()));
            HttpsTransportConfig https = new HttpsTransportConfig("127.0.0.1", 0);
            https.setEnabled(false);
            transports.setHttps(https);
            orionConfiguration.setTransport(transports);

            OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                    new OrionHttpRouteRegistry(Set.of(new OkRoute())),
                    new OrionHttpResponseWriter(new com.fasterxml.jackson.databind.ObjectMapper()));
            JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, rootServlet);

            assertThatThrownBy(server::onStart)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start Jetty HTTP server");
            assertThat(server.isRunning()).isFalse();
        }
    }

    @Test
    void stopClearsServerReferenceAfterGracefulShutdownTimeout() throws Exception {
        int httpPort = NetworkUtils.findAvailablePort();
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", httpPort));
        HttpsTransportConfig https = new HttpsTransportConfig("localhost", 0);
        https.setEnabled(false);
        transports.setHttps(https);
        orionConfiguration.setTransport(transports);

        BlockingRoute route = new BlockingRoute();
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(route)),
                new OrionHttpResponseWriter(new com.fasterxml.jackson.databind.ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, rootServlet);
        ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
        server.onStart();

        URL blockUrl = server.relativiseHttp("/block");
        Future<?> clientRequest = clientExecutor.submit(() -> request(blockUrl));
        assertThat(route.awaitStarted()).isTrue();

        try {
            server.onStop();

            assertThat(server.getJettyServer().get()).isNull();
            assertThat(server.isRunning()).isFalse();
        } finally {
            route.release();
            clientRequest.cancel(true);
            clientExecutor.shutdownNow();
        }
    }

    private static void request(URL url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setReadTimeout(10_000);
            connection.getResponseCode();
        } catch (IOException ignored) {
        }
    }

    private static final class OkRoute extends AbstractOrionHttpRoute {
        private OkRoute() {
            super("/ok", "GET");
        }

        @Override
        protected OrionHttpResponse doGet(jakarta.servlet.http.HttpServletRequest req) {
            return OrionHttpResponse.text(HttpURLConnection.HTTP_OK, "OK");
        }
    }

    private static final class BlockingRoute extends AbstractOrionHttpRoute {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingRoute() {
            super("/block", "GET");
        }

        @Override
        protected OrionHttpResponse doGet(jakarta.servlet.http.HttpServletRequest req) {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OrionHttpResponse.text(HttpURLConnection.HTTP_OK, "OK");
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }
}
