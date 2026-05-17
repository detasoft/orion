package pro.deta.orion.resource.reference.resolver;

import org.junit.jupiter.api.Test;
import pro.deta.orion.resource.reference.ResourceContent;
import pro.deta.orion.resource.reference.ResourceReferenceResolutionException;
import pro.deta.orion.resource.reference.ResourceReferenceResolver;
import pro.deta.orion.resource.reference.ResourceReferenceScope;
import pro.deta.orion.resource.reference.ResourceResolverRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceReferenceCapabilityResolverTest {
    @Test
    void resolvesGitRepositoryLocationFromRegisteredResolver() {
        ResourceReferenceResolver resolver = resolverWith(
                new GitRepositoryLocationResolver(),
                Map.of("GIT_HOST", "git.example.test"));

        GitRepositoryLocation location = resolver.resolve(
                "git+https://$GIT_HOST/team/config.git?ref=main",
                GitRepositoryLocation.class);

        assertThat(location.kind()).isEqualTo(GitRepositoryLocation.Kind.HTTPS);
        assertThat(location.location()).isEqualTo("https://git.example.test/team/config.git");
        assertThat(location.ref()).contains("main");
    }

    @Test
    void resolvesS3ObjectLocationFromRegisteredResolver() {
        ResourceReferenceResolver resolver = resolverWith(
                new S3ObjectLocationResolver(),
                Map.of("CONFIG_BUCKET", "orion-configs"));

        S3ObjectLocation location = resolver.resolve(
                "s3://$CONFIG_BUCKET/prod/orion.yml?region=us-east-1",
                S3ObjectLocation.class);

        assertThat(location.bucket()).isEqualTo("orion-configs");
        assertThat(location.key()).isEqualTo("prod/orion.yml");
        assertThat(location.region()).contains("us-east-1");
    }

    @Test
    void failsS3LocationWithoutBucket() {
        ResourceReferenceResolver resolver = resolverWith(new S3ObjectLocationResolver(), Map.of());

        assertThatThrownBy(() -> resolver.resolve("s3://", S3ObjectLocation.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("must contain a bucket");
    }

    @Test
    void resolvesGitContentThroughRegisteredReader() {
        RecordingGitContentReader reader = new RecordingGitContentReader("configuration");
        ResourceReferenceResolver resolver = resolverWith(
                new GitResourceContentResolver(reader),
                Map.of("GIT_HOST", "git.example.test"));

        ResourceContent content = resolver.resolve(
                "git+https://$GIT_HOST/team/config.git?path=acl/orion.xml&ref=main",
                ResourceContent.class);

        assertThat(content.asUtf8String()).isEqualTo("configuration");
        assertThat(reader.repository.kind()).isEqualTo(GitRepositoryLocation.Kind.HTTPS);
        assertThat(reader.repository.location()).isEqualTo("https://git.example.test/team/config.git");
        assertThat(reader.path).isEqualTo("acl/orion.xml");
        assertThat(reader.ref).isEqualTo("main");
    }

    @Test
    void resolvesS3ContentThroughRegisteredReader() {
        RecordingS3ContentReader reader = new RecordingS3ContentReader("configuration");
        ResourceReferenceResolver resolver = resolverWith(
                new S3ResourceContentResolver(reader),
                Map.of("CONFIG_BUCKET", "orion-configs"));

        ResourceContent content = resolver.resolve(
                "s3://$CONFIG_BUCKET/prod/orion.yml?region=us-east-1",
                ResourceContent.class);

        assertThat(content.asUtf8String()).isEqualTo("configuration");
        assertThat(reader.location.bucket()).isEqualTo("orion-configs");
        assertThat(reader.location.key()).isEqualTo("prod/orion.yml");
        assertThat(reader.location.region()).contains("us-east-1");
    }

    private static ResourceReferenceResolver resolverWith(
            pro.deta.orion.resource.reference.ResourceCapabilityResolver<?> resolver,
            Map<String, String> environment) {
        return ResourceReferenceResolver.builder()
                .scope(ResourceReferenceScope.builder()
                        .environment(environment)
                        .build())
                .registry(ResourceResolverRegistry.builder()
                        .withDefaults()
                        .add(resolver)
                        .build())
                .build();
    }

    private static final class RecordingGitContentReader implements GitRepositoryContentReader {
        private final byte[] bytes;
        private GitRepositoryLocation repository;
        private String path;
        private String ref;

        private RecordingGitContentReader(String content) {
            this.bytes = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Optional<byte[]> read(GitRepositoryLocation repository, String path, String ref) {
            this.repository = repository;
            this.path = path;
            this.ref = ref;
            return Optional.of(bytes);
        }
    }

    private static final class RecordingS3ContentReader implements S3ObjectContentReader {
        private final byte[] bytes;
        private S3ObjectLocation location;

        private RecordingS3ContentReader(String content) {
            this.bytes = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Optional<byte[]> read(S3ObjectLocation location) {
            this.location = location;
            return Optional.of(bytes);
        }
    }
}
