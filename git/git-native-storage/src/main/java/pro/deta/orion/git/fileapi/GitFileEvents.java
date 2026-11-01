package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.RepositoryRefChanged;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pro.deta.orion.event.OrionEventManager;

/** Owns a Git file and its dispatcher handler, reporting revisions whose file content changed. */
public final class GitFileEvents implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final GitFile file;

    public GitFileEvents(GitFile file) {
        this.file = Objects.requireNonNull(file, "file");
        file.events().forTarget(this).subscribe(RepositoryRefChanged.class, null, this::accept);
    }

    public GitFile file() {
        return file;
    }

    public OrionEventManager events() {
        return file.events();
    }

    public UUID source() {
        return id;
    }

    public boolean matches(GitFileChanged event) {
        return id.equals(event.source());
    }

    private void accept(RepositoryRefChanged event) {
        if (!file.matches(event)) return;
        if (event.update().newId().isEmpty()) {
            warn("Ref was deleted; skipping file notification", null);
            return;
        }
        CommitId revision = new CommitId(event.update().newId().orElseThrow().toHex());
        try {
            CommitId previous = event.update().expectedOld().isEmpty()
                    ? null : new CommitId(event.update().expectedOld().orElseThrow().toHex());
            if (!Objects.equals(file.objectId(previous), file.objectId(revision))) {
                events().publish(new GitFileChanged(id, Optional.ofNullable(previous), revision));
            }
        } catch (GitOperationException | RuntimeException failure) {
            warn("Cannot inspect file change at " + revision, failure);
        }
    }

    private void warn(String message, Exception failure) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, file + ": " + message, failure);
    }

    @Override
    public void close() {
        file.events().forTarget(this).unsubscribe(RepositoryRefChanged.class, null);
    }
}
