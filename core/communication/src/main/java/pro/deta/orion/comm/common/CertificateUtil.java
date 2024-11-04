package pro.deta.orion.comm.common;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLParameters;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

public class CertificateUtil {
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    public static X509Certificate generateSelfSigned(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 1000L * 60);
        Date notAfter = new Date(now + 1000L * 60 * 60 * 24);

        X500Name name = new X500Name("CN=Test Certificate");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name,  // issuer
                BigInteger.valueOf(now),
                notBefore,
                notAfter,
                name,  // subject
                keyPair.getPublic()
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    public static KeyManager[] getKeyManager() {
        try {
            KeyPair keyPair = generateKeyPair();
            X509Certificate cert = generateSelfSigned(keyPair);

            // 3. Put key and cert in KeyStore
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            ks.setKeyEntry("alias", keyPair.getPrivate(), "password".toCharArray(),
                    new java.security.cert.Certificate[]{cert});

            // 4. Init KeyManagerFactory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, "password".toCharArray());
            return kmf.getKeyManagers();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
