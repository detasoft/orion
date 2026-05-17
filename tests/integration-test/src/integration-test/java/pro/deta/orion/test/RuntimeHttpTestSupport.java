package pro.deta.orion.test;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

final class RuntimeHttpTestSupport {
    private RuntimeHttpTestSupport() {
    }

    static OrionConfiguration httpOnlyConfiguration(Path orionRoot) throws IOException {
        return httpOnlyConfiguration(orionRoot, ignored -> {
        });
    }

    static OrionConfiguration httpOnlyConfiguration(Path orionRoot, Consumer<OrionConfiguration> customizer) throws IOException {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(orionRoot.toString());
        configuration.getStorage().setLocation(orionRoot.resolve("repos").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation("local:orion");

        TestPorts.nextBatch().configure(configuration);
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttps().setEnabled(false);
        customizer.accept(configuration);
        return configuration;
    }

    static StartedOrion start(OrionConfiguration orionConfiguration) {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(() -> orionConfiguration)
                .build();
        OrionApplicationLifecycle lifecycle = orionComponent.orionApplicationLifecycle();
        ApplicationState state = lifecycle.runApplication();
        assertThat(state).isEqualTo(ApplicationState.UP);
        lifecycle.waitForStarting();
        return new StartedOrion(orionConfiguration, lifecycle, orionComponent.orionAccessControlService());
    }

    static HttpResponse request(String method, URL url, String authorization) throws IOException {
        return request(method, url, authorization, null, new byte[0]);
    }

    static HttpResponse request(String method, URL url, String authorization, String contentType, byte[] body)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
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

    record HttpResponse(int status, String contentType, String allow, String body) {
    }

    record StartedOrion(
            OrionConfiguration configuration,
            OrionApplicationLifecycle lifecycle,
            OrionAccessControlServiceImpl accessControlService)
            implements AutoCloseable {
        URL httpUrl(String path) throws IOException {
            return new URL(
                    "http",
                    configuration.getTransport().getHttp().getAddress(),
                    configuration.getTransport().getHttp().getPort(),
                    path);
        }

        @Override
        public void close() {
            lifecycle.shutdownApplication();
            lifecycle.waitForShutdown();
        }
    }
}
