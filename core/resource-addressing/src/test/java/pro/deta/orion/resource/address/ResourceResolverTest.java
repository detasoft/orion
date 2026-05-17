package pro.deta.orion.resource.address;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesFilePathFromNestedEnvironmentReference() {
        Path configPath = temporaryDirectory.resolve("orion.yml");
        ResourceResolver resolver = ResourceResolver.standard(Map.of("ORION_CONFIG", configPath.toString()));

        Path resolved = resolver.resolve("file(env:ORION_CONFIG)", Path.class);

        assertThat(resolved).isEqualTo(configPath);
    }

    @Test
    void resolvesLegacyNestedFilePathFromEnvironmentReference() {
        Path configPath = temporaryDirectory.resolve("orion.yml");
        ResourceResolver resolver = ResourceResolver.standard(Map.of("ORION_CONFIG", configPath.toString()));

        Path resolved = resolver.resolve("file:env:ORION_CONFIG", Path.class);

        assertThat(resolved).isEqualTo(configPath);
    }

    @Test
    void resolvesGitRepositoryLocationFromNestedEnvironmentReference() {
        ResourceResolver resolver = ResourceResolver.standard(Map.of("PATH_TO_GIT_ROOT", temporaryDirectory.toString()));

        GitRepositoryLocation resolved = resolver.resolve("git(env:PATH_TO_GIT_ROOT)", GitRepositoryLocation.class);

        assertThat(resolved.kind()).isEqualTo(GitRepositoryLocation.Kind.LOCAL);
        assertThat(resolved.location()).isEqualTo(temporaryDirectory.toString());
    }

    @Test
    void resolvesGitResourceContentAfterResolvingRepositoryAddressInsideOut() {
        RecordingGitContentReader reader = new RecordingGitContentReader("acl");
        ResourceResolver resolver = resolverWithGitReader(
                Map.of("PATH_TO_GIT_ROOT", temporaryDirectory.toString()),
                reader);

        ResourceContent content = resolver.resolve(
                "git(env:PATH_TO_GIT_ROOT)/acl/orion.xml?ref=main",
                ResourceContent.class);

        assertThat(content.asUtf8String()).isEqualTo("acl");
        assertThat(reader.repository.kind()).isEqualTo(GitRepositoryLocation.Kind.LOCAL);
        assertThat(reader.repository.location()).isEqualTo(temporaryDirectory.toString());
        assertThat(reader.path).isEqualTo("acl/orion.xml");
        assertThat(reader.ref).isEqualTo("main");
    }

    @Test
    void resolvesS3ObjectLocationFromNestedEnvironmentReference() {
        ResourceResolver resolver = ResourceResolver.standard(Map.of("S3_ROOT", "s3://orion-configs/prod"));

        S3ObjectLocation resolved = resolver.resolve(
                "s3(env:S3_ROOT)/orion.yml?region=us-east-1",
                S3ObjectLocation.class);

        assertThat(resolved.bucket()).isEqualTo("orion-configs");
        assertThat(resolved.key()).isEqualTo("prod/orion.yml");
        assertThat(resolved.region()).contains("us-east-1");
    }

    @Test
    void resolvesS3ResourceContentWithFakeReader() {
        RecordingS3ContentReader reader = new RecordingS3ContentReader("configuration");
        ResourceResolver resolver = resolverWithS3Reader(Map.of("S3_ROOT", "s3://orion-configs/prod"), reader);

        ResourceContent content = resolver.resolve(
                "s3(env:S3_ROOT)/orion.yml?region=us-east-1",
                ResourceContent.class);

        assertThat(content.asUtf8String()).isEqualTo("configuration");
        assertThat(reader.location.bucket()).isEqualTo("orion-configs");
        assertThat(reader.location.key()).isEqualTo("prod/orion.yml");
        assertThat(reader.location.region()).contains("us-east-1");
    }

    @Test
    void resolvesBase64InlineResourceContent() {
        ResourceContent content = ResourceResolver.standard().resolve(
                ResourceScheme.CONTENT.value() + ":" + ResourceAddressConstants.BASE64_CONTENT_PREFIX + "aGVsbG8=",
                ResourceContent.class);

        assertThat(content.bytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void allowsDomainResolversForNewSchemesWithoutChangingRegistryCode() {
        ResourceResolver resolver = new ResourceResolver(List.of(new VaultSecretResolver()));

        VaultSecret secret = resolver.resolve("vault:orion/signing-key", VaultSecret.class);

        assertThat(secret.name()).isEqualTo("orion/signing-key");
    }

    private static ResourceResolver resolverWithGitReader(
            Map<String, String> environment,
            GitRepositoryContentReader reader) {
        return new ResourceResolver(List.of(
                new EnvironmentStringResolver(environment),
                new ContentStringResolver(),
                new AddressStringResolver(),
                new FilePathResolver(),
                new GitRepositoryLocationResolver(),
                new GitResourceContentResolver(reader)));
    }

    private static ResourceResolver resolverWithS3Reader(
            Map<String, String> environment,
            S3ObjectContentReader reader) {
        return new ResourceResolver(List.of(
                new EnvironmentStringResolver(environment),
                new ContentStringResolver(),
                new AddressStringResolver(),
                new S3ObjectLocationResolver(),
                new S3ResourceContentResolver(reader)));
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

    private record VaultSecret(String name) {
    }

    private static final class VaultSecretResolver implements ResourceAddressResolver<VaultSecret> {
        @Override
        public Class<VaultSecret> targetType() {
            return VaultSecret.class;
        }

        @Override
        public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
            return expression.hasScheme(ResourceScheme.of("vault")) && !expression.hasNested();
        }

        @Override
        public VaultSecret resolve(ResourceExpression expression, ResourceResolutionContext context) {
            return new VaultSecret(expression.directValue());
        }
    }
}
