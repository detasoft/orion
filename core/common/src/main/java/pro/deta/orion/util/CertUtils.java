package pro.deta.orion.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.io.FileReader;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

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

    public static PrivateKeyWithCerts readKeyWithCertsFromPEM(String path, String keyPassword) throws IOException, CertificateException, OperatorCreationException, PKCSException {
        List<X509Certificate> cert = new ArrayList<>();
        final JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        PrivateKey privateKey = null;
        try (PEMParser pemParser = new PEMParser(new FileReader(path))) {
            Object object;
            while ((object = pemParser.readObject()) != null) {
                switch (object) {
                    case X509CertificateHolder holder -> {
                        final JcaX509CertificateConverter certificateConverter = new JcaX509CertificateConverter();
                        cert.add(certificateConverter.getCertificate(holder));
                    }
                    case PEMKeyPair pemKeyPair -> {
                        KeyPair keyPair = converter.getKeyPair(pemKeyPair);
                        privateKey = keyPair.getPrivate();
                    }
                    case PrivateKeyInfo privateKeyInfo -> privateKey = converter.getPrivateKey(privateKeyInfo);
                    case PKCS8EncryptedPrivateKeyInfo privateKeyInfo -> {
                        final InputDecryptorProvider decryptorProvider = new JceOpenSSLPKCS8DecryptorProviderBuilder().build(keyPassword.toCharArray());
                        privateKey = converter.getPrivateKey(privateKeyInfo.decryptPrivateKeyInfo(decryptorProvider));
                    }
                    case null, default ->
                            throw new IllegalArgumentException("Unsupported format. The key must be in PKCS#1 or PKCS#8 format and X509 certificate.");
                }
            }
        }
        if (privateKey == null)
            throw new IllegalArgumentException("No private key found in " + path);

        return new PrivateKeyWithCerts(privateKey, cert.toArray(new X509Certificate[0]));
    }

    public static KeyStore readKeyWithCertsFromJKS(String path, char[] keyStorePassword) throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(path)) {
            keyStore.load(fis, keyStorePassword);
        }
        return keyStore;
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
