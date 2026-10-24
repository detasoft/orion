package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionSshHostKeysTest {
    @Test
    void createsDefaultsOnFirstAccessAndReloadsThem() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        List<byte[]> publicKeys;

        try (OrionKeyMaterial material = open(store)) {
            SshHostKeyCapability keys = material.sshHostKeys(List.of());

            assertThat(keys.descriptors())
                    .extracting(descriptor -> descriptor.alias())
                    .containsExactly("ssh-host-ec-v1", "ssh-host-rsa-v1");
            publicKeys = keys.keyPairs().stream()
                    .map(key -> key.getPublic().getEncoded())
                    .toList();
        }

        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.sshHostKeys(List.of()).keyPairs())
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

        seed(store, List.of(first, latest, other));
        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.sshHostKeys(List.of(new SshHostKeyReference("node-a-rsa")))
                    .descriptors()).containsExactly(latest);
            assertThat(material.sshHostKeys(List.of(new SshHostKeyReference("node-a-rsa-v1")))
                    .descriptors()).containsExactly(first);
            assertThat(material.sshHostKeys(List.of()).descriptors())
                    .containsExactly(first, latest, other);
            assertThatThrownBy(() -> material.sshHostKeys(List.of(new SshHostKeyReference("missing"))))
                    .isInstanceOf(GeneralSecurityException.class)
                    .hasMessageContaining("missing");
        }

        try (OrionKeyMaterial material = open(store)) {
            assertThat(material.sshHostKeys(List.of()).descriptors())
                    .containsExactly(first, latest, other);
        }
    }

    @Test
    void doesNotTreatAMissingConcreteAliasAsALogicalAlias() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        KeyMaterialDescriptor nestedLogicalAlias = sshHostKey(
                "node-a-rsa-v1-v3", KeyMaterialAlgorithm.RSA, 3);

        seed(store, List.of(nestedLogicalAlias));
        try (OrionKeyMaterial material = open(store)) {
            assertThatThrownBy(() -> material.sshHostKeys(List.of(new SshHostKeyReference("node-a-rsa-v1"))))
                    .isInstanceOf(GeneralSecurityException.class)
                    .hasMessageContaining("node-a-rsa-v1");
        }
    }

    private static void seed(InMemoryKeyMaterialContentStore store, List<KeyMaterialDescriptor> descriptors)
            throws Exception {
        try (KeyMaterialService material = KeyMaterialService.open(store,
                KeyMaterialOptions.pkcs12("test-password".toCharArray()))) {
            material.generateKeyIfMissing(serverSigning(), 2_048);
            for (KeyMaterialDescriptor descriptor : descriptors) {
                material.generateKeyIfMissing(descriptor, 2_048);
            }
            material.save();
        }
    }

    private static OrionKeyMaterial open(InMemoryKeyMaterialContentStore store) throws Exception {
        return OrionKeyMaterial.open(
                store,
                KeyMaterialOptions.pkcs12("test-password".toCharArray()),
                new SigningMaterialSet(serverSigning(), List.of()),
                2_048,
                new KeyMaterialCreation(true));
    }

    private static KeyMaterialDescriptor serverSigning() {
        return new KeyMaterialDescriptor(
                "server-signing-v1",
                KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA,
                1);
    }

    private static KeyMaterialDescriptor sshHostKey(
            String alias,
            KeyMaterialAlgorithm algorithm,
            long version) {
        return new KeyMaterialDescriptor(
                alias,
                KeyMaterialPurpose.SSH_HOST,
                algorithm,
                version);
    }
}
