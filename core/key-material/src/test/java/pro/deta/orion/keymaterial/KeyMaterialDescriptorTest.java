package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialDescriptorTest {
    @Test
    void modelsPurposeAlgorithmAndVersion() {
        KeyMaterialDescriptor descriptor = signing("server-signing-v2", 2);

        assertThat(descriptor.alias()).isEqualTo("server-signing-v2");
        assertThat(descriptor.purpose()).isEqualTo(KeyMaterialPurpose.SERVER_SIGNING);
        assertThat(descriptor.algorithm()).isEqualTo(KeyMaterialAlgorithm.RSA);
        assertThat(descriptor.version()).isEqualTo(2);
    }

    @Test
    void rejectsCrossPurposeAliasReuse() {
        KeyMaterialDescriptor signing = signing("shared-alias", 1);
        KeyMaterialDescriptor ssh = new KeyMaterialDescriptor(
                "shared-alias",
                KeyMaterialPurpose.SSH_HOST,
                KeyMaterialAlgorithm.RSA,
                1);

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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsBlankAliasesAtTheDescriptorBoundary(String alias) {
        assertThatThrownBy(() -> new KeyMaterialDescriptor(alias,
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedCertificateDescriptor(alias, KeyMaterialAlgorithm.RSA, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveVersionsAtTheDescriptorBoundary(long version) {
        assertThatThrownBy(() -> new KeyMaterialDescriptor("signing",
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, version))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedCertificateDescriptor("root", KeyMaterialAlgorithm.RSA, version))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KeyMaterialDescriptor signing(String alias, long version) {
        return new KeyMaterialDescriptor(
                alias,
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                version);
    }
}
