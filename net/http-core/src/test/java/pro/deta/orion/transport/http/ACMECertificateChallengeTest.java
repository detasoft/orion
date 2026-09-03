package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.config.HttpTransportConfig;
import pro.deta.orion.schema.config.HttpsTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.util.NetworkUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class ACMECertificateChallengeTest {
    @Test
    public void testChallengeHttp01() throws Exception {
        try (AcmeHttpTestServer server = startHttp()) {
            server.challengeService().registerChallenge("test-token", "test-authorization");

            HttpURLConnection connection = get(server.challengeUrl("test-token"));
            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(new String(connection.getInputStream().readAllBytes())).isEqualTo("test-authorization");
        }
    }

    private static AcmeHttpTestServer startHttp() throws IOException {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", NetworkUtils.findAvailablePort()));

        HttpsTransportConfig httpsTransportConfig = new HttpsTransportConfig(
                "localhost",
                NetworkUtils.findAvailablePort());
        httpsTransportConfig.setEnabled(false);
        transports.setHttps(httpsTransportConfig);

        orionConfiguration.setTransport(transports);

        AcmeHttpChallengeService challengeService = new AcmeHttpChallengeService();
        Set<OrionHttpRoute> routes = new LinkedHashSet<>();
        routes.add(new AcmeHttpChallengeRoute(challengeService));
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(routes),
                new OrionHttpResponseWriter(new ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, rootServlet);
        server.onStart();
        return new AcmeHttpTestServer(server, challengeService);
    }

    private record AcmeHttpTestServer(
            JettyHTTPServer server,
            AcmeHttpChallengeService challengeService) implements AutoCloseable {
        private URL challengeUrl(String token) throws IOException {
            return server.relativiseHttp(AcmeHttpChallengeRoute.CHALLENGE_PREFIX + token);
        }

        @Override
        public void close() {
            server.onStop();
        }
    }

    @Test
    public void startHttpWithGeneratedCertificate() throws Exception {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", NetworkUtils.findAvailablePort()));
        transports.setHttps(new HttpsTransportConfig("localhost", NetworkUtils.findAvailablePort()));
        orionConfiguration.setTransport(transports);

        Set<OrionHttpRoute> routes = new LinkedHashSet<>();
        routes.add(new OkRoute());
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(routes),
                new OrionHttpResponseWriter(new ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, rootServlet);
        server.onStart();

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    null,
                    new TrustManager[]{TrustAllX509TrustManager.INSTANCE},
                    new java.security.SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            HttpURLConnection connection = get(server.relativiseHttp("/ok"));
            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);

            HttpsURLConnection httpsConnection = (HttpsURLConnection) get(server.relativiseHttps("/ok"));
            httpsConnection.setSSLSocketFactory(sslSocketFactory);
            assertThat(httpsConnection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
        } finally {
            server.onStop();
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

    private static HttpURLConnection get(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        return connection;
    }

}
