package pro.deta.orion.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSchemeTest {
    @Test
    void resolvesEmptyScheme() {
        assertThat(ResourceScheme.fromNullable(null)).isEqualTo(ResourceScheme.EMPTY);
        assertThat(ResourceScheme.EMPTY.value()).isEmpty();
    }

    @Test
    void resolvesKnownSchemes() {
        assertThat(ResourceScheme.from("file")).isEqualTo(ResourceScheme.FILE);
        assertThat(ResourceScheme.from("LOCAL")).isEqualTo(ResourceScheme.LOCAL);
    }

    @Test
    void resolvesOtherSchemeWithNormalizedValue() {
        ResourceScheme scheme = ResourceScheme.from("S3");

        assertThat(scheme).isEqualTo(ResourceScheme.other("s3"));
        assertThat(scheme.value()).isEqualTo("s3");
    }
}
