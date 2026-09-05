package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.HttpTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
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
        transports.setHttp(new HttpTransportConfig("localhost", 0));
        orionConfiguration.setTransport(transports);

        AcmeHttpChallengeService challengeService = new AcmeHttpChallengeService();
        Set<OrionHttpRoute> routes = new LinkedHashSet<>();
        routes.add(new AcmeHttpChallengeRoute(challengeService));
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(routes),
                new OrionHttpResponseWriter(new ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(
                orionConfiguration,
                desiredStateWithoutHttps(),
                TlsCapability.unavailable(),
                rootServlet,
                null);
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
    public void startsHttpChallengeWithoutHttpsMaterial() throws Exception {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", 0));
        orionConfiguration.setTransport(transports);

        Set<OrionHttpRoute> routes = new LinkedHashSet<>();
        routes.add(new OkRoute());
        OrionHttpRouteServlet rootServlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(routes),
                new OrionHttpResponseWriter(new ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(
                orionConfiguration,
                desiredStateWithoutHttps(),
                TlsCapability.unavailable(),
                rootServlet,
                null);
        server.onStart();

        try {
            HttpURLConnection connection = get(server.relativiseHttp("/ok"));
            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
        } finally {
            server.onStop();
        }
    }

    private static OrionDesiredState desiredStateWithoutHttps() {
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty()),
                List.of()), Optional.of("test-revision"));
        return desiredState;
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
