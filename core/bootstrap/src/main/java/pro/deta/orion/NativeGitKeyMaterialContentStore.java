package pro.deta.orion;

import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.keymaterial.KeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialSnapshot;
import pro.deta.orion.keymaterial.KeyMaterialStoreConflictException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
            GitRepositoryFileSnapshot snapshot = repository.loadFiles(refName, List.of(path));
            byte[] bytes = snapshot.files().get(path);
            String version = snapshot.version().orElse(refRevision);
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
        if (observation == null) {
            read();
        }
        if (!Objects.equals(observation.materialVersion(), expectedVersion)) {
            throw conflict();
        }

        try {
            NativeGitFileUpdate update = repositoryProvider.prepareFileUpdate(
                    repositoryName,
                    refName,
                    observation.refRevision(),
                    Map.of(path, bytes),
                    SAVE_MESSAGE,
                    GitCommitAuthor.EMPTY);
            List<RefUpdateResult> results = repositoryProvider.publish(
                    repositoryName,
                    update.objects(),
                    update.refUpdates(),
                    true);
            if (results.contains(RefUpdateResult.STALE)) {
                throw conflict();
            }
            String version = update.refUpdates().getFirst().newId();
            observation = new Observation(version, version);
            return version;
        } catch (KeyMaterialStoreConflictException conflict) {
            throw conflict;
        } catch (GitOperationException | RuntimeException failure) {
            throw new IOException("Cannot write key material store", failure);
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
