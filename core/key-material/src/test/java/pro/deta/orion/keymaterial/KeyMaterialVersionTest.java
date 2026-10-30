package pro.deta.orion.keymaterial;

import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.InMemoryBlobStorage;

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
        InMemoryBlobStorage source = new InMemoryBlobStorage();
        KeyMaterialId root = new KeyMaterialId("root", 1);
        X509Certificate first = TestCertificateChain.root("First").certificate();
        X509Certificate second = TestCertificateChain.root("Second").certificate();
        KeyMaterialId firstId;
        KeyMaterialId secondId;
        try (OrionKeyMaterial material = open(source)) {
            KeyMaterialModification initial = material.modify(material.revision());
            firstId = initial.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, first);
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
        KeyMaterialId signing = new KeyMaterialId("signing", 1);
        KeyMaterialId other = new KeyMaterialId("other", 1);
        InMemoryBlobStorage source = new InMemoryBlobStorage();
        byte[] payload = new byte[]{1, 2, 3};
        byte[] oldSignature;
        KeyMaterialId next;
        try (OrionKeyMaterial material = open(source)) {
            KeyMaterialModification initial = material.modify(material.revision());
            initial.generateKey("signing", Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA, 2048);
            initial.generateKey("other", Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA, 2048);
            initial.apply();
            oldSignature = material.sign(signing, payload);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyMaterialModification rotation = material.modify(material.revision());
            KeyMaterialId subset = new KeyMaterialId(signing.alias(), signing.version());
            next = rotation.importKey(subset, generator.generateKeyPair());
            rotation.apply();
            KeyMaterialId active = material.read(next).id();
            assertThat(next).isEqualTo(new KeyMaterialId("signing", 2));
            assertThat(material.read(next).purposes()).isEqualTo(Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST));
            assertThat(material.read(other).id()).isEqualTo(other);
            assertThat(material.verify(signing, payload, oldSignature)).isTrue();
            assertThat(material.verify(active, payload, oldSignature)).isFalse();
            assertThat(new SigningMaterialSet(active, java.util.List.of(signing)).verification())
                    .containsExactly(signing);
        }
        try (OrionKeyMaterial material = open(source)) {
            assertThat(material.read(next).purposes()).isEqualTo(Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST));
            assertThat(material.inventory()).containsExactlyInAnyOrder(next, other);
            assertThat(material.verify(signing, payload, oldSignature)).isTrue();
            assertThat(material.verify(material.read(next).id(), payload, oldSignature)).isFalse();
        }
    }

    private static OrionKeyMaterial open(BlobStorage source) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(source,
                    new KeyMaterialCodec(options), new OrionEventManager()));
        }
    }
}
