package pro.deta.orion.util;

import lombok.extern.slf4j.Slf4j;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.apache.sshd.common.config.keys.*;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.common.util.io.output.SecureByteArrayOutputStream;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.common.util.security.eddsa.OpenSSHEd25519PrivateKeyEntryDecoder;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static pro.deta.orion.util.Result.Failure.generalFailure;

@Slf4j
public class KeyUtils {
    public static void savePrivateKey(PrivateKey privateKey, Path path) {
        try {
            OutputStream os = preparePrivateKeyFile(path);
            PemWriter w = new PemWriter(new OutputStreamWriter(os));
            String algorithm = privateKey.getAlgorithm();
            if (algorithm.equals("ED25519")) {
                // This generates a proper OpenSSH formatted ed25519 private key path.
                // It is currently unused because the SSHD library in play doesn't work with proper keys.
                // This is kept in the hope that in the future the library offers proper support.
                AsymmetricKeyParameter keyParam = PrivateKeyFactory.createKey(privateKey.getEncoded());
                byte[] encKey = OpenSSHPrivateKeyUtil.encodePrivateKey(keyParam);
                w.writeObject(new PemObject("OPENSSH PRIVATE KEY", encKey));
            } else if (algorithm.equals("EdDSA")) {
                // This saves the ed25519 key in a path format that the current SSHD library can work with.
                // We call it EDDSA PRIVATE KEY, but that string is given by us and nothing official.
                if (privateKey instanceof EdDSAPrivateKey) {
                    OpenSSHEd25519PrivateKeyEntryDecoder encoder = (OpenSSHEd25519PrivateKeyEntryDecoder)SecurityUtils.getOpenSSHEDDSAPrivateKeyEntryDecoder();
                    EdDSAPrivateKey dsaPrivateKey = (EdDSAPrivateKey)privateKey;
                    // Jumping through some hoops here, because the decoder expects the key type as a string at the
                    // start, but the encoder doesn't put it in. So we have to put it in ourselves.
                    SecureByteArrayOutputStream encos = new SecureByteArrayOutputStream();
                    String type = encoder.encodePrivateKey(encos, dsaPrivateKey, null);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    KeyEntryResolver.encodeString(bos, type);
                    encos.writeTo(bos);
                    w.writeObject(new PemObject("EDDSA PRIVATE KEY", bos.toByteArray()));
                } else {
                    log.warn("Unable to encode EdDSA key, got key type {} alg: {}", privateKey.getClass().getCanonicalName(), privateKey.getAlgorithm());
                }
            } else {
                w.writeObject(new JcaMiscPEMGenerator(privateKey));
            }
            w.flush();
            w.close();
        } catch (Exception e) {
            log.warn(MessageFormat.format("Unable to generate {0} keypair", privateKey.getAlgorithm()), e);
        }
    }

    public static Result<KeyPair> generateRSAKeyPair() {
        return generateKeyPair("RSA", 2048);
    }

    private static OutputStream preparePrivateKeyFile(Path file) throws IOException {
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException ignored) {
        } finally {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        return new FileOutputStream(file.toFile());
    }

