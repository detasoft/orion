package pro.deta.orion.crypto;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.util.*;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static pro.deta.orion.util.KeyUtils.*;

@Singleton
@Slf4j
@ToString
public class ServerKeyService {
    private final Path hostKeysDir;
    @Getter
    private final KeyPair keyPair;
    private final List<KeyPair> serverKeys = new ArrayList<>();

    @Inject
    public ServerKeyService(ConfigurationContext configurationContext) {
        this(configurationContext.getBaseDir());
    }

    ServerKeyService(Path baseDir) {
        this.hostKeysDir = baseDir.resolve("server-keys");
        FileUtils.mkdirs(hostKeysDir);
        switch(createServerKeyIfNotExist(hostKeysDir.resolve("rsa.pem"), "RSA", 2048)) {
            case Result.Failure<KeyPair>(var code, var message, var throwable) -> {
                log.error("[{}]: {}", code, message, throwable);
                throw new IllegalStateException(message, throwable);
            }
            case Result.Success<KeyPair>(var k) -> {
                log.warn("Loaded main server public key: {}", k.getPublic());
                keyPair = k;
            }
        }
        createServerKeyIfNotExist(hostKeysDir.resolve("ecdsa.pem"), "ECDSA", 256).onSuccess(this::addKey);
//        createServerKeyIfNotExist(hostKeysDir.resolve("eddsa.pem"), "EdDSA", 0).onSuccess(this::addKey);
    }

    private void addKey(KeyPair key) {
        serverKeys.add(key);
    }

    private Result<KeyPair> createServerKeyIfNotExist(Path keyFile, String algorithm, int keySize) {
        Result<KeyPair> kp = readKeyFromFile(keyFile);
        return switch (kp) {
            case Result.Failure<KeyPair> v ->
                generateKeyPair(algorithm, keySize).onSuccess((k) -> {
                    KeyUtils.savePrivateKey(k.getPrivate(), keyFile);
                })
                        .failOnFailure("Failed to generate key");
            case Result.Success<KeyPair> v -> v;
        };
    }

    public Collection<KeyPair> getKeyPairs() {
        return Collections.unmodifiableList(serverKeys);
    }

    public List<PublicKey> getPublicKeys() {
        return this.serverKeys.stream().map(it -> it.getPublic()).collect(Collectors.toList());
    }
}
