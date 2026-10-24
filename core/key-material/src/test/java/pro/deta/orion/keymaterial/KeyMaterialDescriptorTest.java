package pro.deta.orion.keymaterial;

import java.util.Set;

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
        assertThat(descriptor.purposes()).containsExactly(KeyMaterialPurpose.SERVER_SIGNING);
        assertThat(descriptor.algorithm()).isEqualTo(KeyMaterialAlgorithm.RSA);
        assertThat(descriptor.version()).isEqualTo(2);
    }

    @Test
    void rejectsConflictingAlgorithmsForOneAlias() {
        KeyMaterialDescriptor signing = signing("shared-alias", 1);
        KeyMaterialDescriptor ssh = new KeyMaterialDescriptor(
                "shared-alias",
                Set.of(KeyMaterialPurpose.SSH_HOST),
                KeyMaterialAlgorithm.EC,
                1);

        assertThatThrownBy(() -> KeyMaterialCapabilities.open(null, List.of(signing, ssh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared-alias")
                .hasMessageContaining("conflicting");
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
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedCertificateDescriptor(alias,
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveVersionsAtTheDescriptorBoundary(long version) {
        assertThatThrownBy(() -> new KeyMaterialDescriptor("signing",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, version))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedCertificateDescriptor("root",
                Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, version))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAccountKeysCombinedWithOtherWorkflowRoles() {
        assertThatThrownBy(() -> new KeyMaterialDescriptor("account",
                Set.of(KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialPurpose.TLS_IDENTITY),
                KeyMaterialAlgorithm.RSA, 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACME_ACCOUNT");
    }

    private static KeyMaterialDescriptor signing(String alias, long version) {
        return new KeyMaterialDescriptor(
                alias,
                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA,
                version);
    }
}
