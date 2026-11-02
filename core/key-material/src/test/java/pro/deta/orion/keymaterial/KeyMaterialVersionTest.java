package pro.deta.orion.keymaterial;

import pro.deta.orion.keymaterial.KeyMaterialTestRuntime.TestBlobStorage;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcRevision;

import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.revision;

import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.InMemoryBlobStorage;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialVersionTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final KeyMaterialTestRuntime runtime = new KeyMaterialTestRuntime();

    @Test
    void retainsExactCertificateVersionsAcrossReloadAndBlocksDeletedHistory() throws Exception {
        InMemoryBlobStorage source = new TestBlobStorage(runtime.events);
        KeyMaterialId root = new KeyMaterialId("root", 1);
        X509Certificate first = TestCertificateChain.root("First").certificate();
        X509Certificate second = TestCertificateChain.root("Second").certificate();
        KeyMaterialId firstId;
        KeyMaterialId secondId;
        try (OrionKeyMaterial material = open(source)) {
            KeyMaterialOperation initial = material.open(new VcRevision.Stored(material.revision()));
            firstId = initial.setTrustedCertificate("root", Set.of(KeyMaterialPurpose.TRUST_ANCHOR), KeyMaterialAlgorithm.RSA, first);
            initial.commit(VcChangeDescription.EMPTY);
            String revision = material.revision();
            KeyMaterialOperation renewal = material.open(new VcRevision.Stored(revision));
            secondId = renewal.setTrustedCertificate(root, second);
            renewal.commit(VcChangeDescription.EMPTY);
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
            KeyMaterialOperation deletion = material.open(new VcRevision.Stored(material.revision()));
            deletion.delete(root);
            deletion.commit(VcChangeDescription.EMPTY);
            assertThatThrownBy(() -> material.read(firstId)).isInstanceOf(GeneralSecurityException.class);
            assertThatThrownBy(() -> material.read(secondId)).isInstanceOf(GeneralSecurityException.class);
        }
    }

    @Test
    void rotatesOneSigningAliasWithoutChangingOtherEntryIds() throws Exception {
        KeyMaterialId signing = new KeyMaterialId("signing", 1);
        KeyMaterialId other = new KeyMaterialId("other", 1);
        InMemoryBlobStorage source = new TestBlobStorage(runtime.events);
        byte[] payload = new byte[]{1, 2, 3};
        byte[] oldSignature;
        KeyMaterialId next;
        try (OrionKeyMaterial material = open(source)) {
            KeyMaterialOperation initial = material.open(new VcRevision.Stored(material.revision()));
            initial.generateKey("signing", Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA, 2048);
            initial.generateKey("other", Set.of(KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA, 2048);
            initial.commit(VcChangeDescription.EMPTY);
            oldSignature = material.sign(signing, payload);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyMaterialOperation rotation = material.open(new VcRevision.Stored(material.revision()));
            KeyMaterialId subset = new KeyMaterialId(signing.alias(), signing.version());
            next = rotation.importKey(subset, generator.generateKeyPair());
            rotation.commit(VcChangeDescription.EMPTY);
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

    private OrionKeyMaterial open(BlobStorage source) throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(source,
                    new KeyMaterialCodec(options), runtime.events));
        }
    }
}
