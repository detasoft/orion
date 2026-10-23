package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialDescriptorTest {
    @Test
    void modelsPurposeAlgorithmAndVersion() {
        KeyMaterialDescriptor descriptor = signing("server-signing-v2", 2);

        assertThat(descriptor.alias().value()).isEqualTo("server-signing-v2");
        assertThat(descriptor.purpose()).isEqualTo(KeyMaterialPurpose.SERVER_SIGNING);
        assertThat(descriptor.algorithm()).isEqualTo(KeyMaterialAlgorithm.RSA);
        assertThat(descriptor.version().value()).isEqualTo(2);
    }

    @Test
    void rejectsCrossPurposeAliasReuse() {
        KeyMaterialDescriptor signing = signing("shared-alias", 1);
        KeyMaterialDescriptor ssh = new KeyMaterialDescriptor(
                new KeyMaterialAlias("shared-alias"),
                KeyMaterialPurpose.SSH_HOST,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(1));

        assertThatThrownBy(() -> KeyMaterialCapabilities.open(null, List.of(signing, ssh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared-alias")
                .hasMessageContaining("purposes");
    }

    @Test
    void rejectsActiveAliasWithDifferentVerificationVersion() {
        assertThatThrownBy(() -> new SigningMaterialSet(
                signing("server-signing", 2),
                List.of(signing("server-signing", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias")
                .hasMessageContaining("server-signing");
    }

    @Test
    void rejectsConflictingVerificationDescriptorsForOneAlias() {
        assertThatThrownBy(() -> new SigningMaterialSet(
                signing("server-signing-v3", 3),
                List.of(signing("retired-signing", 1), signing("retired-signing", 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias")
                .hasMessageContaining("retired-signing");
    }

    @Test
    void rejectsVerificationMaterialThatIsNotOlderThanActive() {
        assertThatThrownBy(() -> new SigningMaterialSet(
                signing("server-signing-v2", 2),
                List.of(signing("server-signing-v3", 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("older");
    }

    private static KeyMaterialDescriptor signing(String alias, long version) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(alias),
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(version));
    }
}
