package pro.deta.orion.resource.reference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceReferenceResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesCompoundPathFromEnvironment() {
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_ROOT", tempDir.toString()), Map.of());

        Path resolved = resolver.resolve("$ORION_ROOT/orion.xml", Path.class);

        assertThat(resolved).isEqualTo(tempDir.resolve("orion.xml"));
    }

    @Test
    void appliesUnsetAndBlankDefaultRules() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("BLANK", "   "),
                Map.of());

        assertThat(resolver.resolve("${MISSING:-default}", String.class)).isEqualTo("default");
        assertThat(resolver.resolve("${MISSING-default}", String.class)).isEqualTo("default");
        assertThat(resolver.resolve("${BLANK:-default}", String.class)).isEqualTo("default");
        assertThat(resolver.resolve("${BLANK-default}", String.class)).isEqualTo("   ");
    }

    @Test
    void appliesRequiredRules() {
        ResourceReferenceResolver resolver = standardResolver(Map.of("BLANK", ""), Map.of());

        assertThatThrownBy(() -> resolver.resolve("${MISSING?missing config}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("missing config");
        assertThatThrownBy(() -> resolver.resolve("${BLANK:?blank config}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("blank config");
        assertThat(resolver.resolve("${BLANK?allowed}", String.class)).isEqualTo("");
    }

    @Test
    void resolvesContextSelfReference() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of(),
                Map.of("bootstrap.baseDir", "${ORION_ROOT:-orion_root}"));

        Path resolved = resolver.resolve("${bootstrap.baseDir}/orion.xml", Path.class);

        assertThat(resolved).isEqualTo(Path.of("orion_root").resolve("orion.xml"));
    }

    @Test
    void detectsContextCycles() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of(),
                Map.of("bootstrap.baseDir", "${storage.root}", "storage.root", "${bootstrap.baseDir}"));

        assertThatThrownBy(() -> resolver.resolve("${bootstrap.baseDir}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("bootstrap.baseDir")
                .hasMessageContaining("storage.root");
    }

    @Test
    void readsYamlPathFromInlineContent() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("ORION_CONFIG_DATA", "bootstrap:\n  baseDir: /var/lib/orion\n"),
                Map.of());

        String baseDir = resolver.resolve("${yaml:$ORION_CONFIG_DATA/bootstrap/baseDir}", String.class);

        assertThat(baseDir).isEqualTo("/var/lib/orion");
    }

    @Test
    void readsYamlPathFromFilePath() throws Exception {
        Path config = tempDir.resolve("orion.yml");
        Files.writeString(config, "bootstrap:\n  baseDir: /srv/orion\n");
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_CONFIG", config.toString()), Map.of());

        String baseDir = resolver.resolve("${yaml:$ORION_CONFIG/bootstrap/baseDir}", String.class);

        assertThat(baseDir).isEqualTo("/srv/orion");
    }

    @Test
    void readsXmlPathFromInlineContent() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("ORION_ACL_DATA", """
                        <accessControl>
                          <users>
                            <root>admin</root>
                          </users>
                        </accessControl>
                        """),
                Map.of());

        String rootUser = resolver.resolve("${xml:$ORION_ACL_DATA/accessControl/users/root}", String.class);

        assertThat(rootUser).isEqualTo("admin");
    }

    @Test
    void readsTomlPathFromInlineContent() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("ORION_CONFIG_DATA", """
                        [bootstrap]
                        baseDir = "/toml/orion"
                        """),
                Map.of());

        String baseDir = resolver.resolve("${toml:$ORION_CONFIG_DATA/bootstrap/baseDir}", String.class);

        assertThat(baseDir).isEqualTo("/toml/orion");
    }

    @Test
    void failsUnreadablePathInsteadOfParsingItAsInlineYaml() {
        Path missing = tempDir.resolve("missing.yml");
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_CONFIG", missing.toString()), Map.of());

        assertThatThrownBy(() -> resolver.resolve("${yaml:$ORION_CONFIG/bootstrap/baseDir}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("Cannot read document source");
    }

    @Test
    void resolvesFileContentWhenRequested() throws Exception {
        Path config = tempDir.resolve("orion.yml");
        Files.writeString(config, "bootstrap:\n  baseDir: /srv/orion\n");
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_CONFIG", config.toString()), Map.of());

        ResourceContent content = resolver.resolve("$ORION_CONFIG", ResourceContent.class);

        assertThat(content.asUtf8String()).contains("baseDir");
    }

    @Test
    void resolvesS3ObjectLocationFromInterpolatedAddress() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("CONFIG_BUCKET", "orion-configs"),
                Map.of());

        S3ObjectLocation location = resolver.resolve(
                "s3://$CONFIG_BUCKET/prod/orion.yml?region=us-east-1",
                S3ObjectLocation.class);

        assertThat(location.bucket()).isEqualTo("orion-configs");
        assertThat(location.key()).isEqualTo("prod/orion.yml");
        assertThat(location.region()).contains("us-east-1");
    }

    @Test
    void resolvesRemoteGitLocationFromInterpolatedAddress() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("GIT_HOST", "git.example.test"),
                Map.of());

        GitRepositoryLocation location = resolver.resolve(
                "git+https://$GIT_HOST/team/config.git?ref=main",
                GitRepositoryLocation.class);

        assertThat(location.kind()).isEqualTo(GitRepositoryLocation.Kind.HTTPS);
        assertThat(location.location()).contains("git.example.test");
        assertThat(location.ref()).contains("main");
    }

    @Test
    void readsHttpResourceContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] response = "bootstrap:\n  baseDir: /http/orion\n".getBytes(StandardCharsets.UTF_8);
        server.createContext("/orion.yml", exchange -> {
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/orion.yml");
            ResourceReferenceResolver resolver = standardResolver(Map.of(), Map.of());

            ResourceContent content = resolver.resolve(uri.toString(), ResourceContent.class);

            assertThat(content.asUtf8String()).contains("/http/orion");
        } finally {
            server.stop(0);
        }
    }

    private static ResourceReferenceResolver standardResolver(
            Map<String, String> environment,
            Map<String, String> context) {
        return ResourceReferenceResolver.standard(ResourceReferenceScope.builder()
                .environment(environment)
                .context(context)
                .build());
    }
}
