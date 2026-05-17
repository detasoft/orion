package pro.deta.orion.keymaterial;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

public class KeyMaterialStorageCertificateFactory {
    private final Clock clock;
    private final Duration validity;
    private final SecureRandom secureRandom;

    public KeyMaterialStorageCertificateFactory() {
        this(Clock.systemUTC(), KeyMaterialConstants.DEFAULT_STORAGE_CERTIFICATE_VALIDITY);
    }

    public KeyMaterialStorageCertificateFactory(Clock clock, Duration validity) {
        this(clock, validity, new SecureRandom());
    }

    KeyMaterialStorageCertificateFactory(Clock clock, Duration validity, SecureRandom secureRandom) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null");
        }
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("Storage certificate validity must be positive");
        }
        if (secureRandom == null) {
            throw new IllegalArgumentException("Secure random must not be null");
        }
        this.clock = clock;
        this.validity = validity;
        this.secureRandom = secureRandom;
    }

    public X509Certificate create(String alias, String purpose, KeyPair keyPair) throws GeneralSecurityException {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Key material purpose must not be empty");
        }
        if (keyPair == null || keyPair.getPrivate() == null || keyPair.getPublic() == null) {
            throw new IllegalArgumentException("Key pair must include both public and private keys");
        }

        Instant now = clock.instant();
        X500Name subject = subject(alias, purpose);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                serialNumber(),
                Date.from(now.minus(KeyMaterialConstants.STORAGE_CERTIFICATE_NOT_BEFORE_SKEW)),
                Date.from(now.plus(validity)),
                subject,
                keyPair.getPublic());
        try {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm(keyPair))
                    .build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .getCertificate(builder.build(signer));
            certificate.verify(keyPair.getPublic());
            return certificate;
        } catch (CertIOException e) {
            throw new GeneralSecurityException("Cannot add storage certificate extensions", e);
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException("Cannot create storage certificate signer", e);
        } catch (CertificateException e) {
            throw new GeneralSecurityException("Cannot convert storage certificate", e);
        }
    }

    private static X500Name subject(String alias, String purpose) {
        return new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, alias)
                .addRDN(BCStyle.OU, purpose)
                .addRDN(BCStyle.O, KeyMaterialConstants.STORAGE_CERTIFICATE_ORGANIZATION)
                .build();
    }

    private BigInteger serialNumber() {
        return new BigInteger(KeyMaterialConstants.STORAGE_CERTIFICATE_SERIAL_BITS, secureRandom).add(BigInteger.ONE);
    }

    private static String signatureAlgorithm(KeyPair keyPair) {
        String algorithm = keyPair.getPrivate().getAlgorithm().toUpperCase(Locale.ROOT);
        if (algorithm.contains(KeyMaterialConstants.RSA_ALGORITHM)) {
            return KeyMaterialConstants.SHA256_WITH_RSA_SIGNATURE;
        }
        if (algorithm.equals(KeyMaterialConstants.EC_ALGORITHM)
                || algorithm.contains(KeyMaterialConstants.ECDSA_ALGORITHM_FRAGMENT)) {
            return KeyMaterialConstants.SHA256_WITH_ECDSA_SIGNATURE;
        }
        if (algorithm.contains(KeyMaterialConstants.ED25519_ALGORITHM)
                || algorithm.contains(KeyMaterialConstants.EDDSA_ALGORITHM_FRAGMENT)) {
            return KeyMaterialConstants.ED25519_ALGORITHM;
        }
        throw new IllegalArgumentException("Unsupported storage certificate key algorithm: " + keyPair.getPrivate().getAlgorithm());
    }
}
