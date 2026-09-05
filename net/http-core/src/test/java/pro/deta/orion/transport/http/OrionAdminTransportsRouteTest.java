package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.schema.config.HttpTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.SshTransportConfig;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;

import java.net.URI;
import java.util.List;
import java.util.Optional;
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
        transport.setSsh(new SshTransportConfig("0.0.0.0", 2222));
        transport.setGit(new GitTransportConfig("0.0.0.0", 9418));
        transport.getHttp().setPublicUrl("https://git.example");
        transport.getSsh().setPublicUrl("ssh://git.example:2222");
        OrionAdminTransportsRoute.TransportDescriptor http =
                OrionAdminTransportsRoute.descriptor(transport.getHttp(), "http", 9080);
        OrionAdminTransportsRoute.TransportDescriptor httpsDescriptor =
                OrionAdminTransportsRoute.descriptor(new OrionHttpsConfiguration(
                        false,
                        "0.0.0.0",
                        9443,
                        null,
                        Optional.empty(),
                        Optional.empty(),
                        OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                        List.of(),
                        Optional.empty()), 9443);
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
        configuration.setTransport(transport);
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of()),
                new OrionHttpResponseWriter(new ObjectMapper()));
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty()),
                List.of()), Optional.of("test-revision"));
        JettyHTTPServer server = new JettyHTTPServer(
                configuration, desiredState, TlsCapability.unavailable(), servlet, null);
        server.onStart();

        try {
            OrionAdminTransportsRoute route = new OrionAdminTransportsRoute(
                    configuration,
                    desiredState,
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
    void reportsDesiredStateHttpsWhenBootstrapTransportsAreAbsent() {
        OrionConfiguration configuration = new OrionConfiguration();
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(
                        new AccessControl(),
                        Optional.of(new OrionHttpsConfiguration(
                                true,
                                "127.0.0.1",
                                9443,
                                URI.create("https://git.example"),
                                Optional.of(new OrionMaterialReference("https-identity", 1)),
                                Optional.empty(),
                                OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                                List.of(),
                                Optional.empty()))),
                List.of()), Optional.of("test-revision"));
        JettyHTTPServer server = new JettyHTTPServer(
                configuration, desiredState, TlsCapability.unavailable(), null, null) {
            @Override
            public int boundHttpsPort() {
                return 9443;
            }
        };
        configuration.setTransport(null);
        OrionAdminTransportsRoute route = new OrionAdminTransportsRoute(
                configuration, desiredState, () -> server, null, null);

        OrionAdminTransportsRoute.AdminTransportsResponse response =
                (OrionAdminTransportsRoute.AdminTransportsResponse) route.doGet(null).body();

        assertTrue(response.https().enabled());
        assertEquals("https://git.example", response.https().url());
        assertFalse(response.http().enabled());
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