    public static Result<KeyPair> generateKeyPair(String algorithm, int keySize) {
        try {
            KeyPairGenerator generator = SecurityUtils.getKeyPairGenerator(algorithm);
            if (keySize != 0) {
                generator.initialize(keySize);
                log.info("Generating {}-{} SSH host keypair...", algorithm, keySize);
            } else {
                log.info("Generating {} SSH host keypair...", algorithm);
            }
            return new Result.Success<>(generator.generateKeyPair());
        } catch (Exception e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e);
        }
    }

    public static String publicKeyToString(PublicKey aPublic) {
        StringWriter stringWriter = new StringWriter(2048);
        PemWriter w = null;
        try {
            w = new PemWriter(stringWriter);
            w.writeObject(new PemObject("PUBLIC KEY", aPublic.getEncoded()));
        } catch (Exception e) {
            log.error("Error while exporting key to string {}", aPublic, e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException e) {
                    log.error("Error while closing writer {}", aPublic, e);
                }
            }
        }
        return stringWriter.toString();
    }

    public static Result<KeyPair> readKeyPair(String algorithm, Path tmp) {
        try {
            KeyFactory factory = KeyFactory.getInstance(algorithm);
            PEMParser pp = new PEMParser(new FileReader(tmp.toFile()));
            PKCS8EncodedKeySpec privKeySpec = new PKCS8EncodedKeySpec(pp.readPemObject().getContent());
            PrivateKey pk = factory.generatePrivate(privKeySpec);
            RSAPrivateCrtKey privk = (RSAPrivateCrtKey)pk;
            RSAPublicKeySpec publicKeySpec = new java.security.spec.RSAPublicKeySpec(privk.getModulus(), privk.getPublicExponent());

            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            PublicKey myPublicKey = keyFactory.generatePublic(publicKeySpec);

            return new Result.Success<>(new KeyPair(myPublicKey, pk));
        } catch (Exception e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e);
        }
    }

    public static Result<KeyPair> readRSAKeyPair(Path tmp) {
        return readKeyPair("RSA", tmp);
    }

    public static KeyFactory getRSAKeyFactory() throws NoSuchAlgorithmException {
        return KeyFactory.getInstance("RSA");
    }

    public static Result<KeyPair> readKeyFromFile(Path p) {
        if (!Files.exists(p) || Files.isDirectory(p)) {
            return new Result.Failure<>(Result.FailureCode.NOT_EXISTS);
        }
        try (
                FileReader fileReader = new FileReader(p.toFile());
                PEMParser pp = new PEMParser(fileReader);
        ) {
            Object o = pp.readObject();
            if (o instanceof PEMKeyPair) {
                return new Result.Success<>(getJcaPEMKeyConverter().getKeyPair((PEMKeyPair) o));
            } else if (o instanceof PEMEncryptedKeyPair) {
                return generalFailure("Cannot read encrypted PEM key" + p);
            } else if (o instanceof KeyPair) {
                return new Result.Success<>((KeyPair) o);
            }
            return generalFailure("Error while reading key from file " + o);
        } catch (Exception e) {
            return generalFailure("Error while reading key from file {}" + p, e);
        }
    }

        private static JcaPEMKeyConverter getJcaPEMKeyConverter() {
        JcaPEMKeyConverter pemConverter = new JcaPEMKeyConverter();
        pemConverter.setProvider(new BouncyCastleProvider());
        return pemConverter;
    }

    public static PublicKey toRSAPublicKey(byte[] publicKey) {
        try {
            return getRSAKeyFactory().generatePublic(new X509EncodedKeySpec(publicKey));
        } catch (Exception e) {
            throw new IllegalArgumentException("Can't create public key", e);
        }
    }

    private static final Pattern REGEXP_BASE64 = Pattern.compile("(AAAA[-A-Za-z0-9+/]*={0,3})");

    public static PublicKey readPublicKeyFromString(String key) {
        Matcher m = REGEXP_BASE64.matcher(key);
        try {
            if (key.startsWith("-----BEGIN PUBLIC KEY-----")) {
                PEMParser pp = new PEMParser(new StringReader(key));
                Object o = pp.readObject();
                return getJcaPEMKeyConverter().getPublicKey((SubjectPublicKeyInfo) o);
            } else if (m.find()) {
                String value = m.group(1);
                byte[] decoded = Base64.getDecoder().decode(value);
                AuthorizedKeyEntry ake = new AuthorizedKeyEntry();
                ake.setKeyType(recoverPublicKeyType(decoded));
                ake.setKeyData(decoded);
                return ake.resolvePublicKey(null, PublicKeyEntryResolver.IGNORING);
            }
            throw new IllegalArgumentException("Can't parse the key: " + key);
        } catch (Exception e) {
            throw new IllegalArgumentException("Can't read key from source: " + key, e);
        }
    }

    private static String recoverPublicKeyType(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data);
        int len = bb.getInt();
        return StandardCharsets.US_ASCII.decode(bb.slice(bb.position(), len)).toString();
    }

    public byte[] generateSalt16Byte() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);

        return salt;
    }
}
