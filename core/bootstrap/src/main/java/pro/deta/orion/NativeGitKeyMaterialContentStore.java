package pro.deta.orion;

import pro.deta.orion.git.fileapi.GitCommitMetadata;
import pro.deta.orion.git.nativestorage.RepositoryRefChanged;
import pro.deta.orion.keymaterial.KeyMaterialUpdated;
import pro.deta.orion.util.Subscription;

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
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

final class NativeGitKeyMaterialContentStore implements KeyMaterialContentStore {
    private static final String SAVE_MESSAGE = "Update server identity material";

    private final NativeGitRepositoryProvider repositoryProvider;
    private final String repositoryName;
    private final String refName;
    private final String path;
    private final NativeGitRepository revisionRepository;
    private final Subscription repositorySubscription;
    private boolean closed;

    NativeGitKeyMaterialContentStore(
            NativeGitRepositoryProvider repositoryProvider,
            String repositoryName,
            String refName,
            String path) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.repositoryName = required(repositoryName, "repository name");
        this.refName = required(refName, "ref name");
        this.path = required(path, "path");
        NativeGitRepository repository = repositoryProvider.openForRead(repositoryName)
                .valueOrFailure("Cannot open key material repository");
        revisionRepository = repository.eventSource();
        repositorySubscription = revisionRepository.events()
                .subscribe(RepositoryRefChanged.class, this::onRefChanged);
    }

    @Override
    public synchronized Optional<KeyMaterialSnapshot> read() throws IOException {
        String revision = openForRead().refs().get(refName);
        return revision == null ? Optional.empty() : read(revision);
    }

    @Override
    public synchronized Optional<KeyMaterialSnapshot> read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        try {
            byte[] bytes = revisionRepository.files().readBytes(new ObjectId(revision), path);
            try {
                return Optional.of(new KeyMaterialSnapshot(bytes, revision));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } catch (GitRepositoryFileNotFoundException missing) {
            return Optional.empty();
        } catch (GitOperationException | RuntimeException failure) {
            throw new IOException("Cannot read key material store at " + revision, failure);
        }
    }

    @Override
    public String write(byte[] bytes, String expectedVersion) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        String observedRef = openForRead().refs().get(refName);
        Optional<KeyMaterialSnapshot> current = observedRef == null ? Optional.empty() : read(observedRef);
        Optional<KeyMaterialSnapshot> expected = expectedVersion == null ? Optional.empty() : read(expectedVersion);
        if (!Objects.equals(contentVersion(current), contentVersion(expected))) throw conflict();

        try {
            NativeGitRepository repository = repositoryProvider.openForWrite(repositoryName)
                    .valueOrFailure("Cannot open key material repository");
            return repository.files().withAccess(refName, observedRef,
                    new GitCommitMetadata(GitCommitAuthor.EMPTY, SAVE_MESSAGE), access -> {
                        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
                            access.write(path, FileMode.REGULAR_FILE, bytes.length, input);
                        }
                        String next = access.refUpdates().getFirst().newId().orElseThrow().toHex();
                        access.apply();
                        return next;
                    });
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

    private synchronized void onRefChanged(RepositoryRefChanged event) {
        if (closed || event.repository() != revisionRepository || !refName.equals(event.update().ref().value())) {
            return;
        }
        if (event.update().newId().isEmpty()) {
            warn("Key material ref was deleted; retaining active material", null);
            return;
        }
        String revision = event.update().newId().orElseThrow().toHex();
        try {
            Optional<KeyMaterialSnapshot> current = read(revision);
            if (current.isEmpty()) {
                warn("Key material file is missing at " + revision + "; retaining active material", null);
                return;
            }
            Optional<KeyMaterialSnapshot> previous = event.update().expectedOld().isEmpty()
                    ? Optional.empty() : read(event.update().expectedOld().orElseThrow().toHex());
            if (!Objects.equals(contentVersion(previous), contentVersion(current))) {
                revisionRepository.events().publish(new KeyMaterialUpdated(revision));
            }
        } catch (IOException | RuntimeException failure) {
            warn("Cannot inspect key material change at " + revision, failure);
        }
    }

    private static String contentVersion(Optional<KeyMaterialSnapshot> snapshot) {
        if (snapshot.isEmpty()) return null;
        byte[] bytes = snapshot.orElseThrow().bytes();
        try {
            return materialVersion(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void warn(String message, Exception failure) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                repositoryName + ":" + refName + "/" + path + ": " + message, failure);
    }

    @Override
    public synchronized void close() {
        closed = true;
        repositorySubscription.close();
    }
}
