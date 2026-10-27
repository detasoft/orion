package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.PKCS12Attribute;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialCodecTest {
    @Test
    void ownsOptionsIndependentlyOfTheCaller() throws Exception {
        KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
        KeyMaterialCodec codec = new KeyMaterialCodec(options);
        try {
            options.close();
            KeyStore keyStore = codec.create();
            byte[] content = codec.encode(keyStore);
            try {
                assertThat(codec.decode(content).size()).isZero();
            } finally {
                Arrays.fill(content, (byte) 0);
            }
            codec.close();
            assertThatThrownBy(codec::create)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            codec.close();
        } finally {
            codec.close();
            options.close();
        }
    }

    @Test
    void createsAnEmptyKeyStore() throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             KeyMaterialCodec codec = new KeyMaterialCodec(options)) {
            KeyStore keyStore = codec.create();
            assertThat(keyStore.getType()).isEqualToIgnoringCase("PKCS12");
            assertThat(keyStore.size()).isZero();
        }
    }

    @Test
    void preservesKeysCertificatesAndAttributesInPkcs12Bytes() throws Exception {
        char[] password = KeyMaterialTestConstants.password();
        byte[] content = null;
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(password);
             KeyMaterialCodec codec = new KeyMaterialCodec(options)) {
            KeyStore original = KeyStore.getInstance("PKCS12");
            original.load(null, password);
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey key = generator.generateKey();
            PKCS12Attribute attribute = new PKCS12Attribute("1.2.3.4", "key metadata");
            original.setEntry("secret", new KeyStore.SecretKeyEntry(key, Set.of(attribute)),
                    new KeyStore.PasswordProtection(password));
            X509Certificate certificate = TestCertificateChain.root("Codec root").certificate();
            original.setCertificateEntry("root", certificate);

            content = codec.encode(original);
            KeyStore independent = KeyStore.getInstance("PKCS12");
            independent.load(new ByteArrayInputStream(content), password);
            assertThat(independent.getKey("secret", password).getEncoded()).isEqualTo(key.getEncoded());

            KeyStore decoded = codec.decode(content);
            Arrays.fill(content, (byte) 0);
            assertThat(decoded.getKey("secret", password).getEncoded()).isEqualTo(key.getEncoded());
            assertThat(decoded.getCertificate("root")).isEqualTo(certificate);
            assertThat(decoded.getAttributes("secret")).contains(attribute);
            assertThat(decoded.size()).isEqualTo(2);
        } finally {
            Arrays.fill(password, '\0');
            if (content != null) Arrays.fill(content, (byte) 0);
        }
    }

    @Test
    void rejectsCorruptBytesAndTheWrongPassword() throws Exception {
        byte[] content = null;
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             KeyMaterialOptions wrong = KeyMaterialOptions.pkcs12("wrong password".toCharArray());
             KeyMaterialCodec codec = new KeyMaterialCodec(options);
             KeyMaterialCodec wrongCodec = new KeyMaterialCodec(wrong)) {
            assertThatThrownBy(() -> codec.decode(new byte[]{1, 2, 3}))
                    .isInstanceOf(IOException.class);
            content = codec.encode(codec.create());
            byte[] encoded = content;
            assertThatThrownBy(() -> wrongCodec.decode(encoded)).isInstanceOf(IOException.class);
        } finally {
            if (content != null) Arrays.fill(content, (byte) 0);
        }
    }
}
