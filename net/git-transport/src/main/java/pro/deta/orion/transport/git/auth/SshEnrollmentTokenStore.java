package pro.deta.orion.transport.git.auth;

import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.util.ConfigurationContext;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Singleton
public final class SshEnrollmentTokenStore {
    static final String TOKEN_OUTPUT_PREFIX = "SSH enrollment token: ";

    private static final String STATE_FILE_NAME = "ssh-enrollment-token.properties";
    private static final String STATE_VERSION = "1";
    private static final int RANDOM_BYTE_COUNT = 32;
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> NON_OWNER_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private final Path stateFile;
    private final boolean regenerate;
    private final PrintStream output;
    private final SecureRandom secureRandom;

    private TokenState state;
    private boolean started;

    @Inject
    public SshEnrollmentTokenStore(
            ConfigurationContext configurationContext,
            OrionRuntimeOptions runtimeOptions) {
        this(
                configurationContext.getBaseDir(),
                runtimeOptions.regenerateSshEnrollmentToken(),
                System.out,
                new SecureRandom());
    }

    private SshEnrollmentTokenStore(
            Path baseDir,
            boolean regenerate,
            PrintStream output,
            SecureRandom secureRandom) {
        this.stateFile = baseDir.resolve(STATE_FILE_NAME).toAbsolutePath().normalize();
        this.regenerate = regenerate;
        this.output = output;
        this.secureRandom = secureRandom;
    }

    @TestOnly
    static SshEnrollmentTokenStore forTest(
            Path baseDir,
            boolean regenerate,
            PrintStream output,
            SecureRandom secureRandom) {
        return new SshEnrollmentTokenStore(baseDir, regenerate, output, secureRandom);
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        if (regenerate || Files.notExists(stateFile)) {
            String token = generateToken();
            TokenState activeState = createActiveState(token);
            persist(activeState);
            state = activeState;
            output.println(TOKEN_OUTPUT_PREFIX + token);
        } else {
            state = load();
        }
        started = true;
    }

    public synchronized boolean consumeIfValid(String token, Runnable enrollment) {
        ensureStarted();
        if (token == null || state.status() != Status.ACTIVE || !matches(token, state)) {
            return false;
        }

        enrollment.run();
        TokenState consumedState = TokenState.consumed();
        persist(consumedState);
        state = consumedState;
        return true;
    }

    private String generateToken() {
        byte[] bytes = randomBytes();
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private TokenState createActiveState(String token) {
        byte[] salt = randomBytes();
        try {
            return new TokenState(Status.ACTIVE, salt, hash(salt, token));
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[RANDOM_BYTE_COUNT];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private TokenState load() {
        try {
            validateOwnerOnlyFile();
            Map<String, String> values = parseState(Files.readAllLines(stateFile, StandardCharsets.UTF_8));
            if (!STATE_VERSION.equals(values.get("version"))) {
                throw corruptState("unsupported version");
            }

            Status status = parseStatus(values.get("status"));
            if (status == Status.CONSUMED) {
                return TokenState.consumed();
            }
            byte[] salt = decodeRequired(values, "salt");
            byte[] hash = decodeRequired(values, "hash");
            if (salt.length != RANDOM_BYTE_COUNT || hash.length != 32) {
                throw corruptState("invalid salt or hash length");
            }
            return new TokenState(Status.ACTIVE, salt, hash);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("SSH enrollment token state cannot be read: " + stateFile, e);
        }
    }

    private void persist(TokenState newState) {
        Path parent = stateFile.getParent();
        Path temporaryFile = null;
        try {
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(
                    parent,
                    ".ssh-enrollment-token-",
                    ".tmp",
                    ownerOnlyAttributes(parent));
            Files.writeString(temporaryFile, serialize(newState), StandardCharsets.UTF_8);
            setOwnerOnlyPermissions(temporaryFile);
            try {
                Files.move(
                        temporaryFile,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic token-state replacement is not supported", e);
            }
            setOwnerOnlyPermissions(stateFile);
        } catch (IOException e) {
            throw new IllegalStateException("SSH enrollment token state cannot be persisted: " + stateFile, e);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The target state is already authoritative; a failed temp cleanup is non-fatal.
                }
            }
        }
    }

    private String serialize(TokenState tokenState) {
        StringBuilder serialized = new StringBuilder()
                .append("version=").append(STATE_VERSION).append('\n')
                .append("status=").append(tokenState.status().value).append('\n');
        if (tokenState.status() == Status.ACTIVE) {
            serialized.append("salt=")
                    .append(Base64.getEncoder().encodeToString(tokenState.salt()))
                    .append('\n')
                    .append("hash=").append(Base64.getEncoder().encodeToString(tokenState.hash())).append('\n');
        }
        return serialized.toString();
    }

    private void validateOwnerOnlyFile() throws IOException {
        if (!supportsPosix(stateFile)) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(stateFile);
        for (PosixFilePermission permission : NON_OWNER_PERMISSIONS) {
            if (permissions.contains(permission)) {
                throw new IOException("SSH enrollment token state must be accessible only by its owner");
            }
        }
    }

    private static Map<String, String> parseState(Iterable<String> lines) {
        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            String previous = separator <= 0
                    ? null
                    : values.put(line.substring(0, separator), line.substring(separator + 1));
            if (separator <= 0 || previous != null) {
                throw corruptState("invalid property");
            }
        }
        return values;
    }

    private static Status parseStatus(String value) {
        for (Status status : Status.values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw corruptState("invalid status");
    }

    private static byte[] decodeRequired(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw corruptState("missing " + key);
        }
        return Base64.getDecoder().decode(value);
    }

    private static boolean matches(String token, TokenState tokenState) {
        return MessageDigest.isEqual(tokenState.hash(), hash(tokenState.salt(), token));
    }

    private static byte[] hash(byte[] salt, String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static FileAttribute<?>[] ownerOnlyAttributes(Path parent) throws IOException {
        if (supportsPosix(parent)) {
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS)};
        }
        return new FileAttribute<?>[0];
    }

    private static void setOwnerOnlyPermissions(Path path) throws IOException {
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS);
        }
    }

    private static boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static IllegalStateException corruptState(String detail) {
        return new IllegalStateException("SSH enrollment token state is corrupt: " + detail);
    }

    private void ensureStarted() {
        if (!started) {
            throw new IllegalStateException("SSH enrollment token store has not been started");
        }
    }

    private enum Status {
        ACTIVE("active"),
        CONSUMED("consumed");

        private final String value;

        Status(String value) {
            this.value = value;
        }
    }

    private record TokenState(Status status, byte[] salt, byte[] hash) {
        private static TokenState consumed() {
            return new TokenState(Status.CONSUMED, new byte[0], new byte[0]);
        }

        private TokenState {
            salt = salt.clone();
            hash = hash.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }
    }
}
