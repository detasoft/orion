package pro.deta.orion.git.nativestorage;

import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.git.parser.v2.data.RefUpdate;

import java.util.Objects;

/** A completed ref change in one repository instance. */
public final class RepositoryRefChanged extends OrionEvent {
    private final NativeGitRepository repository;
    private final RefUpdate update;

    public RepositoryRefChanged(NativeGitRepository repository, RefUpdate update) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.update = Objects.requireNonNull(update, "update");
    }

    public NativeGitRepository repository() {
        return repository;
    }

    public RefUpdate update() {
        return update;
    }
}
