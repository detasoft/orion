package pro.deta.orion.crypto;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.util.*;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.util.List;

import static pro.deta.orion.util.KeyUtils.*;

@Singleton
@Slf4j
@ToString
public class ServerIdentityKeyService implements PublicKeysProvider, ServerKeySigner {
    private final Path identityKeysDir;
    private final LazySupplier<KeyPair> identitySigningKey;

    @Inject
    public ServerIdentityKeyService(ConfigurationContext configurationContext) {
        this(configurationContext.getBaseDir());
    }

    ServerIdentityKeyService(Path baseDir) {
        this.identityKeysDir = baseDir.resolve("server-identity");
        this.identitySigningKey = new LazySupplier<>(this::loadIdentitySigningKey);
    }

    private KeyPair loadIdentitySigningKey() {
        FileUtils.mkdirs(identityKeysDir);
        return switch (createIdentityKeyIfNotExist(identityKeysDir.resolve("signing-rsa.pem"), "RSA", 2048)) {
            case Result.Failure<KeyPair>(var code, var message, var throwable) -> {
                log.error("[{}]: {}", code, message, throwable);
                throw new IllegalStateException(message, throwable);
            }
            case Result.Success<KeyPair>(var k) -> {
                log.warn("Loaded server identity public key: {}", k.getPublic());
                yield k;
            }
        };
    }

    private Result<KeyPair> createIdentityKeyIfNotExist(Path keyFile, String algorithm, int keySize) {
        Result<KeyPair> kp = readKeyFromFile(keyFile);
        return switch (kp) {
            case Result.Failure<KeyPair> v ->
                generateKeyPair(algorithm, keySize).onSuccess((k) -> {
                    KeyUtils.savePrivateKey(k.getPrivate(), keyFile);
                })
                        .failOnFailure("Failed to generate server identity key");
            case Result.Success<KeyPair> v -> v;
        };
    }

    private KeyPair getIdentitySigningKey() {
        return identitySigningKey.value();
    }

    @Override
    public SigningKey rsaSha256SigningKey() {
        return new RsaSha256SigningKey(getIdentitySigningKey());
    }

    @Override
    public List<PublicKey> getPublicKeys() {
        return List.of(getIdentitySigningKey().getPublic());
    }

    private static final class RsaSha256SigningKey implements SigningKey {
        private final KeyPair keyPair;

        private RsaSha256SigningKey(KeyPair keyPair) {
            this.keyPair = keyPair;
        }

        @Override
        public PublicKey publicKey() {
            return keyPair.getPublic();
        }

        @Override
        public byte[] sign(byte[] data) {
            try {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(keyPair.getPrivate());
                signature.update(data);
                return signature.sign();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot sign data with server key", e);
            }
        }
    }
}
