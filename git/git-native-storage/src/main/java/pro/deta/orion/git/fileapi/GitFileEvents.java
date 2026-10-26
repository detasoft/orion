package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.RepositoryRefChanged;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.util.Subscription;

import java.util.Objects;
import java.util.function.Consumer;

/** Owns a Git file and its dispatcher handler, reporting revisions whose file content changed. */
public final class GitFileEvents implements AutoCloseable {
    private final GitFile file;
    private final Subscription handler;

    public GitFileEvents(GitFile file, Consumer<CommitId> changed) {
        this.file = Objects.requireNonNull(file, "file");
        Objects.requireNonNull(changed, "changed");
        handler = file.events().subscribe(RepositoryRefChanged.class, event -> onRefChanged(event, changed));
    }

    public GitFile file() {
        return file;
    }

    private void onRefChanged(RepositoryRefChanged event, Consumer<CommitId> changed) {
        if (!file.matches(event)) return;
        if (event.update().newId().isEmpty()) {
            warn("Ref was deleted; skipping file notification", null);
            return;
        }
        CommitId revision = new CommitId(event.update().newId().orElseThrow().toHex());
        try {
            CommitId previous = event.update().expectedOld().isEmpty()
                    ? null : new CommitId(event.update().expectedOld().orElseThrow().toHex());
            if (!Objects.equals(file.objectId(previous), file.objectId(revision))) changed.accept(revision);
        } catch (GitOperationException | RuntimeException failure) {
            warn("Cannot inspect file change at " + revision, failure);
        }
    }

    private void warn(String message, Exception failure) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, file + ": " + message, failure);
    }

    @Override
    public void close() {
        handler.close();
    }
}
