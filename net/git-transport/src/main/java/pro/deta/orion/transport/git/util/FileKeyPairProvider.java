package pro.deta.orion.transport.git.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.common.config.keys.PrivateKeyEntryDecoder;
import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.jcajce.spec.OpenSSHPrivateKeySpec;
import org.bouncycastle.jcajce.spec.OpenSSHPublicKeySpec;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

@Getter
@RequiredArgsConstructor
public class FileKeyPairProvider extends AbstractKeyPairProvider {

    private final List<Path> files;

    @Override
    public Iterable<KeyPair> loadKeys(SessionContext sessionContext) {
        if (!SecurityUtils.isBouncyCastleRegistered()) {
            throw new IllegalStateException("BouncyCastle must be registered as a JCE provider");
        }
        return () -> new Iterator<>() {
            private final Iterator<Path> iterator = files.iterator();
            private KeyPair nextKeyPair;
            private boolean nextKeyPairSet = false;

            @Override
            public boolean hasNext() {
                return nextKeyPairSet || setNextObject();
            }

            @Override
            public KeyPair next() {
                if (!nextKeyPairSet) {
                    if (!setNextObject()) {
                        throw new NoSuchElementException();
                    }
                }
                nextKeyPairSet = false;
                return nextKeyPair;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            private boolean setNextObject() {
                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    if (!file.toFile().isFile()) {
                        log.debug("File does not exist, skipping {}", file);
                        continue;
                    }
                    nextKeyPair = doLoadKey(sessionContext, file);
                    if (nextKeyPair != null) {
                        nextKeyPairSet = true;
                        return true;
                    }
                }
                return false;
            }

        };
    }


    private KeyPair doLoadKey(SessionContext sc, Path file) {
        try {

            try (PemReader r = new PemReader(new InputStreamReader(new FileInputStream(file.toFile())))) {
                PemObject pemObject = r.readPemObject();
                if ("OPENSSH PRIVATE KEY".equals(pemObject.getType())) {
                    // This reads a properly OpenSSH formatted ed25519 private key file.
                    // It is currently unused because the SSHD library in play doesn't work with proper keys.
                    // This is kept in the hope that in the future the library offers proper support.
                    try {
                        byte[] privateKeyContent = pemObject.getContent();
                        AsymmetricKeyParameter privateKeyParameters = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(privateKeyContent);
                        if (privateKeyParameters instanceof Ed25519PrivateKeyParameters) {
                            OpenSSHPrivateKeySpec privkeySpec = new OpenSSHPrivateKeySpec(privateKeyContent);

                            Ed25519PublicKeyParameters publicKeyParameters = ((Ed25519PrivateKeyParameters) privateKeyParameters).generatePublicKey();
                            OpenSSHPublicKeySpec pubKeySpec = new OpenSSHPublicKeySpec(OpenSSHPublicKeyUtil.encodePublicKey(publicKeyParameters));

                            KeyFactory kf = KeyFactory.getInstance("Ed25519", "BC");
                            PrivateKey privateKey = kf.generatePrivate(privkeySpec);
                            PublicKey publicKey = kf.generatePublic(pubKeySpec);
                            return new KeyPair(publicKey, privateKey);
                        } else {
                            log.warn("OpenSSH format is only supported for Ed25519 key type. Unable to read key " + file);
                        }
                    } catch (Exception e) {
                        log.warn("Unable to read key " + file, e);
                    }
                    return null;
                }

                if ("EDDSA PRIVATE KEY".equals(pemObject.getType())) {
                    // This reads the ed25519 key from a file format that we created in SshDaemon.
                    // The type EDDSA PRIVATE KEY was given by us and nothing official.
                    byte[] privateKeyContent = pemObject.getContent();
                    PrivateKeyEntryDecoder<? extends PublicKey, ? extends PrivateKey> decoder = SecurityUtils.getOpenSSHEDDSAPrivateKeyEntryDecoder();
                    PrivateKey privateKey = decoder.decodePrivateKey(sc, null, privateKeyContent, 0, privateKeyContent.length);
                    PublicKey publicKey = SecurityUtils.recoverEDDSAPublicKey(privateKey);
                    return new KeyPair(publicKey, privateKey);
                }
            }

            try (PEMParser r = new PEMParser(new InputStreamReader(new FileInputStream(file.toFile())))) {
                Object o = r.readObject();

                JcaPEMKeyConverter pemConverter = new JcaPEMKeyConverter();
                pemConverter.setProvider("BC");
                if (o instanceof PEMKeyPair) {
                    o = pemConverter.getKeyPair((PEMKeyPair) o);
                    return (KeyPair) o;
                } else if (o instanceof KeyPair) {
                    return (KeyPair) o;
                } else {
                    log.warn("Cannot read unsupported PEM object of type: " + o.getClass().getCanonicalName());
                }
            }

        } catch (Exception e) {
            log.warn("Unable to read key " + file, e);
        }
        return null;
    }

}
