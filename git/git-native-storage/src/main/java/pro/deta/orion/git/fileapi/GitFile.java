package pro.deta.orion.git.fileapi;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.RepositoryRefChanged;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.util.VcChangeDescription;


import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * A file bound to one repository, ref and path. Current revisions refresh the source;
 * pinned reads use the retained repository. Writes compare file object IDs, allowing unrelated
 * branch changes, then publish against the observed head. GitFileEvents owns change handling.
 */
public final class GitFile {
    private final NativeGitRepositoryProvider provider;
    private final String repositoryName;
    private final String ref;
    private final String path;

    public GitFile(NativeGitRepositoryProvider provider, String repositoryName, String ref, String path) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
        this.ref = GitFileApi.branchRefName(ref);
        this.path = GitFileApi.gitPath(path);
        provider.openForRead(repositoryName).valueOrFailure("Cannot open Git file repository");
    }

    public Optional<CommitId> revision() {
        NativeGitRepository repository = provider.openForRead(repositoryName)
                .valueOrFailure("Cannot open Git file repository");
        String revision = repository.refs().get(ref);
        return revision == null ? Optional.empty() : Optional.of(new CommitId(revision));
    }

    public byte[] read(CommitId revision) throws IOException, GitOperationException {
        return provider.retained(repositoryName).eventSource().files()
                .readBytes(Objects.requireNonNull(revision, "revision"), path);
    }

    public boolean exists(CommitId revision) throws GitOperationException {
        return objectId(Objects.requireNonNull(revision, "revision")) != null;
    }

    public GitFileAccess createAccess() throws IOException, GitOperationException {
        NativeGitRepository repository = provider.openForWrite(repositoryName)
                .valueOrFailure("Cannot open Git file repository");
        return repository.files().createAccess(ref);
    }

    public CommitId write(CommitId expectedRevision, byte[] bytes, VcChangeDescription metadata) throws Exception {
        Objects.requireNonNull(bytes, "bytes");
        return GitFileApi.run(createAccess(), access -> {
            CommitId current = access.baseRevision().map(id -> new CommitId(id.toHex())).orElse(null);
            if (!Objects.equals(current, expectedRevision)
                    && !Objects.equals(objectId(current), objectId(expectedRevision))) {
                throw new GitRefConflictException(new RefUpdate(new RefId(ref),
                        Optional.ofNullable(expectedRevision), Optional.ofNullable(current)), Optional.ofNullable(current));
            }
            access.write(path, bytes);
            return new CommitId(access.commit(metadata).value());
        });
    }

    public OrionEventManager events() {
        return provider.retained(repositoryName).events();
    }

    boolean matches(RepositoryRefChanged event) {
        return event.repository() == provider.retained(repositoryName).eventSource()
                && ref.equals(event.update().ref().value());
    }

    ObjectId objectId(CommitId revision) throws GitOperationException {
        if (revision == null) return null;
        try {
            return provider.retained(repositoryName).eventSource().files().objectId(revision, path);
        } catch (GitRepositoryFileNotFoundException missing) {
            return null;
        }
    }

    public boolean isCurrent(CommitId revision) throws GitOperationException {
        String head = provider.retained(repositoryName).refs().get(ref);
        CommitId current = head == null ? null : new CommitId(head);
        return Objects.equals(objectId(revision), objectId(current));
    }

    @Override
    public String toString() {
        return repositoryName + ":" + ref + "/" + path;
    }
}
