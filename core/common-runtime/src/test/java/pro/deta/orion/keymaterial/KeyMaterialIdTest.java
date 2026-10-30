package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialIdTest {
    @Test
    void normalizesAliasAndPreservesTheExplicitVersion() {
        KeyMaterialId id = new KeyMaterialId("  signing  ", 3);

        assertThat(id.alias()).isEqualTo("signing");
        assertThat(id.version()).isEqualTo(3);
    }

    @Test
    void rejectsMissingAliasesAndNonPositiveVersions() {
        for (String alias : new String[]{null, "", " ", "\t"}) {
            assertThatThrownBy(() -> new KeyMaterialId(alias, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (long version : new long[]{0, -1, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> new KeyMaterialId("signing", version))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
