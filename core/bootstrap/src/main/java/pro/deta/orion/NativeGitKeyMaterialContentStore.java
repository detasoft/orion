package pro.deta.orion;

import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.keymaterial.KeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialSnapshot;
import pro.deta.orion.keymaterial.KeyMaterialStoreConflictException;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

final class NativeGitKeyMaterialContentStore implements KeyMaterialContentStore {
    private static final String SAVE_MESSAGE = "Update server identity material";

    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String refName;
    private final String path;
    private Observation observation;

    NativeGitKeyMaterialContentStore(
            NativeGitRepositoryProvider repositoryProvider,
            String repositoryName,
            String refName,
            String path) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.repositoryName = required(repositoryName, "repository name");
        this.refName = required(refName, "ref name");
        this.path = required(path, "path");
    }

    @Override
    public synchronized Optional<KeyMaterialSnapshot> read() throws IOException {
        NativeGitRepository repository = openForRead();
        String refRevision = repository.refs().get(refName);
        if (refRevision == null) {
            observation = new Observation(null, null);
            return Optional.empty();
        }
        try {
            byte[] bytes = repository.files().readFile(new ObjectId(refRevision), path,
                    (type, size, base, input) -> input.readBytes(Math.toIntExact(size)));
            String version = materialVersion(bytes);
            observation = new Observation(version, refRevision);
            return Optional.of(new KeyMaterialSnapshot(bytes, version));
        } catch (GitRepositoryFileNotFoundException missing) {
            observation = new Observation(null, refRevision);
            return Optional.empty();
        } catch (GitOperationException | RuntimeException failure) {
            throw new IOException("Cannot read key material store", failure);
        }
    }

    @Override
    public synchronized String write(byte[] bytes, String expectedVersion) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        read();
        if (!Objects.equals(observation.materialVersion(), expectedVersion)) {
            throw conflict();
        }

        try {
            NativeGitRepository repository = repositoryProvider.openForWrite(repositoryName)
                    .valueOrFailure("Cannot open key material repository");
            String revision = repository.files().withAccess(refName, observation.refRevision(),
                    SAVE_MESSAGE, GitCommitAuthor.EMPTY, access -> {
                        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                            access.write(path, FileMode.REGULAR_FILE, bytes.length, input);
                        }
                        String next = access.refUpdates().getFirst().newId().orElseThrow().toHex();
                        access.apply();
                        return next;
                    });
            String version = materialVersion(bytes);
            observation = new Observation(version, revision);
            return version;
        } catch (GitRefConflictException failure) {
            throw conflict();
        } catch (Exception failure) {
            throw new IOException("Cannot write key material store", failure);
        }
    }

    private static String materialVersion(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private NativeGitRepository openForRead() throws IOException {
        try {
            return repositoryProvider.openForRead(repositoryName)
                    .valueOrFailure("Cannot open key material repository");
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read key material store", failure);
        }
    }

    private static KeyMaterialStoreConflictException conflict() {
        return new KeyMaterialStoreConflictException("Key material store changed before save");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Key material " + label + " must not be empty");
        }
        return value;
    }

    private record Observation(String materialVersion, String refRevision) {
    }
}
