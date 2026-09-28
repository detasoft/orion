package pro.deta.orion.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class CertUtils {
    public static PrivateKeyWithCerts generateSelfSignedCertificate() throws Exception {
        // Generate key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Certificate validity period (1 year)
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter = Date.from(now.plus(365, ChronoUnit.DAYS));

        // Generate self-signed certificate
        String localhost = "localhost";
        X500Name issuerName = new X500Name("CN=" + localhost);
        BigInteger serialNumber = new BigInteger(64, new SecureRandom());

        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                issuerName,
                serialNumber,
                notBefore,
                notAfter,
                issuerName,
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded())
        );
        ASN1Encodable[] encodableAltNames = new ASN1Encodable[]{new GeneralName(GeneralName.dNSName, localhost)};
        KeyPurposeId[] purposes = new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth};

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature + KeyUsage.keyEncipherment));
        certBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purposes));
        certBuilder.addExtension(Extension.subjectAlternativeName, false, new DERSequence(encodableAltNames));

        // Sign the certificate
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        return new PrivateKeyWithCerts(keyPair.getPrivate(), new X509Certificate[]{cert});
    }

    @RequiredArgsConstructor
    @Getter
    public static class PrivateKeyWithCerts {
        private final PrivateKey privateKey;
        private final X509Certificate[] x509Certificates;
    }

    public static KeyStore convertToKeyStore(PrivateKeyWithCerts pkWithCerts, String alias, char[] initialPassword) throws CertificateException, IOException, NoSuchAlgorithmException, KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry(alias, pkWithCerts.getPrivateKey(), initialPassword, pkWithCerts.getX509Certificates());
        return keyStore;
    }
}
