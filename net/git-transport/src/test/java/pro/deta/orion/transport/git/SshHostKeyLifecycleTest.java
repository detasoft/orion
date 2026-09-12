package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.keymaterial.SshHostKeyCapability;
import pro.deta.orion.keymaterial.SshHostKeyReference;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshHostKeyLifecycleTest {
    private static final KeyMaterialScope.Cluster CLUSTER = new KeyMaterialScope.Cluster("orion-prod");

    @Test
    void createsDefaultsOnFirstTransportInitializationAndReloadsThem() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        List<byte[]> publicKeys;

        try (OrionKeyMaterial material = open(store)) {
            SshHostKeyCapability keys = SshHostKeyLifecycle.open(
                    material.sshHostKeyMaterial(), List.of());

            assertThat(keys.descriptors())
                    .extracting(descriptor -> descriptor.alias().value())
                    .containsExactly("ssh-host-ec-v1", "ssh-host-rsa-v1");
            publicKeys = keys.keyPairs().stream()
                    .map(key -> key.getPublic().getEncoded())
                    .toList();
        }

        try (OrionKeyMaterial material = open(store)) {
            assertThat(SshHostKeyLifecycle.open(material.sshHostKeyMaterial(), List.of()).keyPairs())
                    .extracting(key -> key.getPublic().getEncoded())
                    .containsExactlyElementsOf(publicKeys);
        }
    }

    @Test
    void selectsExactOrLatestAliasWithoutDeletingOlderKeys() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor first = sshHostKey("node-a-rsa-v1", KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor latest = sshHostKey("node-a-rsa-v3", KeyMaterialAlgorithm.RSA, 3);
        KeyMaterialDescriptor other = sshHostKey("node-b-rsa-v4", KeyMaterialAlgorithm.RSA, 4);

        try (OrionKeyMaterial material = open(store)) {
            material.sshHostKeyMaterial().generateIfMissing(Map.of(first, 2_048, latest, 2_048, other, 2_048));

            assertThat(SshHostKeyLifecycle.open(
                    material.sshHostKeyMaterial(),
                    List.of(new SshHostKeyReference("node-a-rsa")))
                    .descriptors()).containsExactly(latest);
            assertThat(SshHostKeyLifecycle.open(
                    material.sshHostKeyMaterial(),
                    List.of(new SshHostKeyReference("node-a-rsa-v1")))
                    .descriptors()).containsExactly(first);
            assertThat(SshHostKeyLifecycle.open(material.sshHostKeyMaterial(), List.of()).descriptors())
                    .containsExactly(first, latest, other);
            assertThatThrownBy(() -> SshHostKeyLifecycle.open(
                    material.sshHostKeyMaterial(),
                    List.of(new SshHostKeyReference("missing"))))
                    .isInstanceOf(GeneralSecurityException.class)
                    .hasMessageContaining("missing");
        }

        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.sshHostKeyMaterial().available())
                    .containsExactly(first, latest, other);
        }
    }

    @Test
    void doesNotTreatAMissingConcreteAliasAsALogicalAlias() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor nestedLogicalAlias = sshHostKey(
                "node-a-rsa-v1-v3", KeyMaterialAlgorithm.RSA, 3);

        try (OrionKeyMaterial material = open(store)) {
            material.sshHostKeyMaterial().generateIfMissing(Map.of(nestedLogicalAlias, 2_048));

            assertThatThrownBy(() -> SshHostKeyLifecycle.open(
                    material.sshHostKeyMaterial(),
                    List.of(new SshHostKeyReference("node-a-rsa-v1"))))
                    .isInstanceOf(GeneralSecurityException.class)
                    .hasMessageContaining("node-a-rsa-v1");
        }
    }

    private static OrionKeyMaterial open(InMemoryKeyMaterialContentStore store) throws Exception {
        return OrionKeyMaterial.open(
                store,
                KeyMaterialOptions.pkcs12("test-password".toCharArray()),
                new SigningMaterialSet(serverSigning(), List.of()),
                2_048);
    }

    private static KeyMaterialDescriptor serverSigning() {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias("server-signing-v1"),
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(1),
                CLUSTER);
    }

    private static KeyMaterialDescriptor sshHostKey(
            String alias,
            KeyMaterialAlgorithm algorithm,
            long version) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(alias),
                KeyMaterialPurpose.SSH_HOST,
                algorithm,
                new KeyMaterialVersion(version),
                CLUSTER);
    }
}
