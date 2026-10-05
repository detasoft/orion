package pro.deta.orion.test;

import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

final class RuntimeHttpTestSupport {
    private RuntimeHttpTestSupport() {
    }

    static BootstrapConfiguration httpOnlyConfiguration(Path orionRoot) throws IOException {
        return httpOnlyConfiguration(orionRoot, ignored -> {
        });
    }

    static BootstrapConfiguration httpOnlyConfiguration(
            Path orionRoot, Consumer<BootstrapConfiguration> customizer) throws IOException {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().setBaseDir(orionRoot.toString());
        configuration.getStorage().setLocation(orionRoot.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation("local:orion");

        TestPorts.configure(configuration);
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        customizer.accept(configuration);
        return configuration;
    }

    static StartedOrion start(BootstrapConfiguration orionConfiguration) {
        try {
            TestServerIdentityMaterial identity = TestServerIdentityMaterial.open(orionConfiguration);
            OrionComponent orionComponent = TestRuntimeBootstrap
                    .componentBuilder(orionConfiguration, identity.capability(), identity.sshHostKeys())
                    .configurationCipherCapability(identity.material().configurationCipher())
                    .build();
            OrionApplicationLifecycle lifecycle = orionComponent.orionApplicationLifecycle();
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            lifecycle.waitForStarting();
            return new StartedOrion(
                    orionComponent,
                    orionConfiguration,
                    lifecycle,
                    orionComponent.orionAccessControlService(),
                    orionComponent.nativeGitRepositoryProvider(),
                    identity,
                    orionComponent.httpTransport().boundHttpPort(),
                    orionComponent.nativeGitTransport().boundPort(),
                    orionComponent.sshTransport().boundPort());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot open test server identity", failure);
        }
    }

    static HttpResponse request(String method, URL url, String authorization) throws IOException {
        return request(method, url, authorization, null, new byte[0]);
    }

    static void updateConfiguration(StartedOrion orion, byte[] content, String etag) throws IOException {
        OrionDocument document = OrionXml.read(new ByteArrayInputStream(content));
        orion.component().configurationEditor().edit(etag.replace("\"", "")).update(ignored -> document)
                .apply("Update test configuration", UserEmail.EMPTY);
    }

    static String aclEtag(StartedOrion orion, String token) throws IOException {
        return request("GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token)).etag();
    }

    static HttpResponse request(String method, URL url, String authorization, String contentType, byte[] body)
            throws IOException {
        return request(method, url, authorization, contentType, body, null);
    }

    static HttpResponse request(String method, URL url, String authorization, String contentType, byte[] body,
            String ifMatch) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        if (ifMatch != null) {
            connection.setRequestProperty("If-Match", ifMatch);
        }
        if (body.length > 0 || "POST".equals(method) || "PUT".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
        }

        int status = connection.getResponseCode();
        String responseBody = responseBody(connection);
        return new HttpResponse(
                status,
                connection.getContentType(),
                connection.getHeaderField("Allow"),
                connection.getHeaderField("ETag"),
                responseBody);
    }

    private static String responseBody(HttpURLConnection connection) throws IOException {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            if (connection.getResponseCode() >= HttpURLConnection.HTTP_BAD_REQUEST) {
                return "";
            }
            input = connection.getInputStream();
        }
        if (input == null) {
            return "";
        }
        try (InputStream responseInput = input) {
            return new String(responseInput.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    record HttpResponse(int status, String contentType, String allow, String etag, String body) {
    }

    record StartedOrion(
            OrionComponent component,
            BootstrapConfiguration configuration,
            OrionApplicationLifecycle lifecycle,
            OrionAccessControlServiceImpl accessControlService,
            NativeGitRepositoryProvider repositoryProvider,
            TestServerIdentityMaterial identity,
            int httpPort,
            int gitPort,
            int sshPort)
            implements AutoCloseable {
        URL httpUrl(String path) throws IOException {
            return new URL(
                    "http",
                    configuration.getTransport().getHttp().getAddress(),
                    httpPort,
                    path);
        }

        @Override
        public void close() {
            try {
                lifecycle.shutdownApplication();
                lifecycle.waitForShutdown();
            } finally {
                identity.close();
            }
        }
    }
}
