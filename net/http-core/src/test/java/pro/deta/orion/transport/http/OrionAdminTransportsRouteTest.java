package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.schema.config.HttpTransportConfig;
import pro.deta.orion.schema.config.HttpsTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.SshTransportConfig;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionAdminTransportsRouteTest {
    @Test
    void reportsOnlyRunningTransportEndpoints() {
        OrionConfiguration.AppTransport transport = new OrionConfiguration.AppTransport();
        transport.setHttp(new HttpTransportConfig("0.0.0.0", 9080));
        HttpsTransportConfig httpsConfig = new HttpsTransportConfig("0.0.0.0", 9443);
        httpsConfig.setEnabled(false);
        transport.setHttps(httpsConfig);
        transport.setSsh(new SshTransportConfig("0.0.0.0", 2222));
        transport.setGit(new GitTransportConfig("0.0.0.0", 9418));
        transport.getHttp().setPublicUrl("https://git.example");
        transport.getSsh().setPublicUrl("ssh://git.example:2222");
        OrionAdminTransportsRoute.TransportDescriptor http =
                OrionAdminTransportsRoute.descriptor(transport.getHttp(), "http", 9080);
        OrionAdminTransportsRoute.TransportDescriptor httpsDescriptor =
                OrionAdminTransportsRoute.descriptor(transport.getHttps(), "https", 9443);
        OrionAdminTransportsRoute.TransportDescriptor ssh =
                OrionAdminTransportsRoute.descriptor(transport.getSsh(), "ssh", 2222);
        OrionAdminTransportsRoute.TransportDescriptor nativeGit =
                OrionAdminTransportsRoute.descriptor(transport.getGit(), "git", 9418);

        assertTrue(http.enabled());
        assertEquals("https://git.example", http.url());
        assertFalse(httpsDescriptor.enabled());
        assertNull(httpsDescriptor.url());
        assertTrue(ssh.enabled());
        assertEquals("ssh://git.example:2222", ssh.url());
        assertTrue(nativeGit.enabled());
        assertNull(nativeGit.url());
    }

    @Test
    void hidesAnInvalidConfiguredPublicUrl() {
        HttpTransportConfig config = new HttpTransportConfig("localhost", 8000);
        config.setPublicUrl("not a URL");

        OrionAdminTransportsRoute.TransportDescriptor descriptor =
                OrionAdminTransportsRoute.descriptor(config, "http", 8000);

        assertTrue(descriptor.enabled());
        assertNull(descriptor.url());
    }

    @Test
    void reportsAnEnabledButUnboundTransportAsStopped() {
        HttpTransportConfig config = new HttpTransportConfig("localhost", 0);

        OrionAdminTransportsRoute.TransportDescriptor descriptor =
                OrionAdminTransportsRoute.descriptor(config, "http", 0);

        assertFalse(descriptor.enabled());
        assertNull(descriptor.url());
    }

    @Test
    void reportsThePortBoundByTheRunningHttpServerThroughItsProvider() {
        OrionConfiguration configuration = new OrionConfiguration();
        OrionConfiguration.AppTransport transport = new OrionConfiguration.AppTransport();
        transport.setHttp(new HttpTransportConfig("127.0.0.1", 0));
        HttpsTransportConfig https = new HttpsTransportConfig("127.0.0.1", 0);
        https.setEnabled(false);
        transport.setHttps(https);
        configuration.setTransport(transport);
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of()),
                new OrionHttpResponseWriter(new ObjectMapper()));
        JettyHTTPServer server = new JettyHTTPServer(configuration, servlet);
        server.onStart();

        try {
            OrionAdminTransportsRoute route = new OrionAdminTransportsRoute(
                    configuration,
                    () -> server,
                    null,
                    null);
            OrionAdminTransportsRoute.AdminTransportsResponse response =
                    (OrionAdminTransportsRoute.AdminTransportsResponse) route.doGet(null).body();

            assertTrue(response.http().enabled());
            assertEquals(
                    "http://127.0.0.1:" + server.boundHttpPort(),
                    response.http().url());
            assertTrue(server.boundHttpPort() > 0);
        } finally {
            server.onStop();
        }
    }

    @Test
    void bracketsAnIpv6LoopbackAddressInGeneratedUrls() {
        HttpTransportConfig config = new HttpTransportConfig("::1", 0);

        OrionAdminTransportsRoute.TransportDescriptor descriptor =
                OrionAdminTransportsRoute.descriptor(config, "http", 38123);

        assertEquals("http://[::1]:38123", descriptor.url());
    }

    @Test
    void rejectsPublicUrlsWithAPathBecauseRepositoryPathsAreServerRelative() {
        HttpTransportConfig config = new HttpTransportConfig("localhost", 8000);
        config.setPublicUrl("https://git.example/orion");

        OrionAdminTransportsRoute.TransportDescriptor descriptor =
                OrionAdminTransportsRoute.descriptor(config, "http", 8000);

        assertTrue(descriptor.enabled());
        assertNull(descriptor.url());
    }
}
