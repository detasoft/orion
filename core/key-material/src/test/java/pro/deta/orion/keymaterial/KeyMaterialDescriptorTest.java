package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialDescriptorTest {
    private static final KeyMaterialScope CLUSTER = KeyMaterialScope.cluster("orion-prod");

    @Test
    void modelsPurposeAlgorithmVersionAndScope() {
        KeyMaterialDescriptor descriptor = signing("server-signing-v2", 2);

        assertThat(descriptor.alias().value()).isEqualTo("server-signing-v2");
        assertThat(descriptor.purpose()).isEqualTo(KeyMaterialPurpose.SERVER_SIGNING);
        assertThat(descriptor.algorithm()).isEqualTo(KeyMaterialAlgorithm.RSA);
        assertThat(descriptor.version().value()).isEqualTo(2);
        assertThat(descriptor.scope()).isEqualTo(CLUSTER);
        assertThat(KeyMaterialScope.node("orion-prod", "node-7").canonicalName())
                .isEqualTo("node:10:orion-prod:6:node-7");
    }

    @Test
    void rejectsCrossPurposeAliasReuse() {
        KeyMaterialDescriptor signing = signing("shared-alias", 1);
        KeyMaterialDescriptor ssh = new KeyMaterialDescriptor(
                new KeyMaterialAlias("shared-alias"),
                KeyMaterialPurpose.SSH_HOST,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(1),
                KeyMaterialScope.node("orion-prod", "node-7"));

        assertThatThrownBy(() -> KeyMaterialCapabilities.open(null, List.of(signing, ssh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared-alias")
                .hasMessageContaining("purposes");
    }

    @Test
    void rejectsRetainedSigningMaterialFromAnotherScope() {
        KeyMaterialDescriptor wrongScope = new KeyMaterialDescriptor(
                new KeyMaterialAlias("server-signing-node-v1"),
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(1),
                KeyMaterialScope.node("orion-prod", "node-7"));
        assertThatThrownBy(() -> new SigningMaterialSet(
                signing("server-signing-v2", 2), List.of(wrongScope)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
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
                new KeyMaterialVersion(version),
                CLUSTER);
    }
}
