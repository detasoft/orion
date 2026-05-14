package pro.deta.orion.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceLocationTest {
    @Test
    void parsesPlainPathWithoutScheme() {
        ResourceLocation location = ResourceLocation.parse("repositories", "Resource location");

        assertThat(location.raw()).isEqualTo("repositories");
        assertThat(location.scheme()).isEqualTo(ResourceScheme.EMPTY);
    }

    @Test
    void parsesAbsoluteFilePathValue() {
        ResourceLocation location = ResourceLocation.parse("file:/tmp/orion-repos", "Resource location");

        assertThat(location.scheme()).isEqualTo(ResourceScheme.FILE);
        assertThat(location.pathOrSchemeSpecificPart("File location must include a path")).isEqualTo("/tmp/orion-repos");
    }

    @Test
    void parsesRelativeFilePathValue() {
        ResourceLocation location = ResourceLocation.parse("file:target/orion-repos", "Resource location");

        assertThat(location.pathOrSchemeSpecificPart("File location must include a path")).isEqualTo("target/orion-repos");
    }

    @Test
    void exposesHierarchicalParts() {
        ResourceLocation location = ResourceLocation.parse("s3://bucket/prefix/repositories", "Resource location");

        assertThat(location.scheme()).isEqualTo(ResourceScheme.other("s3"));
        assertThat(location.scheme().value()).isEqualTo("s3");
        assertThat(location.host()).isEqualTo("bucket");
        assertThat(location.path()).isEqualTo("/prefix/repositories");
    }

    @Test
    void exposesLocalSchemeAsKnownType() {
        ResourceLocation location = ResourceLocation.parse("local:team/project", "Resource location");

        assertThat(location.scheme()).isEqualTo(ResourceScheme.LOCAL);
        assertThat(location.scheme().value()).isEqualTo("local");
    }

    @Test
    void resolvesNormalizedRelativePathFromOpaqueLocation() {
        ResourceLocation location = ResourceLocation.parse("local:team/project", "Resource location");

        assertThat(location.normalizedRelativePath()).isEqualTo(Path.of("team/project").toString());
    }

    @Test
    void resolvesNormalizedRelativePathFromHierarchicalLocation() {
        ResourceLocation location = ResourceLocation.parse("local://team/project", "Resource location");

        assertThat(location.normalizedRelativePath()).isEqualTo(Path.of("team/project").toString());
    }

    @Test
    void replacesSchemeAndKeepsOpaqueLocationBody() {
        ResourceLocation location = ResourceLocation.parse("git+file:target/orion-acl.git", "Resource location");

        ResourceLocation normalized = location.withScheme("file");

        assertThat(normalized.raw()).isEqualTo("file:target/orion-acl.git");
        assertThat(normalized.scheme()).isEqualTo(ResourceScheme.FILE);
        assertThat(normalized.pathOrSchemeSpecificPart("File location must include a path"))
                .isEqualTo("target/orion-acl.git");
    }

    @Test
    void replacesSchemeAndKeepsHierarchicalLocationBody() {
        ResourceLocation location = ResourceLocation.parse("git+ssh://git@example.test/orion/acl.git", "Resource location");

        ResourceLocation normalized = location.withScheme("ssh");

        assertThat(normalized.raw()).isEqualTo("ssh://git@example.test/orion/acl.git");
        assertThat(normalized.scheme()).isEqualTo(ResourceScheme.other("ssh"));
        assertThat(normalized.host()).isEqualTo("example.test");
        assertThat(normalized.path()).isEqualTo("/orion/acl.git");
    }

    @Test
    void rejectsSchemeReplacementForPlainPath() {
        ResourceLocation location = ResourceLocation.parse("repositories", "Resource location");

        assertThatThrownBy(() -> location.withScheme("file"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resource location does not have a scheme: repositories");
    }

    @Test
    void rejectsBlankLocation() {
        assertThatThrownBy(() -> ResourceLocation.parse(" ", "Resource location"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resource location must not be empty");
    }
}
