package pro.deta.orion.crypto;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.FileUtils;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.LazySupplier;
import pro.deta.orion.util.Result;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.util.KeyUtils.generateKeyPair;
import static pro.deta.orion.util.KeyUtils.readKeyFromFile;

@Singleton
@Slf4j
@ToString
public class SshHostKeyService {
    private final Path hostKeysDir;
    private final LazySupplier<List<KeyPair>> hostKeys;

    @Inject
    public SshHostKeyService(ConfigurationContext configurationContext) {
        this(configurationContext.getBaseDir());
    }

    SshHostKeyService(Path baseDir) {
        this.hostKeysDir = baseDir.resolve("ssh-host-keys");
        this.hostKeys = new LazySupplier<>(this::loadHostKeys);
    }

    public List<KeyPair> getKeyPairs() {
        return hostKeys.value();
    }

    private List<KeyPair> loadHostKeys() {
        List<KeyPair> loadedKeys = new ArrayList<>();
        FileUtils.mkdirs(hostKeysDir);
        switch (createHostKeyIfNotExist(hostKeysDir.resolve("rsa.pem"), "RSA", 2048)) {
            case Result.Failure<KeyPair>(var code, var message, var throwable) -> {
                log.error("[{}]: {}", code, message, throwable);
                throw new IllegalStateException(message, throwable);
            }
            case Result.Success<KeyPair>(var k) -> {
                log.warn("Loaded main SSH host public key: {}", k.getPublic());
                loadedKeys.add(k);
            }
        }
        createHostKeyIfNotExist(hostKeysDir.resolve("ecdsa.pem"), "ECDSA", 256).onSuccess(loadedKeys::add);
        return List.copyOf(loadedKeys);
    }

    private Result<KeyPair> createHostKeyIfNotExist(Path keyFile, String algorithm, int keySize) {
        Result<KeyPair> keyPair = readKeyFromFile(keyFile);
        return switch (keyPair) {
            case Result.Failure<KeyPair> ignored ->
                    generateKeyPair(algorithm, keySize).onSuccess((key) -> KeyUtils.savePrivateKey(key.getPrivate(), keyFile))
                            .failOnFailure("Failed to generate SSH host key");
            case Result.Success<KeyPair> success -> success;
        };
    }
}
