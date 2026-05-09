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
        assertThat(location.hasNoScheme()).isTrue();
        assertThat(location.hasNoSchemeOrScheme("file")).isTrue();
    }

    @Test
    void parsesAbsoluteFilePathValue() {
        ResourceLocation location = ResourceLocation.parse("file:/tmp/orion-repos", "Resource location");

        assertThat(location.hasScheme("file")).isTrue();
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

        assertThat(location.scheme()).isEqualTo("s3");
        assertThat(location.host()).isEqualTo("bucket");
        assertThat(location.path()).isEqualTo("/prefix/repositories");
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
    void rejectsBlankLocation() {
        assertThatThrownBy(() -> ResourceLocation.parse(" ", "Resource location"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resource location must not be empty");
    }
}
