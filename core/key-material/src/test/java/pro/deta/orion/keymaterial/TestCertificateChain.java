package pro.deta.orion.keymaterial;

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

final class TestCertificateChain {
    private static final AtomicLong SERIAL = new AtomicLong(1);

    private TestCertificateChain() {
    }

    static Authority root(String commonName) throws Exception {
        KeyPair keyPair = keyPair();
        X500Principal subject = new X500Principal("CN=" + commonName);
        X509Certificate certificate = issue(subject, keyPair, subject, keyPair, true);
        return new Authority(keyPair, certificate);
    }

    static Authority intermediate(String commonName, Authority issuer) throws Exception {
        KeyPair keyPair = keyPair();
        X500Principal subject = new X500Principal("CN=" + commonName);
        X509Certificate certificate = issue(
                issuer.certificate().getSubjectX500Principal(),
                issuer.keyPair(),
                subject,
                keyPair,
                true);
        return new Authority(keyPair, certificate);
    }

    static X509Certificate leaf(String commonName, KeyPair keyPair, Authority issuer) throws Exception {
        return issue(
                issuer.certificate().getSubjectX500Principal(),
                issuer.keyPair(),
                new X500Principal("CN=" + commonName),
                keyPair,
                false);
    }

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate issue(
            X500Principal issuer,
            KeyPair issuerKeyPair,
            X500Principal subject,
            KeyPair subjectKeyPair,
            boolean authority) throws Exception {
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(SERIAL.getAndIncrement()),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(30, ChronoUnit.DAYS)),
                subject,
                subjectKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(authority));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(authority
                        ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                        : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(issuerKeyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
        certificate.verify(issuerKeyPair.getPublic());
        return certificate;
    }

    record Authority(KeyPair keyPair, X509Certificate certificate) {
    }
}
