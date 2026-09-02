package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationSecretContextTest {
    @Test
    void exposesOnlyStableSecretIdentity() {
        assertThat(Arrays.stream(ConfigurationSecretContext.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("secretId", "kind");
    }

    @Test
    void encodesEqualContextsDeterministically() {
        ConfigurationSecretContext first = completeContext();
        ConfigurationSecretContext second = completeContext();

        assertThat(first.authenticatedBytes()).isEqualTo(second.authenticatedBytes());
        assertThat(first.authenticatedBytes()).isNotSameAs(first.authenticatedBytes());
    }

    @Test
    void authenticatesEveryContextComponent() {
        ConfigurationSecretContext original = completeContext();
        List<ConfigurationSecretContext> changed = List.of(
                new ConfigurationSecretContext("other", "access-token"),
                new ConfigurationSecretContext("github-token", "other"));

        for (ConfigurationSecretContext candidate : changed) {
            assertThat(candidate.authenticatedBytes()).isNotEqualTo(original.authenticatedBytes());
        }
    }

    @Test
    void usesUnambiguousLengthPrefixedContextFields() {
        ConfigurationSecretContext first = new ConfigurationSecretContext("a", "bc");
        ConfigurationSecretContext second = new ConfigurationSecretContext("ab", "c");

        assertThat(first.authenticatedBytes()).isNotEqualTo(second.authenticatedBytes());
    }

    @Test
    void rejectsInvalidRequiredFields() {
        assertThatThrownBy(() -> new ConfigurationSecretContext(null, "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret ID");
        assertThatThrownBy(() -> new ConfigurationSecretContext(" ", "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret ID");
        assertThatThrownBy(() -> new ConfigurationSecretContext("secret", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> new ConfigurationSecretContext("secret", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
    }

    private static ConfigurationSecretContext completeContext() {
        return new ConfigurationSecretContext("github-token", "access-token");
    }
}
