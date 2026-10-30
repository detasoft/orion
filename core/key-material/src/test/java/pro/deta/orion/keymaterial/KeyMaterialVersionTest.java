package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialVersionTest {
    @Test
    void retainsExactCertificateVersionsAcrossReloadAndBlocksDeletedHistory() throws Exception {
        InMemoryKeyMaterialContentSource source = new InMemoryKeyMaterialContentSource();
        KeyMaterialDescriptor root = new KeyMaterialDescriptor("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                KeyMaterialAlgorithm.RSA, 1);
        X509Certificate first = TestCertificateChain.root("First").certificate();
        X509Certificate second = TestCertificateChain.root("Second").certificate();
        KeyMaterialId firstId;
        KeyMaterialId secondId;
        try (OrionKeyMaterial material = open(source)) {
            KeyMaterialModification initial = material.modify(material.revision());
            firstId = initial.setTrustedCertificate(root, first);
            initial.apply();
            String revision = material.revision();
            KeyMaterialModification renewal = material.modify(revision);
            secondId = renewal.setTrustedCertificate(root, second);
            renewal.apply();
            assertThat(firstId).isEqualTo(new KeyMaterialId("root", 1));
            assertThat(secondId).isEqualTo(new KeyMaterialId("root", 2));
            assertThat(material.revision()).isNotEqualTo(revision);
            assertThat(material.inventory()).containsExactly(secondId);
            assertThat(material.read(firstId).trustedCertificate()).isEqualTo(first);
            assertThat(material.read(secondId).trustedCertificate()).isEqualTo(second);
        }
        try (OrionKeyMaterial material = open(source)) {
            assertThat(material.read(firstId).trustedCertificate()).isEqualTo(first);
            assertThat(material.read(secondId).trustedCertificate()).isEqualTo(second);
            KeyMaterialModification deletion = material.modify(material.revision());
            deletion.delete(root);
            deletion.apply();
            assertThatThrownBy(() -> material.read(firstId)).isInstanceOf(GeneralSecurityException.class);
            assertThatThrownBy(() -> material.read(secondId)).isInstanceOf(GeneralSecurityException.class);
        }
    }

    @Test
    void rotatesOneSigningAliasWithoutChangingOtherEntryIds() throws Exception {
        KeyMaterialDescriptor signing = new KeyMaterialDescriptor("signing",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor other = new KeyMaterialDescriptor("other",
                signing.purposes(), signing.algorithm(), 1);
        InMemoryKeyMaterialContentSource source = new InMemoryKeyMaterialContentSource();
        byte[] payload = new byte[]{1, 2, 3};
        byte[] oldSignature;
        KeyMaterialId next;
        try (OrionKeyMaterial material = open(source)) {
            KeyMaterialModification initial = material.modify(material.revision());
            initial.generateKey(signing, 2048);
            initial.generateKey(other, 2048);
            initial.apply();
            oldSignature = material.sign(signing, payload);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyMaterialModification rotation = material.modify(material.revision());
            KeyMaterialDescriptor subset = new KeyMaterialDescriptor(signing.alias(),
                    Set.of(KeyMaterialPurpose.SERVER_SIGNING), signing.algorithm(), signing.version());
            next = rotation.importKey(subset, generator.generateKeyPair());
            rotation.apply();
            KeyMaterialDescriptor active = material.read(next).descriptor();
            assertThat(next).isEqualTo(new KeyMaterialId("signing", 2));
            assertThat(material.read(next).purposes()).isEqualTo(signing.purposes());
            assertThat(material.read(other.id()).id()).isEqualTo(other.id());
            assertThat(material.verify(signing, payload, oldSignature)).isTrue();
            assertThat(material.verify(active, payload, oldSignature)).isFalse();
            assertThat(new SigningMaterialSet(active, java.util.List.of(signing)).verification())
                    .containsExactly(signing);
        }
        try (OrionKeyMaterial material = open(source)) {
            assertThat(material.read(next).purposes()).isEqualTo(signing.purposes());
            assertThat(material.inventory()).containsExactlyInAnyOrder(next, other.id());
            assertThat(material.verify(signing, payload, oldSignature)).isTrue();
            assertThat(material.verify(material.read(next).descriptor(), payload, oldSignature)).isFalse();
        }
    }

    private static OrionKeyMaterial open(KeyMaterialContentSource source) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(new KeyMaterialContentStore(source,
                    new KeyMaterialCodec(options), new OrionEventManager()));
        }
    }
}
