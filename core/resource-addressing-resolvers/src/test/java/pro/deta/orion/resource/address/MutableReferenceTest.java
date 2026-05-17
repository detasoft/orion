package pro.deta.orion.resource.address;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MutableReferenceTest {
    @TempDir
    private Path tempDir;

    @Test
    void plainAndFileReferencesResolveToMutableReferences() throws Exception {
        Path plainPath = tempDir.resolve("plain.bin");
        Path filePath = tempDir.resolve("file.bin");
        ResourceResolver resolver = ResourceResolver.standard();

        MutableReference plainReference = resolver.resolve(plainPath.toString(), MutableReference.class);
        MutableReference fileContentReference =
                resolver.resolve(fileReference(filePath), MutableReference.class);

        String plainVersion = plainReference.write(bytes("plain"), null);
        String fileVersion = fileContentReference.write(bytes("file"), null);

        assertThat(Files.readAllBytes(plainPath)).isEqualTo(bytes("plain"));
        assertThat(Files.readAllBytes(filePath)).isEqualTo(bytes("file"));
        assertThat(plainReference.read().orElseThrow().version()).contains(plainVersion);
        assertThat(fileContentReference.read().orElseThrow().version()).contains(fileVersion);
    }

    @Test
    void environmentReferenceCanPointToWritableContentLocation() throws Exception {
        Path path = tempDir.resolve("env.bin");
        ResourceResolver resolver = ResourceResolver.standard(Map.of("ORION_CONTENT", fileReference(path)));

        MutableReference reference = resolver.resolve("env:ORION_CONTENT", MutableReference.class);

        String version = reference.write(bytes("env"), null);

        assertThat(Files.readAllBytes(path)).isEqualTo(bytes("env"));
        assertThat(reference.read().orElseThrow().version()).contains(version);
    }

    @Test
    void localMutableReferenceRejectsStaleWrites() throws Exception {
        Path path = tempDir.resolve("cas.bin");
        MutableReference first = ResourceResolver.standard().resolve(path.toString(), MutableReference.class);
        MutableReference second = ResourceResolver.standard().resolve(path.toString(), MutableReference.class);

        String initialVersion = first.write(bytes("first"), null);
        String secondVersion = second.write(bytes("second"), initialVersion);

        assertThatThrownBy(() -> first.write(bytes("stale"), initialVersion))
                .isInstanceOf(MutableReferenceConflictException.class)
                .hasMessageContaining("changed before save");
        assertThat(first.read().orElseThrow().version()).contains(secondVersion);
        assertThat(first.read().orElseThrow().bytes()).isEqualTo(bytes("second"));
    }

    @Test
    void inlineContentResolvesToImmutableReference() throws Exception {
        ResourceResolver resolver = ResourceResolver.standard();

        ImmutableReference reference =
                resolver.resolve(contentBase64Reference(bytes("inline")), ImmutableReference.class);
        ResourceContent snapshot = reference.read().orElseThrow();

        assertThat(snapshot.bytes()).isEqualTo(bytes("inline"));
        assertThat(snapshot.version()).contains(ResourceContentVersions.sha256(bytes("inline")));
    }

    @Test
    void rawStringAndEnvironmentResolveToImmutableReferences() throws Exception {
        ResourceResolver resolver = ResourceResolver.standard(Map.of("ORION_VALUE", "env-value"));

        ImmutableReference rawReference = resolver.resolve("raw-value", ImmutableReference.class);
        ImmutableReference envReference = resolver.resolve("env:ORION_VALUE", ImmutableReference.class);

        assertThat(rawReference.readString()).contains("raw-value");
        assertThat(envReference.readString()).contains("env-value");
    }

    @Test
    void inlineContentDoesNotResolveToMutableReference() {
        ResourceResolver resolver = ResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolve(contentBase64Reference(bytes("inline")), MutableReference.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No resource resolver");
    }

    @Test
    void referenceResolverResolvesLocationsByMutability() throws Exception {
        Path path = tempDir.resolve("reference.bin");
        ReferenceResolver resolver = ReferenceResolver.standard(Map.of("ORION_CONTENT", fileReference(path)));

        ImmutableReference inlineReference =
                resolver.resolveLocation(contentBase64Reference(bytes("inline")));
        ImmutableReference environmentReference =
                resolver.resolveLocation("env:ORION_CONTENT");

        assertThat(inlineReference).isNotInstanceOf(MutableReference.class);
        assertThat(inlineReference.read().orElseThrow().bytes()).isEqualTo(bytes("inline"));
        assertThat(environmentReference).isInstanceOf(MutableReference.class);

        MutableReference mutableReference = (MutableReference) environmentReference;
        String version = mutableReference.write(bytes("location"), null);

        assertThat(Files.readAllBytes(path)).isEqualTo(bytes("location"));
        assertThat(mutableReference.read().orElseThrow().version()).contains(version);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String fileReference(Path path) {
        return ResourceScheme.FILE.value() + ":" + path;
    }

    private static String contentBase64Reference(byte[] bytes) {
        return ResourceScheme.CONTENT.value()
                + ":"
                + ResourceAddressConstants.BASE64_CONTENT_PREFIX
                + Base64.getEncoder().encodeToString(bytes);
    }
}
